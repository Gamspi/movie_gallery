// Nightly backup of the list into a Google Sheet via a service account (JWT RS256, WebCrypto).
import { Budget } from './cron.js';

const SHEETS = 'https://sheets.googleapis.com/v4/spreadsheets/';
const SCOPE = 'https://www.googleapis.com/auth/spreadsheets';
export const TITLES_SHEET = 'Тайтлы';
export const EPISODES_SHEET = 'Серии';
const TITLE_HEADER = ['Ключ', 'Название', 'Тип', 'Статус', 'Оценка', 'Оценки сезонов', 'Уведомлять', 'Год',
  'Премьера', 'IMDb', 'Каталог', 'ID в каталоге', 'Изменено (МСК)', 'Данные (JSON)'];
const EPISODE_HEADER = ['Ключ тайтла', 'Название', 'Сезон', 'Серия', 'Просмотрена', 'Изменено (МСК)'];

const b64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64urlText = text => b64url(new TextEncoder().encode(text));

export function pemToPkcs8(pem) {
  const body = String(pem).replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  const raw = atob(body);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

export function parseServiceAccount(text) {
  let sa;
  try { sa = JSON.parse(text); } catch { throw new Error('google_sa_invalid'); }
  if (typeof sa?.client_email !== 'string' || typeof sa?.private_key !== 'string') throw new Error('google_sa_invalid');
  return sa;
}

/** Signed assertion for the OAuth 2.0 JWT bearer grant. */
export async function signJwt(sa, now) {
  const iat = Math.floor(now / 1000);
  const header = b64urlText(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64urlText(JSON.stringify({ iss: sa.client_email, scope: SCOPE,
    aud: sa.token_uri || 'https://oauth2.googleapis.com/token', iat, exp: iat + 3600 }));
  const key = await crypto.subtle.importKey('pkcs8', pemToPkcs8(sa.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`));
  return `${header}.${claims}.${b64url(signature)}`;
}

const mskTime = ms => Number.isFinite(ms) ? new Date(ms + 3 * 3600000).toISOString().slice(0, 16).replace('T', ' ') : '';
const cell = v => v === undefined || v === null ? '' : typeof v === 'object' ? JSON.stringify(v) : v;
const yes = v => v === true || v === 'true' ? 'да' : 'нет';

/** Sheet rows from non-deleted D1 rows; titles sorted by key, episodes by key/season/episode. */
export function backupRows(titleRows, episodeRows) {
  const names = new Map();
  const titles = [TITLE_HEADER];
  for (const row of [...titleRows].sort((a, b) => a.key.localeCompare(b.key))) {
    let data = {};
    try { data = JSON.parse(row.data); } catch { /* kept as raw text below */ }
    const name = data.title_ru || data.title || data.name || '';
    names.set(row.key, name);
    titles.push([row.key, name, cell(data.type), cell(data.status), cell(data.my_rating), cell(data.season_ratings),
      data.type === 'tv_show' || data.type === 'animated_series' ? yes(data.notify) : '', cell(data.year),
      cell(data.premiere_date), cell(data.imdb_id), cell(data.catalog_source), cell(data.catalog_id),
      mskTime(row.updated_at), typeof row.data === 'string' ? row.data : JSON.stringify(row.data)]);
  }
  const episodes = [EPISODE_HEADER];
  for (const row of [...episodeRows].sort((a, b) => a.title_key.localeCompare(b.title_key) || a.season - b.season || a.episode - b.episode)) {
    episodes.push([row.title_key, names.get(row.title_key) ?? '', row.season, row.episode, row.watched ? 'да' : 'нет', mskTime(row.updated_at)]);
  }
  return { titles, episodes };
}

const column = n => { let s = ''; for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + (n - 1) % 26) + s; return s; };
const quoted = name => `'${name}'`;

async function call(ctx, url, init) {
  ctx.budget.take();
  let response;
  try { response = await ctx.fetch(url, { ...init, signal: AbortSignal.timeout(20000) }); }
  catch { throw new Error('google_network'); }
  let body = null;
  try { body = await response.json(); } catch { /* empty */ }
  return { status: response.status, body };
}

/** Full rewrite: write new rows first, then clear what is left below/right, so the sheet is never empty. */
export async function runBackup(env, deps = {}) {
  const now = deps.now ?? Date.now();
  const ctx = { fetch: deps.fetch ?? fetch.bind(globalThis), budget: deps.budget ?? new Budget(45) };
  ctx.budget.take();
  const [titles, episodes] = await env.DB.batch([
    env.DB.prepare('SELECT key, data, updated_at FROM titles WHERE deleted = 0'),
    env.DB.prepare('SELECT title_key, season, episode, watched, updated_at FROM episodes WHERE deleted = 0'),
  ]);
  const rows = backupRows(titles.results, episodes.results);
  const report = { job: 'backup', titles: rows.titles.length - 1, episodes: rows.episodes.length - 1, dry: Boolean(deps.dry) };
  if (deps.dry) return report;
  if (!env.GOOGLE_SA_JSON || !env.GOOGLE_SHEET_ID) return { ...report, skipped: 'not_configured' };
  if (!/^[A-Za-z0-9_-]{10,200}$/.test(env.GOOGLE_SHEET_ID)) throw new Error('google_sheet_invalid');
  const sa = parseServiceAccount(env.GOOGLE_SA_JSON);
  const assertion = await signJwt(sa, now);
  const token = await call(ctx, sa.token_uri || 'https://oauth2.googleapis.com/token', { method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString() });
  if (token.status !== 200 || typeof token.body?.access_token !== 'string') throw new Error('google_auth_failed');
  const headers = { authorization: `Bearer ${token.body.access_token}`, 'content-type': 'application/json' };
  const base = SHEETS + env.GOOGLE_SHEET_ID;
  const meta = await call(ctx, `${base}?fields=sheets.properties.title`, { headers });
  if (meta.status !== 200) throw new Error('google_sheet_unavailable');
  const existing = new Set((meta.body?.sheets ?? []).map(s => s?.properties?.title));
  const missing = [TITLES_SHEET, EPISODES_SHEET].filter(t => !existing.has(t));
  if (missing.length) {
    const added = await call(ctx, `${base}:batchUpdate`, { method: 'POST', headers,
      body: JSON.stringify({ requests: missing.map(title => ({ addSheet: { properties: { title } } })) }) });
    if (added.status !== 200) throw new Error('google_add_sheet_failed');
  }
  const sheets = [[TITLES_SHEET, rows.titles], [EPISODES_SHEET, rows.episodes]];
  const written = await call(ctx, `${base}/values:batchUpdate`, { method: 'POST', headers,
    body: JSON.stringify({ valueInputOption: 'RAW', data: sheets.map(([name, values]) => ({ range: `${quoted(name)}!A1`, values })) }) });
  if (written.status !== 200) throw new Error('google_write_failed');
  const ranges = sheets.flatMap(([name, values]) => [
    `${quoted(name)}!A${values.length + 1}:ZZ`, `${quoted(name)}!${column(values[0].length)}1:ZZ`]);
  const cleared = await call(ctx, `${base}/values:batchClear`, { method: 'POST', headers, body: JSON.stringify({ ranges }) });
  if (cleared.status !== 200) throw new Error('google_clear_failed');
  return { ...report, created: missing };
}
