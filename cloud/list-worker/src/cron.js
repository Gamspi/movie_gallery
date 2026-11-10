// Release notifications: TMDB/PoiskKino metadata refresh, event selection, Telegram delivery.

export const NOTIFY_CRON = '0 7 * * *';
export const BACKUP_CRON = '0 0 * * *';
export const MAX_ATTEMPTS = 3;
const DAY = 86400000;
const MSK_OFFSET = 3 * 3600000;
const RELEASE_WINDOW_DAYS = 3;
const ENDED_WINDOW_DAYS = 7;
const SERIES_TYPES = new Set(['tv_show', 'animated_series']);
const FINAL_STATUSES = new Set(['Ended', 'Canceled']);
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
const TMDB = 'https://api.themoviedb.org/3';
const POISKKINO = 'https://api.poiskkino.dev/v1.5/movie/';
const POSTER = 'https://image.tmdb.org/t/p/w500';

export class BudgetError extends Error { constructor() { super('budget'); } }
export class CronError extends Error {}

/** Counts every outgoing call (fetch and D1) against the per-invocation subrequest limit. */
export class Budget {
  constructor(limit) { this.left = limit; }
  take(n = 1) { if (this.left < n) throw new BudgetError(); this.left -= n; }
}

export const mskDate = now => new Date(now + MSK_OFFSET).toISOString().slice(0, 10);
export const addDays = (date, n) => new Date(Date.parse(`${date}T00:00:00Z`) + n * DAY).toISOString().slice(0, 10);
export function isoDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}/.test(value)) return null;
  const date = value.slice(0, 10);
  return new Date(`${date}T00:00:00Z`).toISOString().startsWith(date) ? date : null;
}
const isoSafe = v => { try { return isoDate(v); } catch { return null; } };
export function formatDate(date) {
  const [y, m, d] = date.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]} ${y}`;
}
const positive = v => { const n = Number(v); return Number.isSafeInteger(n) && n > 0 ? n : null; };

/** Parses a titles row; returns null for broken JSON. */
export function parseTitle(row) {
  let data = row.data;
  if (typeof data === 'string') { try { data = JSON.parse(data); } catch { return null; } }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  return { key: row.key, data, deleted: Boolean(row.deleted) };
}

/** 'tv' for series with notify on, 'movie' for unwatched/watching films, otherwise null. */
export function eligibleKind(title) {
  if (!title || title.deleted || title.key.startsWith('ai-')) return null;
  const { data } = title;
  if (data.status === 'dropped' || data.status === 'dismissed') return null;
  const series = SERIES_TYPES.has(data.type) || title.key.startsWith('tmdb-tv-');
  if (series) return data.notify === true || data.notify === 'true' ? 'tv' : null;
  return data.status === 'unwatched' || data.status === 'watching' ? 'movie' : null;
}

/** Catalog ids known without any request. */
export function identifiers(key, data, kind) {
  const direct = /^tmdb-(movie|tv)-(\d+)$/.exec(key);
  let tmdb = direct ? { type: direct[1], id: Number(direct[2]) } : null;
  if (!tmdb && data.catalog_source === 'Tmdb' && positive(data.catalog_id)) tmdb = { type: kind, id: positive(data.catalog_id) };
  const imdb = /^tt\d{5,10}$/.test(key) ? key : (/^tt\d{5,10}$/.test(data.imdb_id ?? '') ? data.imdb_id : null);
  const kpKey = /^kp-(\d+)$/.exec(key);
  const kp = kpKey ? Number(kpKey[1])
    : ['Kinopoisk', 'PoiskKino'].includes(data.catalog_source) ? positive(data.catalog_id) : null;
  return { tmdb, imdb, kp };
}

/** Normalizes TMDB /tv details; status transitions and the season baseline come from the previous info. */
export function tvInfo(json, prev, today) {
  const seasons = (Array.isArray(json.seasons) ? json.seasons : [])
    .filter(s => Number.isInteger(s?.season_number) && s.season_number > 0)
    .map(s => ({ n: s.season_number, air: isoSafe(s.air_date) }));
  const episode = e => e && Number.isInteger(e.season_number) && Number.isInteger(e.episode_number)
    ? { s: e.season_number, e: e.episode_number, name: typeof e.name === 'string' ? e.name : '', air: isoSafe(e.air_date) } : null;
  const status = typeof json.status === 'string' ? json.status : '';
  const final = FINAL_STATUSES.has(status);
  const prevStatus = prev?.kind === 'tv' ? prev.status : '';
  // First sight of an old finished show is not news: only a seen transition counts.
  const finalOn = !final ? null : prevStatus && !FINAL_STATUSES.has(prevStatus) ? today : prev?.final_on ?? null;
  const baseline = prev?.kind === 'tv' && Number.isInteger(prev.baseline) ? prev.baseline
    : Math.max(0, ...seasons.filter(s => s.air).map(s => s.n));
  return { kind: 'tv', source: 'tmdb', name: json.name || '', poster: json.poster_path || prev?.poster || null,
    status, final_on: finalOn, baseline, seasons, last: episode(json.last_episode_to_air), next: episode(json.next_episode_to_air) };
}

export function movieInfo(json) {
  const release = isoSafe(json.release_date);
  return { kind: 'movie', source: 'tmdb', name: json.title || '', poster: json.poster_path || null,
    release, year: release ? Number(release.slice(0, 4)) : null };
}

/** PoiskKino /v1.5/movie/{id}: TMDB id when present, plus a world premiere fallback for films. */
export function poiskkinoInfo(json) {
  const tmdb = positive(json?.externalId?.tmdb);
  const release = isoSafe(json?.premiere?.world);
  const poster = typeof json?.poster?.url === 'string' && json.poster.url.startsWith('https://') ? json.poster.url : null;
  return { tmdb, imdb: /^tt\d{5,10}$/.test(json?.externalId?.imdb ?? '') ? json.externalId.imdb : null,
    info: { kind: 'movie', source: 'poiskkino', name: json?.name || '', poster_url: poster, release,
      year: positive(json?.year) ?? (release ? Number(release.slice(0, 4)) : null) } };
}

const genericName = name => !name || /^(episode|эпизод|серия)\s*\d+$/i.test(name.trim());
const epCode = ep => `S${ep.s}E${ep.e}`;
const epLabel = ep => epCode(ep) + (genericName(ep.name) ? '' : ` «${ep.name}»`);

export function titleName(item) {
  const { data, info } = item;
  return data.title_ru || data.title || data.name || info?.name || item.key;
}

export function photoUrl(item) {
  const { info, data } = item;
  if (info?.poster && /^\/[\w.-]+$/.test(info.poster)) return POSTER + info.poster;
  if (info?.poster_url) return info.poster_url;
  return typeof data.poster_url === 'string' && data.poster_url.startsWith('https://') ? data.poster_url : null;
}

export function caption(event) {
  const { type, title } = event;
  if (type === 'episode') return `📺 Вышла серия ${epLabel(event.ep)} — ${title}`;
  if (type === 'remind_episode') return `🗓 Завтра: ${epLabel(event.ep)} — ${title}`;
  const year = event.year ? ` (${event.year})` : '';
  if (type === 'movie') return `🎬 Вышел фильм «${title}»${year}`;
  if (type === 'remind_movie') return `🗓 Завтра премьера: «${title}»${year}`;
  if (type === 'season') return `📢 Объявлен ${event.season} сезон «${title}» — премьера ${formatDate(event.date)}`;
  if (type === 'ended') return `🏁 Сериал «${title}» ${event.status === 'Canceled' ? 'закрыт' : 'завершён'}`;
  throw new Error('unknown_event');
}

/** Pure event selection; items are { key, kind, data, info } and today is a Moscow calendar date. */
export function buildEvents(items, today) {
  const tomorrow = addDays(today, 1), recent = addDays(today, -RELEASE_WINDOW_DAYS);
  const events = [];
  for (const item of items) {
    const base = { titleKey: item.key, title: titleName(item), photo: photoUrl(item) };
    const push = (key, extra) => { const e = { ...base, key, ...extra }; e.caption = caption(e); events.push(e); };
    const { info } = item;
    if (item.kind === 'tv') {
      if (info?.kind !== 'tv') continue;
      const seen = new Set();
      for (const ep of [info.last, info.next]) {
        if (!ep?.air || seen.has(epCode(ep))) continue;
        seen.add(epCode(ep));
        if (ep.air >= recent && ep.air <= today) push(`ep:${item.key}:${epCode(ep)}`, { type: 'episode', ep, date: ep.air });
        if (ep.air === tomorrow) push(`remind:${item.key}:${epCode(ep)}:${ep.air}`, { type: 'remind_episode', ep, date: ep.air });
      }
      for (const season of info.seasons ?? []) {
        if (season.n > (info.baseline ?? 0) && season.air && season.air >= today) {
          push(`season:${item.key}:${season.n}`, { type: 'season', season: season.n, date: season.air });
        }
      }
      if (info.final_on && info.final_on >= addDays(today, -ENDED_WINDOW_DAYS) && info.final_on <= today) {
        push(`ended:${item.key}`, { type: 'ended', status: info.status });
      }
    } else if (item.kind === 'movie') {
      const release = (info?.kind === 'movie' ? info.release : null) ?? isoSafe(item.data.premiere_date);
      if (!release) continue;
      const year = positive(item.data.year) ?? Number(release.slice(0, 4));
      if (release >= recent && release <= today) push(`movie:${item.key}`, { type: 'movie', year, date: release });
      if (release === tomorrow) push(`remind:${item.key}:movie:${release}`, { type: 'remind_movie', year, date: release });
    }
  }
  return events;
}

/** Milliseconds between refreshes: settled titles are checked weekly. */
export function refreshInterval(item, today) {
  const info = item.cache?.info;
  if (item.cache?.mapped_at && !item.cache.tmdb_id && !info) return 7 * DAY;
  if (info?.source === 'poiskkino') return 7 * DAY;
  if (info?.kind === 'movie' && info.release && info.release < addDays(today, -30)) return 7 * DAY;
  if (info?.kind === 'tv' && FINAL_STATUSES.has(info.status) && !info.next) return 7 * DAY;
  return 20 * 3600000;
}

/** Estimated outgoing requests to refresh one title. */
export function refreshCost(item) {
  const ids = identifiers(item.key, item.data, item.kind);
  if (ids.tmdb || item.cache?.tmdb_id) return 1;
  return ids.imdb && item.kind === 'movie' ? 1 : 2;
}

/** Oldest-first portion of due titles that fits the request budget (cursor = checked_at). */
export function pickRefresh(items, now, today, max, { poiskkino = false } = {}) {
  const due = items.filter(item => {
    const ids = identifiers(item.key, item.data, item.kind);
    if (!ids.tmdb && !item.cache?.tmdb_id && !ids.imdb && !(ids.kp && poiskkino)) return false;
    const checked = item.cache?.checked_at ?? 0;
    return now - checked >= refreshInterval(item, today);
  }).sort((a, b) => (a.cache?.checked_at ?? 0) - (b.cache?.checked_at ?? 0) || a.key.localeCompare(b.key));
  const picked = [];
  let cost = 0;
  for (const item of due) {
    const c = refreshCost(item);
    if (cost + c > max) break;
    picked.push(item); cost += c;
  }
  return picked;
}

async function getJson(ctx, url, init = {}) {
  ctx.budget.take();
  let response;
  try { response = await ctx.fetch(url, { ...init, signal: AbortSignal.timeout(15000) }); }
  catch (error) { if (error instanceof BudgetError) throw error; return { status: 0, body: null }; }
  let body = null;
  try { body = await response.json(); } catch { /* non-JSON answer */ }
  return { status: response.status, body };
}

/** v3 keys are 32 hex chars and go to api_key; anything else is a v4 bearer token. */
export function tmdbRequest(path, key, params = {}) {
  const url = new URL(TMDB + path);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const headers = { accept: 'application/json' };
  if (/^[0-9a-f]{32}$/i.test(key)) url.searchParams.set('api_key', key);
  else headers.authorization = `Bearer ${key}`;
  return { url: url.toString(), init: { headers } };
}

async function tmdb(ctx, path, params) {
  const { url, init } = tmdbRequest(path, ctx.env.TMDB_API_KEY, { language: 'ru-RU', ...params });
  const result = await getJson(ctx, url, init);
  if (result.status === 401) throw new CronError('tmdb_unauthorized');
  return result;
}

function poiskkinoAllowed(ctx) {
  if (!ctx.env.POISKKINO_API_KEY || ctx.dry) return false;
  return ctx.quota.count < ctx.quota.limit;
}

/** Refreshes one title; returns the new cache row (never throws on per-title failures). */
export async function refreshItem(item, ctx) {
  const prev = item.cache ?? {};
  const row = { key: item.key, tmdb_type: prev.tmdb_type ?? null, tmdb_id: prev.tmdb_id ?? null,
    mapped_at: prev.mapped_at ?? null, checked_at: ctx.now, info: prev.info ?? null };
  const ids = identifiers(item.key, item.data, item.kind);
  if (ids.tmdb) Object.assign(row, { tmdb_type: ids.tmdb.type, tmdb_id: ids.tmdb.id, mapped_at: row.mapped_at ?? ctx.now });
  if (!row.tmdb_id && ids.imdb) {
    const found = await tmdb(ctx, `/find/${ids.imdb}`, { external_source: 'imdb_id' });
    if (found.status !== 200 || !found.body) return row;
    const pick = (type, list) => Array.isArray(list) && list[0] && positive(list[0].id) ? { type, hit: list[0] } : null;
    const tv = pick('tv', found.body.tv_results), movie = pick('movie', found.body.movie_results);
    const match = item.kind === 'tv' ? tv ?? movie : movie ?? tv;
    row.mapped_at = ctx.now;
    if (match) {
      Object.assign(row, { tmdb_type: match.type, tmdb_id: match.hit.id });
      // /find already carries the film date; series need details for episodes and status.
      if (match.type === 'movie') { row.info = movieInfo(match.hit); return row; }
    }
  }
  if (!row.tmdb_id && ids.kp && poiskkinoAllowed(ctx)) {
    ctx.quota.count++;
    const res = await getJson(ctx, POISKKINO + ids.kp, { headers: { 'X-API-KEY': ctx.env.POISKKINO_API_KEY, accept: 'application/json' } });
    if (res.status !== 200 || !res.body) return row;
    const parsed = poiskkinoInfo(res.body);
    row.mapped_at = ctx.now;
    if (parsed.tmdb) Object.assign(row, { tmdb_type: item.kind, tmdb_id: parsed.tmdb });
    else { if (item.kind === 'movie') row.info = parsed.info; return row; }
  }
  if (!row.tmdb_id) { row.mapped_at = row.mapped_at ?? ctx.now; return row; }
  const details = await tmdb(ctx, `/${row.tmdb_type}/${row.tmdb_id}`);
  if (details.status === 404) { Object.assign(row, { tmdb_type: null, tmdb_id: null, mapped_at: null }); return row; }
  if (details.status !== 200 || !details.body) return row;
  row.info = row.tmdb_type === 'tv' ? tvInfo(details.body, prev.info, ctx.today) : movieInfo(details.body);
  return row;
}

/** Runs refreshItem over a picked portion until the budget ends; returns updated cache rows. */
export async function refreshAll(items, ctx, max) {
  const picked = pickRefresh(items, ctx.now, ctx.today, max, { poiskkino: poiskkinoAllowed(ctx) });
  const rows = [];
  for (const item of picked) {
    try { rows.push(await refreshItem(item, ctx)); }
    catch (error) { if (error instanceof BudgetError) break; throw error; }
  }
  return rows;
}

/** Sends one message; photo failures fall back to plain text. */
export async function sendTelegram(event, ctx) {
  const api = `https://api.telegram.org/bot${ctx.env.TG_BOT_TOKEN}/`;
  const post = async (method, body) => {
    const res = await getJson(ctx, api + method, { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify({ chat_id: ctx.env.TG_CHAT_ID, ...body }) });
    return res.status === 200 && res.body?.ok === true;
  };
  if (event.photo && await post('sendPhoto', { photo: event.photo, caption: event.caption })) return { ok: true, via: 'photo' };
  if (await post('sendMessage', { text: event.caption })) return { ok: true, via: 'text' };
  return { ok: false };
}

function cacheRow(row) {
  let info = null;
  try { info = row.info ? JSON.parse(row.info) : null; } catch { /* rebuilt on next refresh */ }
  return { tmdb_type: row.tmdb_type, tmdb_id: row.tmdb_id, mapped_at: row.mapped_at, checked_at: row.checked_at, info };
}

/** One D1 call: list items with their cache, delivery log and PoiskKino quota. */
export async function loadState(ctx) {
  ctx.budget.take();
  const [titles, cache, sent, state] = await ctx.db.batch([
    ctx.db.prepare('SELECT key, data, deleted FROM titles WHERE deleted = 0 ORDER BY key'),
    ctx.db.prepare('SELECT key, tmdb_type, tmdb_id, mapped_at, checked_at, info FROM cron_titles'),
    ctx.db.prepare('SELECT event_key, status, attempts FROM sent_events'),
    ctx.db.prepare("SELECT v FROM cron_state WHERE k = 'poiskkino'"),
  ]);
  const caches = new Map(cache.results.map(r => [r.key, cacheRow(r)]));
  const items = [];
  for (const row of titles.results) {
    const title = parseTitle(row);
    const kind = eligibleKind(title);
    if (kind) items.push({ key: title.key, kind, data: title.data, cache: caches.get(title.key) ?? null });
  }
  let quota = { date: ctx.today, count: 0 };
  try { const v = JSON.parse(state.results[0]?.v ?? 'null'); if (v?.date === ctx.today) quota = { date: v.date, count: v.count | 0 }; }
  catch { /* reset */ }
  quota.limit = positive(ctx.env.POISKKINO_DAILY) ?? 20;
  return { items, sent: new Map(sent.results.map(r => [r.event_key, r])), quota };
}

async function saveRefresh(ctx, rows) {
  if (ctx.dry || !rows.length) return;
  ctx.budget.take();
  await ctx.db.batch([
    ...rows.map(r => ctx.db.prepare(`INSERT INTO cron_titles (key, tmdb_type, tmdb_id, mapped_at, checked_at, info)
      VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (key) DO UPDATE SET tmdb_type = excluded.tmdb_type, tmdb_id = excluded.tmdb_id,
      mapped_at = excluded.mapped_at, checked_at = excluded.checked_at, info = excluded.info`)
      .bind(r.key, r.tmdb_type, r.tmdb_id, r.mapped_at, r.checked_at, r.info ? JSON.stringify(r.info) : null)),
    ctx.db.prepare("INSERT INTO cron_state (k, v) VALUES ('poiskkino', ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v")
      .bind(JSON.stringify({ date: ctx.quota.date, count: ctx.quota.count })),
  ]);
}

/** Delivery state of an event: new, retry or a reason to skip it. */
export function deliveryState(row) {
  if (!row) return 'new';
  if (row.status === 'failed') return row.attempts < MAX_ATTEMPTS ? 'retry' : 'gave_up';
  return row.status; // 'sent' or 'pending' (interrupted run: treated as possibly sent)
}

export function makeContext(env, deps = {}) {
  const now = deps.now ?? Date.now();
  const limit = positive(env.CRON_SUBREQUESTS) ?? 50;
  return { env, db: env.DB, fetch: deps.fetch ?? fetch.bind(globalThis), now, today: deps.today ?? mskDate(now),
    budget: new Budget(Math.max(5, limit - 2)), dry: Boolean(deps.dry), quota: { date: '', count: 0, limit: 0 } };
}

const SEND_RESERVE = 15;

/** Refresh-only job (night run): spends what the budget has left on metadata. */
export async function runRefresh(env, deps = {}, ctx = makeContext(env, deps)) {
  if (!env.TMDB_API_KEY) return { job: 'refresh', skipped: 'not_configured' };
  const state = await loadState(ctx);
  ctx.quota = state.quota;
  const rows = await refreshAll(state.items, ctx, ctx.budget.left - 1);
  await saveRefresh(ctx, rows);
  return { job: 'refresh', today: ctx.today, titles: state.items.length, refreshed: rows.length, dry: ctx.dry };
}

/** Daily job: refresh a portion, select events, send new ones exactly once. */
export async function runNotify(env, deps = {}) {
  const ctx = makeContext(env, deps);
  const state = await loadState(ctx);
  ctx.quota = state.quota;
  let rows = [], refreshError;
  if (env.TMDB_API_KEY) {
    // A broken catalog key must not block events already known from cached metadata.
    try { rows = await refreshAll(state.items, ctx, Math.max(0, ctx.budget.left - SEND_RESERVE - 1)); }
    catch (error) { if (!(error instanceof CronError)) throw error; refreshError = error.message; }
    await saveRefresh(ctx, rows);
  }
  const fresh = new Map(rows.map(r => [r.key, r]));
  const items = state.items.map(item => ({ ...item, info: (fresh.get(item.key) ?? item.cache)?.info ?? null }));
  const events = buildEvents(items, ctx.today).map(e => ({ ...e, state: deliveryState(state.sent.get(e.key)) }));
  const report = { job: 'notify', today: ctx.today, dry: ctx.dry, titles: items.length, refreshed: rows.length,
    events: events.map(({ key, type, caption: text, photo, state: s }) => ({ key, type, caption: text, photo, state: s })),
    sent: 0, failed: 0, deferred: 0 };
  if (refreshError) report.refresh_error = refreshError;
  const due = events.filter(e => e.state === 'new' || e.state === 'retry');
  if (ctx.dry || !due.length) return report;
  if (!env.TG_BOT_TOKEN || !env.TG_CHAT_ID) return { ...report, skipped: 'telegram_not_configured' };
  // Claim: photo + text fallback + status write per event, plus one claim batch.
  const capacity = Math.max(0, Math.floor((ctx.budget.left - 1) / 3));
  const batch = due.slice(0, capacity);
  report.deferred = due.length - batch.length;
  if (!batch.length) return report;
  ctx.budget.take();
  const claims = await ctx.db.batch(batch.map(e => ctx.db.prepare(`INSERT INTO sent_events
    (event_key, status, attempts, created_at, updated_at) VALUES (?, 'pending', 1, ?, ?)
    ON CONFLICT (event_key) DO UPDATE SET status = 'pending', attempts = sent_events.attempts + 1, updated_at = excluded.updated_at
    WHERE sent_events.status = 'failed' AND sent_events.attempts < ${MAX_ATTEMPTS}`).bind(e.key, ctx.now, ctx.now)));
  for (const [i, event] of batch.entries()) {
    if (claims[i].meta.changes !== 1) continue; // another run owns it
    const result = await sendTelegram(event, ctx);
    const status = result.ok ? 'sent' : 'failed';
    report[result.ok ? 'sent' : 'failed']++;
    const shown = report.events.find(e => e.key === event.key);
    shown.state = status; if (result.via) shown.via = result.via;
    ctx.budget.take(); // reserved by capacity above
    await ctx.db.prepare('UPDATE sent_events SET status = ?, updated_at = ? WHERE event_key = ?').bind(status, Date.now(), event.key).run();
  }
  return report;
}
