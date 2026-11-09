const json = (value, status = 200) => Response.json(value, { status });

async function keyMatches(given, expected) {
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(given)),
    crypto.subtle.digest('SHA-256', enc.encode(expected)),
  ]);
  if (typeof crypto.subtle.timingSafeEqual === 'function') {
    return crypto.subtle.timingSafeEqual(a, b);
  }
  const x = new Uint8Array(a), y = new Uint8Array(b);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

const keyPattern = /^(tt\d{5,10}|kp-\d{1,10}|tmdb-(movie|tv)-\d{1,10}|ai-[a-z0-9-]{1,80})$/;
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const integer = v => Number.isSafeInteger(v) && v >= 0;
class InputError extends Error {
  constructor(code, status = 400) { super(code); this.status = status; }
}
const fail = (code, status) => { throw new InputError(code, status); };
const columns = {
  titles: ['key', 'data'],
  episodes: ['title_key', 'season', 'episode', 'watched'],
  positions: ['title_key', 'season', 'episode', 'position_ms', 'duration_ms'],
};

async function readBatch(request) {
  const reader = request.body?.getReader();
  let size = 0;
  const chunks = [];
  if (reader) {
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 1048576) { await reader.cancel(); fail('too_large', 413); }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let body;
  try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { fail('bad_json'); }
  if (!object(body) || Object.keys(body).some(k => !Object.hasOwn(columns, k))) fail('bad_batch');
  let count = 0;
  for (const table of Object.keys(columns)) {
    if (body[table] === undefined) body[table] = [];
    if (!Array.isArray(body[table])) fail('bad_batch');
    count += body[table].length;
  }
  if (count > 500) fail('too_many', 413);
  const now = Date.now();
  for (const [table, rows] of Object.entries(body)) {
    for (const row of rows) {
      if (!object(row)) fail('bad_data');
      const key = table === 'titles' ? row.key : row.title_key;
      if (typeof key !== 'string' || !keyPattern.test(key)) fail('bad_key_format');
      if (!integer(row.updated_at)) fail('bad_timestamp');
      if (typeof row.deleted !== 'boolean') fail('bad_deleted');
      if (table === 'titles') {
        if (!object(row.data)) fail('bad_data');
        row.data = JSON.stringify(row.data);
        if (new TextEncoder().encode(row.data).length > 16384) fail('too_large', 413);
      } else {
        if (![row.season, row.episode].every(v => integer(v) && v <= 9999)) fail('bad_episode');
        if (table === 'episodes' && typeof row.watched !== 'boolean') fail('bad_watched');
        if (table === 'positions' && ![row.position_ms, row.duration_ms].every(integer)) fail('bad_position');
      }
      row.client_updated_at = row.updated_at;
      row.updated_at = Math.min(row.updated_at, now + 300000);
    }
  }
  return body;
}

async function writeBatch(request, db) {
  const body = await readBatch(request);
  const statements = [];
  for (const [table, rows] of Object.entries(body)) {
    const fields = [...columns[table], 'updated_at', 'deleted', 'client_updated_at'];
    const keys = table === 'titles' ? ['key'] : ['title_key', 'season', 'episode'];
    for (const row of rows) {
      const values = fields.map(k => typeof row[k] === 'boolean' ? Number(row[k]) : row[k]);
      // Keep the original timestamp so retries of clamped future writes are no-ops.
      statements.push(db.prepare(`INSERT INTO ${table} (${fields},seq)
        VALUES (${fields.map(() => '?')},(SELECT v+1 FROM meta WHERE k='seq'))
        ON CONFLICT (${keys}) DO UPDATE SET
        ${fields.filter(k => !keys.includes(k)).map(k => `${k}=excluded.${k}`)},seq=excluded.seq
        WHERE excluded.updated_at > ${table}.updated_at
        AND excluded.client_updated_at IS NOT ${table}.client_updated_at`).bind(...values));
      statements.push(db.prepare("UPDATE meta SET v=v+changes() WHERE k='seq'"));
    }
  }
  statements.push(db.prepare("SELECT v FROM meta WHERE k='seq'"));
  const result = await db.batch(statements);
  return json({ seq: result.at(-1).results[0].v,
    applied: result.filter((_, i) => i < result.length - 1 && i % 2 === 0)
      .reduce((sum, r) => sum + r.meta.changes, 0) });
}

async function changes(url, db) {
  const parse = (name, fallback) => {
    const value = url.searchParams.get(name) ?? fallback;
    if (!/^\d+$/.test(value) || !integer(Number(value)) || url.searchParams.getAll(name).length > 1) fail('bad_query');
    return Number(value);
  };
  const since = parse('since', '0'), limit = parse('limit', '500');
  if (limit < 1 || limit > 1000) fail('bad_query');
  const query = Object.entries(columns).map(([table, fields]) =>
    `SELECT '${table}' AS kind, seq, json_object(${[...fields, 'updated_at', 'deleted', 'seq'].map(k => `'${k}',${k}`).join(',')}) AS payload FROM ${table} WHERE seq > ?`
  ).join(' UNION ALL ') + ' ORDER BY seq LIMIT ?';
  const { results } = await db.prepare(query).bind(since, since, since, limit + 1).all();
  const answer = { titles: [], episodes: [], positions: [], next: since, more: results.length > limit };
  for (const result of results.slice(0, limit)) {
    const row = JSON.parse(result.payload);
    row.deleted = Boolean(row.deleted);
    if (result.kind === 'titles') row.data = JSON.parse(row.data);
    if (result.kind === 'episodes') row.watched = Boolean(row.watched);
    answer[result.kind].push(row);
    answer.next = row.seq;
  }
  return json(answer);
}

export default {
  async fetch(request, env) {
    if (!env.LIST_KEY) return json({ error: 'not_configured' }, 500);
    if (!(await keyMatches(request.headers.get('X-VG-List-Key') || '', env.LIST_KEY))) {
      return json({ error: 'bad_key' }, 403);
    }
    const url = new URL(request.url);
    const methods = { '/v1/health': 'GET', '/v1/batch': 'POST', '/v1/changes': 'GET' };
    if (!Object.hasOwn(methods, url.pathname)) {
      return json({ error: 'not_found' }, 404);
    }
    if (request.method !== methods[url.pathname]) return json({ error: 'method_not_allowed' }, 405);
    try {
      if (url.pathname === '/v1/batch') return await writeBatch(request, env.DB);
      if (url.pathname === '/v1/changes') return await changes(url, env.DB);
    const row = await env.DB.prepare("SELECT v FROM meta WHERE k = 'seq'").first();
    return json({ ok: true, seq: row.v });
    } catch (error) {
      if (error instanceof InputError) return json({ error: error.message }, error.status);
      return json({ error: 'database_error' }, 500);
    }
  },
};
