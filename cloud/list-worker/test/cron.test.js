import { env } from 'cloudflare:test';
import { beforeAll, beforeEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.js';
import schema from '../schema.sql?raw';
import {
  Budget, BudgetError, addDays, buildEvents, caption, deliveryState, eligibleKind, identifiers, makeContext, mskDate,
  pickRefresh, poiskkinoInfo, refreshItem, runNotify, runRefresh, sendTelegram, tmdbRequest, tvInfo,
} from '../src/cron.js';

beforeAll(async () => {
  await env.DB.batch(schema.split(';').map(s => s.trim()).filter(Boolean).map(s => env.DB.prepare(s)));
});
beforeEach(async () => {
  vi.restoreAllMocks();
  await env.DB.batch(['titles', 'episodes', 'positions', 'cron_titles', 'sent_events', 'cron_state']
    .map(t => env.DB.prepare(`DELETE FROM ${t}`)));
});

const TODAY = '2026-11-10';
const NOW = Date.parse('2026-11-10T07:00:00Z');
const V3 = '0123456789abcdef0123456789abcdef';
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/** Routes mocked requests by URL fragment; records every call. */
function mockFetch(routes) {
  const calls = [];
  const fn = vi.fn(async (url, init = {}) => {
    calls.push({ url: String(url), init, body: init.body ? JSON.parse(init.body) : null });
    for (const [part, answer] of routes) {
      if (String(url).includes(part)) return typeof answer === 'function' ? answer(String(url), init) : json(answer);
    }
    return json({ error: 'unexpected' }, 500);
  });
  fn.calls = calls;
  return fn;
}

const tvJson = (over = {}) => ({
  name: 'Сериал TMDB', status: 'Returning Series', poster_path: '/tv.jpg',
  seasons: [{ season_number: 0, air_date: '2020-01-01' }, { season_number: 1, air_date: '2025-01-01' }, { season_number: 2, air_date: '2026-10-01' }],
  last_episode_to_air: { season_number: 2, episode_number: 5, name: 'Пятая', air_date: TODAY },
  next_episode_to_air: { season_number: 2, episode_number: 6, name: 'Эпизод 6', air_date: addDays(TODAY, 1) },
  ...over,
});
const tv = (key, data = {}, info = null) => ({ key, kind: 'tv', data: { title_ru: 'Сериал', type: 'tv_show', notify: true, status: 'watching', ...data }, info });
const movie = (key, data = {}, info = null) => ({ key, kind: 'movie', data: { title_ru: 'Фильм', type: 'movie', status: 'unwatched', ...data }, info });

it('computes the Moscow calendar date and day offsets', () => {
  expect(mskDate(Date.parse('2026-11-10T20:59:00Z'))).toBe('2026-11-10');
  expect(mskDate(Date.parse('2026-11-10T21:00:00Z'))).toBe('2026-11-11');
  expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
});

it.each([
  [{ key: 'tt0000001', data: { type: 'tv_show', notify: true, status: 'watched' } }, 'tv'],
  [{ key: 'tt0000001', data: { type: 'animated_series', notify: 'true', status: 'unwatched' } }, 'tv'],
  [{ key: 'tt0000001', data: { type: 'tv_show', notify: false, status: 'watching' } }, null],
  [{ key: 'tt0000001', data: { type: 'tv_show', notify: true, status: 'dropped' } }, null],
  [{ key: 'tt0000001', data: { type: 'movie', status: 'dismissed' } }, null],
  [{ key: 'tt0000001', data: { type: 'movie', status: 'unwatched' } }, 'movie'],
  [{ key: 'tt0000001', data: { type: 'movie', status: 'watching' } }, 'movie'],
  [{ key: 'tt0000001', data: { type: 'movie', status: 'watched' } }, null],
  [{ key: 'tt0000001', deleted: true, data: { type: 'movie', status: 'unwatched' } }, null],
  [{ key: 'ai-some-title', data: { type: 'movie', status: 'unwatched' } }, null],
  [{ key: 'tmdb-tv-5', data: { notify: true, status: 'watching' } }, 'tv'],
])('filters by status, notify and key %#', (title, kind) => {
  expect(eligibleKind({ deleted: false, ...title })).toBe(kind);
});

it('derives catalog ids from keys and data', () => {
  expect(identifiers('tmdb-tv-42', {}, 'tv')).toEqual({ tmdb: { type: 'tv', id: 42 }, imdb: null, kp: null });
  expect(identifiers('tt1234567', { catalog_source: 'Tmdb', catalog_id: 7 }, 'movie').tmdb).toEqual({ type: 'movie', id: 7 });
  expect(identifiers('kp-77', { imdb_id: 'tt7654321' }, 'movie')).toEqual({ tmdb: null, imdb: 'tt7654321', kp: 77 });
  expect(identifiers('tt1234567', { catalog_source: 'PoiskKino', catalog_id: 9 }, 'movie').kp).toBe(9);
});

it('builds Russian captions', () => {
  const ep = { s: 2, e: 5, name: 'Название' };
  expect(caption({ type: 'episode', title: 'Сериал', ep })).toBe('📺 Вышла серия S2E5 «Название» — Сериал');
  expect(caption({ type: 'episode', title: 'Сериал', ep: { s: 2, e: 5, name: 'Episode 5' } })).toBe('📺 Вышла серия S2E5 — Сериал');
  expect(caption({ type: 'remind_episode', title: 'Сериал', ep: { s: 3, e: 1, name: '' } })).toBe('🗓 Завтра: S3E1 — Сериал');
  expect(caption({ type: 'movie', title: 'X', year: 2026 })).toBe('🎬 Вышел фильм «X» (2026)');
  expect(caption({ type: 'remind_movie', title: 'X', year: 2026 })).toBe('🗓 Завтра премьера: «X» (2026)');
  expect(caption({ type: 'season', title: 'X', season: 4, date: '2027-03-12' })).toBe('📢 Объявлен 4 сезон «X» — премьера 12 мар 2027');
  expect(caption({ type: 'ended', title: 'X', status: 'Ended' })).toBe('🏁 Сериал «X» завершён');
  expect(caption({ type: 'ended', title: 'X', status: 'Canceled' })).toBe('🏁 Сериал «X» закрыт');
});

it('normalizes TMDB series and only reports seen transitions to Ended', () => {
  const first = tvInfo(tvJson({ status: 'Ended' }), null, TODAY);
  expect(first).toMatchObject({ status: 'Ended', final_on: null, baseline: 2, poster: '/tv.jpg' });
  expect(first.seasons).toEqual([{ n: 1, air: '2025-01-01' }, { n: 2, air: '2026-10-01' }]);
  const running = tvInfo(tvJson(), null, '2026-11-01');
  const ended = tvInfo(tvJson({ status: 'Canceled' }), running, TODAY);
  expect(ended.final_on).toBe(TODAY);
  expect(tvInfo(tvJson({ status: 'Canceled' }), ended, addDays(TODAY, 1)).final_on).toBe(TODAY);
  expect(tvInfo(tvJson({ seasons: 'bad', last_episode_to_air: { season_number: 'x' } }), running, TODAY))
    .toMatchObject({ seasons: [], last: null, baseline: 2 });
});

it('selects episode, reminder, season, ended and movie events', () => {
  const info = { ...tvInfo(tvJson(), null, '2026-11-01'), baseline: 3, final_on: TODAY, status: 'Ended',
    seasons: [{ n: 3, air: '2026-01-01' }, { n: 4, air: '2027-03-12' }, { n: 5, air: null }] };
  const events = buildEvents([
    tv('tt0000001', {}, info),
    movie('tt0000002', { year: 2026 }, { kind: 'movie', release: TODAY, poster: '/m.jpg' }),
    movie('kp-3', { premiere_date: addDays(TODAY, 1) }),
    movie('tt0000004', {}, { kind: 'movie', release: '2026-01-01' }),
    movie('tt0000005', {}, { kind: 'movie', release: addDays(TODAY, -4) }),
  ], TODAY);
  expect(events.map(e => e.key)).toEqual([
    'ep:tt0000001:S2E5', 'remind:tt0000001:S2E6:2026-11-11', 'season:tt0000001:4', 'ended:tt0000001',
    'movie:tt0000002', 'remind:kp-3:movie:2026-11-11',
  ]);
  expect(events[0]).toMatchObject({ caption: '📺 Вышла серия S2E5 «Пятая» — Сериал', photo: 'https://image.tmdb.org/t/p/w500/tv.jpg' });
  expect(events[1].caption).toBe('🗓 Завтра: S2E6 — Сериал');
  expect(events[2].caption).toBe('📢 Объявлен 4 сезон «Сериал» — премьера 12 мар 2027');
  expect(events[4].caption).toBe('🎬 Вышел фильм «Фильм» (2026)');
  expect(events[5]).toMatchObject({ caption: '🗓 Завтра премьера: «Фильм» (2026)', photo: null });
});

it('keeps released episodes for a three-day window so a missed run still reports them', () => {
  const info = tvInfo(tvJson({ last_episode_to_air: { season_number: 1, episode_number: 2, air_date: addDays(TODAY, -3) }, next_episode_to_air: null }), null, TODAY);
  expect(buildEvents([tv('tt0000001', {}, info)], TODAY).map(e => e.key)).toEqual(['ep:tt0000001:S1E2']);
  expect(buildEvents([tv('tt0000001', {}, info)], addDays(TODAY, 1))).toEqual([]);
});

it('parses PoiskKino details', () => {
  expect(poiskkinoInfo({ name: 'Фильм', year: 2027, externalId: { tmdb: 55, imdb: 'tt1234567' },
    premiere: { world: '2027-02-03T00:00:00.000Z' }, poster: { url: 'https://img/p.jpg' } })).toEqual({
    tmdb: 55, imdb: 'tt1234567',
    info: { kind: 'movie', source: 'poiskkino', name: 'Фильм', poster_url: 'https://img/p.jpg', release: '2027-02-03', year: 2027 } });
  expect(poiskkinoInfo({ premiere: { world: 'garbage' } }).info.release).toBe(null);
});

it('supports TMDB v3 keys and v4 bearer tokens', () => {
  const v3 = tmdbRequest('/tv/1', V3, { language: 'ru-RU' });
  expect(new URL(v3.url).searchParams.get('api_key')).toBe(V3);
  expect(v3.init.headers.authorization).toBeUndefined();
  const v4 = tmdbRequest('/tv/1', 'eyJhbGciOiJIUzI1NiJ9.e30.sig');
  expect(new URL(v4.url).searchParams.has('api_key')).toBe(false);
  expect(v4.init.headers.authorization).toBe('Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig');
});

const ctxWith = (fetchFn, extra = {}) => ({ ...makeContext({ TMDB_API_KEY: V3, ...extra }, { fetch: fetchFn, now: NOW, today: TODAY }),
  quota: { date: TODAY, count: 0, limit: 20 } });

it('maps an IMDb film with a single /find request', async () => {
  const f = mockFetch([['/find/tt1111111', { movie_results: [{ id: 9, title: 'Film', release_date: '2026-11-10', poster_path: '/p.jpg' }], tv_results: [] }]]);
  const row = await refreshItem(movie('tt1111111'), ctxWith(f));
  expect(f.calls.length).toBe(1);
  expect(new URL(f.calls[0].url).searchParams.get('external_source')).toBe('imdb_id');
  expect(row).toMatchObject({ tmdb_type: 'movie', tmdb_id: 9, mapped_at: NOW, checked_at: NOW, info: { release: '2026-11-10', poster: '/p.jpg' } });
});

it('maps an IMDb series then loads details, and reuses the cached id later', async () => {
  const f = mockFetch([['/find/', { tv_results: [{ id: 77 }], movie_results: [] }], ['/tv/77', tvJson()]]);
  const ctx = ctxWith(f);
  const row = await refreshItem(tv('tt2222222'), ctx);
  expect(f.calls.map(c => new URL(c.url).pathname)).toEqual(['/3/find/tt2222222', '/3/tv/77']);
  expect(row.info).toMatchObject({ kind: 'tv', last: { s: 2, e: 5 } });
  await refreshItem({ ...tv('tt2222222'), cache: row }, ctx);
  expect(f.calls.length).toBe(3);
  expect(ctx.budget.left).toBe(48 - 3);
});

it('uses tmdb keys directly and clears a mapping that TMDB no longer knows', async () => {
  const f = mockFetch([['/tv/5', () => json({}, 404)]]);
  const row = await refreshItem(tv('tmdb-tv-5'), ctxWith(f));
  expect(row).toMatchObject({ tmdb_id: null, mapped_at: null, checked_at: NOW });
});

it('resolves Kinopoisk-only titles through PoiskKino and counts the daily quota', async () => {
  const f = mockFetch([['poiskkino.dev/v1.5/movie/321', { externalId: { tmdb: 88 } }], ['/movie/88', { title: 'T', release_date: '2026-11-11' }]]);
  const ctx = ctxWith(f, { POISKKINO_API_KEY: 'pk' });
  const row = await refreshItem(movie('kp-321'), ctx);
  expect(f.calls[0].init.headers['X-API-KEY']).toBe('pk');
  expect(row).toMatchObject({ tmdb_type: 'movie', tmdb_id: 88, info: { release: '2026-11-11' } });
  expect(ctx.quota.count).toBe(1);
  const noTmdb = mockFetch([['poiskkino', { name: 'Только КП', premiere: { world: '2026-11-10' } }]]);
  const row2 = await refreshItem(movie('kp-322'), ctxWith(noTmdb, { POISKKINO_API_KEY: 'pk' }));
  expect(row2).toMatchObject({ tmdb_id: null, info: { source: 'poiskkino', release: '2026-11-10' } });
});

it('picks the oldest due titles that fit the budget and skips unreachable ones', () => {
  const old = (key, checked_at, extra = {}) => ({ ...movie(key, extra), cache: checked_at === null ? null : { checked_at, tmdb_id: 1, tmdb_type: 'movie' } });
  const items = [
    old('tt0000003', NOW - 30 * 3600000), old('tt0000001', null), old('tt0000002', NOW - 25 * 3600000),
    old('tt0000004', NOW - 3600000), { ...movie('kp-5'), cache: null }, old('tt0000006', NOW - 26 * 3600000),
  ];
  expect(pickRefresh(items, NOW, TODAY, 3).map(i => i.key)).toEqual(['tt0000001', 'tt0000003', 'tt0000006']);
  expect(pickRefresh(items, NOW, TODAY, 100).map(i => i.key)).not.toContain('tt0000004');
  expect(pickRefresh(items, NOW, TODAY, 100).map(i => i.key)).not.toContain('kp-5');
  expect(pickRefresh(items, NOW, TODAY, 100, { poiskkino: true }).map(i => i.key)).toContain('kp-5');
  const unmappedSeries = [{ ...tv('tt0000009'), cache: null }];
  expect(pickRefresh(unmappedSeries, NOW, TODAY, 1)).toEqual([]);
  expect(pickRefresh(unmappedSeries, NOW, TODAY, 2).length).toBe(1);
});

it('stops when the request budget is exhausted', () => {
  const budget = new Budget(1);
  budget.take();
  expect(() => budget.take()).toThrow(BudgetError);
});

it('falls back from a failed photo to text, and reports total failure', async () => {
  const env2 = { TG_BOT_TOKEN: 'tok', TG_CHAT_ID: '-100' };
  const event = { caption: 'текст', photo: 'https://image.tmdb.org/t/p/w500/x.jpg' };
  const f = mockFetch([['/sendPhoto', { ok: false, description: 'bad photo' }], ['/sendMessage', { ok: true }]]);
  expect(await sendTelegram(event, { env: env2, fetch: f, budget: new Budget(5) })).toEqual({ ok: true, via: 'text' });
  expect(f.calls[0].body).toEqual({ chat_id: '-100', photo: event.photo, caption: 'текст' });
  expect(f.calls[1].body).toEqual({ chat_id: '-100', text: 'текст' });
  const noPhoto = mockFetch([['/sendMessage', { ok: true }]]);
  expect(await sendTelegram({ caption: 'x', photo: null }, { env: env2, fetch: noPhoto, budget: new Budget(5) })).toEqual({ ok: true, via: 'text' });
  expect(noPhoto.calls.length).toBe(1);
  const down = vi.fn(async () => { throw new TypeError('network'); });
  expect(await sendTelegram(event, { env: env2, fetch: down, budget: new Budget(5) })).toEqual({ ok: false });
});

it('maps delivery rows to states', () => {
  expect(deliveryState(undefined)).toBe('new');
  expect(deliveryState({ status: 'failed', attempts: 1 })).toBe('retry');
  expect(deliveryState({ status: 'failed', attempts: 3 })).toBe('gave_up');
  expect(deliveryState({ status: 'pending', attempts: 1 })).toBe('pending');
  expect(deliveryState({ status: 'sent', attempts: 1 })).toBe('sent');
});

const seedTitles = async rows => {
  await env.DB.batch(rows.map(([key, data, deleted = 0], i) => env.DB.prepare(
    'INSERT INTO titles (key, data, updated_at, deleted, seq) VALUES (?, ?, 1, ?, ?)').bind(key, JSON.stringify(data), deleted, i + 1)));
};
const listEnv = (extra = {}) => ({ DB: env.DB, LIST_KEY: 'k', TMDB_API_KEY: V3, TG_BOT_TOKEN: 'tok', TG_CHAT_ID: '-1', ...extra });
const telegram = (ok = true) => [['api.telegram.org', () => json({ ok })]];
const catalog = [['/find/tt0000001', { tv_results: [{ id: 1 }] }], ['/tv/1', tvJson()],
  ['/find/tt0000002', { movie_results: [{ id: 2, title: 'M', release_date: TODAY }] }]];

it('sends each event once and does not resend on the next run', async () => {
  await seedTitles([
    ['tt0000001', { title_ru: 'Сериал', type: 'tv_show', notify: true, status: 'watching' }],
    ['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched', year: 2026 }],
    ['tt0000003', { title_ru: 'Брошен', type: 'tv_show', notify: true, status: 'dropped' }],
    ['tt0000004', { title_ru: 'Удалён', type: 'movie', status: 'unwatched' }, 1],
    ['ai-idea', { title_ru: 'ИИ', type: 'movie', status: 'unwatched' }],
  ]);
  const f = mockFetch([...telegram(), ...catalog]);
  const report = await runNotify(listEnv(), { fetch: f, now: NOW });
  expect(report).toMatchObject({ titles: 2, refreshed: 2, sent: 3, failed: 0 });
  expect(report.events.map(e => [e.key, e.state])).toEqual([
    ['ep:tt0000001:S2E5', 'sent'], ['remind:tt0000001:S2E6:2026-11-11', 'sent'], ['movie:tt0000002', 'sent']]);
  const sends = f.calls.filter(c => c.url.includes('telegram'));
  expect(sends.map(c => c.body.caption ?? c.body.text)).toEqual([
    '📺 Вышла серия S2E5 «Пятая» — Сериал', '🗓 Завтра: S2E6 — Сериал', '🎬 Вышел фильм «Фильм» (2026)']);
  expect(f.calls.some(c => c.url.includes('tt0000003') || c.url.includes('tt0000004'))).toBe(false);
  const rows = (await env.DB.prepare('SELECT event_key, status, attempts FROM sent_events ORDER BY event_key').all()).results;
  expect(rows.every(r => r.status === 'sent' && r.attempts === 1)).toBe(true);
  // Same day, cached metadata is fresh: no catalog calls, no messages.
  const again = mockFetch([...telegram(), ...catalog]);
  const second = await runNotify(listEnv(), { fetch: again, now: NOW + 3600000 });
  expect(second.sent).toBe(0);
  expect(again.calls).toEqual([]);
  expect(second.events.every(e => e.state === 'sent')).toBe(true);
});

it('retries a failed delivery on a later run and gives up after three attempts', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched' }]]);
  const failing = mockFetch([...telegram(false), ...catalog]);
  for (let attempt = 1; attempt <= 3; attempt++) {
    const report = await runNotify(listEnv(), { fetch: failing, now: NOW + attempt });
    expect(report.failed).toBe(1);
  }
  const row = await env.DB.prepare('SELECT status, attempts FROM sent_events').first();
  expect(row).toEqual({ status: 'failed', attempts: 3 });
  const ok = mockFetch([...telegram(true), ...catalog]);
  const report = await runNotify(listEnv(), { fetch: ok, now: NOW + 10 });
  expect(report.events[0].state).toBe('gave_up');
  expect(ok.calls.filter(c => c.url.includes('telegram'))).toEqual([]);
});

it('recovers on the next run after a single failure', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched' }]]);
  await runNotify(listEnv(), { fetch: mockFetch([...telegram(false), ...catalog]), now: NOW });
  const report = await runNotify(listEnv(), { fetch: mockFetch([...telegram(true), ...catalog]), now: NOW + 1 });
  expect(report.sent).toBe(1);
  expect(await env.DB.prepare('SELECT status, attempts FROM sent_events').first()).toEqual({ status: 'sent', attempts: 2 });
});

it('never resends an event left pending by an interrupted run', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched' }]]);
  await env.DB.prepare("INSERT INTO sent_events VALUES ('movie:tt0000002', 'pending', 1, 1, 1)").run();
  const f = mockFetch([...telegram(), ...catalog]);
  const report = await runNotify(listEnv(), { fetch: f, now: NOW });
  expect(report.events[0].state).toBe('pending');
  expect(f.calls.filter(c => c.url.includes('telegram'))).toEqual([]);
});

it('keeps sending known events when TMDB rejects the key', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched', premiere_date: TODAY }]]);
  const f = mockFetch([...telegram(), ['themoviedb', () => json({}, 401)]]);
  const report = await runNotify(listEnv(), { fetch: f, now: NOW });
  expect(report).toMatchObject({ refresh_error: 'tmdb_unauthorized', sent: 1 });
});

it('dry run returns events without sending or writing anything', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched' }]]);
  const f = mockFetch([...telegram(), ...catalog]);
  const report = await runNotify(listEnv({ POISKKINO_API_KEY: 'pk' }), { fetch: f, now: NOW, dry: true });
  expect(report.events).toEqual([{ key: 'movie:tt0000002', type: 'movie', caption: '🎬 Вышел фильм «Фильм» (2026)', photo: null, state: 'new' }]);
  expect(f.calls.filter(c => c.url.includes('telegram'))).toEqual([]);
  for (const table of ['sent_events', 'cron_titles', 'cron_state']) {
    expect((await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first()).n).toBe(0);
  }
});

it('skips sending without Telegram secrets and skips catalogs without TMDB key', async () => {
  await seedTitles([['tt0000002', { title_ru: 'Фильм', type: 'movie', status: 'unwatched', premiere_date: TODAY }]]);
  const f = mockFetch([]);
  const report = await runNotify({ DB: env.DB, TG_BOT_TOKEN: 'tok' }, { fetch: f, now: NOW });
  expect(report.skipped).toBe('telegram_not_configured');
  expect(report.events.map(e => e.key)).toEqual(['movie:tt0000002']);
  expect(f.calls).toEqual([]);
  expect((await env.DB.prepare('SELECT COUNT(*) AS n FROM sent_events').first()).n).toBe(0);
});

it('spreads a large list over runs with the checked_at cursor and stays within the budget', async () => {
  const titles = Array.from({ length: 60 }, (_, i) => [`tmdb-movie-${i + 1}`, { title_ru: `Ф${i}`, type: 'movie', status: 'unwatched' }]);
  await seedTitles(titles);
  const f = mockFetch([['/movie/', { title: 'X', release_date: '2020-01-01' }]]);
  const first = await runRefresh(listEnv({ CRON_SUBREQUESTS: '20' }), { fetch: f, now: NOW });
  // 20 - 2 safety - 1 load - 1 save = 16 catalog requests.
  expect(first).toMatchObject({ titles: 60, refreshed: 16 });
  expect(f.calls.length).toBe(16);
  const notify = await runNotify(listEnv({ CRON_SUBREQUESTS: '50' }), { fetch: f, now: NOW + 60000 });
  expect(notify.refreshed).toBe(48 - 1 - 15 - 1);
  const checked = (await env.DB.prepare('SELECT COUNT(*) AS n FROM cron_titles').first()).n;
  expect(checked).toBe(16 + 31);
  await runRefresh(listEnv(), { fetch: f, now: NOW + 120000 });
  expect((await env.DB.prepare('SELECT COUNT(*) AS n FROM cron_titles').first()).n).toBe(60);
  const keys = new Set(f.calls.map(c => new URL(c.url).pathname));
  expect(keys.size).toBe(60);
});

it('defers events beyond the send budget to the next run', async () => {
  const titles = Array.from({ length: 6 }, (_, i) => [`tmdb-movie-${i + 1}`, { title_ru: `Ф${i}`, type: 'movie', status: 'unwatched', premiere_date: TODAY }]);
  await seedTitles(titles);
  const f = mockFetch([...telegram()]);
  const report = await runNotify(listEnv({ TMDB_API_KEY: '', CRON_SUBREQUESTS: '12' }), { fetch: f, now: NOW });
  // 10 usable - 1 load = 9; one claim batch leaves 8 → 2 events of 3 calls each.
  expect(report).toMatchObject({ sent: 2, deferred: 4 });
  const next = await runNotify(listEnv({ TMDB_API_KEY: '' }), { fetch: f, now: NOW + 1 });
  expect(next.sent).toBe(4);
});

it('serves the protected manual endpoint and validates its query', async () => {
  const call = (path, key = env.LIST_KEY) => worker.fetch(new Request(`https://example.test${path}`,
    { method: 'POST', headers: key ? { 'X-VG-List-Key': key } : {} }), env);
  expect((await call('/v1/cron/run?job=notify&dry=1', '')).status).toBe(403);
  for (const q of ['', '?job=other', '?job=notify&dry=2']) expect((await call('/v1/cron/run' + q)).status).toBe(400);
  const dry = await call('/v1/cron/run?job=notify&dry=1');
  expect(dry.status).toBe(200);
  expect(await dry.json()).toMatchObject({ job: 'notify', dry: true, events: [] });
  expect(await (await call('/v1/cron/run?job=backup&dry=1')).json()).toEqual({ job: 'backup', titles: 0, episodes: 0, dry: true });
  expect(await (await call('/v1/cron/run?job=refresh')).json()).toEqual({ job: 'refresh', skipped: 'not_configured' });
  expect((await worker.fetch(new Request('https://example.test/v1/cron/run?job=notify', { headers: { 'X-VG-List-Key': env.LIST_KEY } }), env)).status).toBe(405);
});

it('dispatches cron triggers to their jobs without leaking errors', async () => {
  const log = vi.spyOn(console, 'log').mockImplementation(() => {});
  const error = vi.spyOn(console, 'error').mockImplementation(() => {});
  await worker.scheduled({ cron: '0 0 * * *', scheduledTime: NOW }, { DB: env.DB });
  expect(log.mock.calls.map(c => JSON.parse(c[0]).job)).toEqual(['backup', 'refresh']);
  log.mockClear();
  await worker.scheduled({ cron: '0 7 * * *', scheduledTime: NOW }, { DB: env.DB });
  expect(log.mock.calls.map(c => JSON.parse(c[0]).job)).toEqual(['notify']);
  await worker.scheduled({ cron: '0 7 * * *', scheduledTime: NOW }, { DB: { batch: async () => { throw new Error('D1 secret detail'); } } });
  expect(JSON.parse(error.mock.calls.at(-1)[0])).toEqual({ cron: '0 7 * * *', error: 'internal' });
});
