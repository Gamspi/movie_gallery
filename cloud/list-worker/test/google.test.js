import { env } from 'cloudflare:test';
import { beforeAll, beforeEach, expect, it, vi } from 'vitest';
import schema from '../schema.sql?raw';
import { backupRows, pemToPkcs8, runBackup, signJwt } from '../src/google.js';

beforeAll(async () => {
  await env.DB.batch(schema.split(';').map(s => s.trim()).filter(Boolean).map(s => env.DB.prepare(s)));
});
beforeEach(async () => {
  await env.DB.batch(['titles', 'episodes'].map(t => env.DB.prepare(`DELETE FROM ${t}`)));
});

const NOW = Date.parse('2026-11-10T00:00:00Z');
const json = (body, status = 200) => new Response(JSON.stringify(body), { status });
const fromB64url = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - s.length % 4) % 4)), c => c.charCodeAt(0));

/** Throwaway RSA key generated per run; nothing real is stored in the tests. */
async function testAccount() {
  const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const der = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  const b64 = btoa(String.fromCharCode(...der)).match(/.{1,64}/g).join('\n');
  return { publicKey: pair.publicKey,
    sa: { client_email: 'backup@test.iam.gserviceaccount.com', private_key: `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n` } };
}

it('signs a verifiable RS256 assertion for the Sheets scope', async () => {
  const { sa, publicKey } = await testAccount();
  expect(pemToPkcs8(sa.private_key).byteLength).toBeGreaterThan(1000);
  const jwt = await signJwt(sa, NOW);
  const [header, claims, signature] = jwt.split('.');
  expect(JSON.parse(new TextDecoder().decode(fromB64url(header)))).toEqual({ alg: 'RS256', typ: 'JWT' });
  expect(JSON.parse(new TextDecoder().decode(fromB64url(claims)))).toEqual({ iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/spreadsheets', aud: 'https://oauth2.googleapis.com/token',
    iat: NOW / 1000, exp: NOW / 1000 + 3600 });
  expect(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', publicKey, fromB64url(signature),
    new TextEncoder().encode(`${header}.${claims}`))).toBe(true);
});

it('builds Russian sheets rows sorted by key with episode titles', () => {
  const rows = backupRows([
    { key: 'tt0000002', updated_at: NOW, data: JSON.stringify({ title_ru: 'Б', type: 'tv_show', status: 'watching', notify: true, season_ratings: { 1: 8 } }) },
    { key: 'kp-1', updated_at: NOW, data: JSON.stringify({ title_ru: 'А', type: 'movie', status: 'watched', my_rating: 9, year: 2020 }) },
  ], [
    { title_key: 'tt0000002', season: 2, episode: 1, watched: 1, updated_at: NOW },
    { title_key: 'tt0000002', season: 1, episode: 3, watched: 0, updated_at: NOW },
  ]);
  expect(rows.titles[0].slice(0, 4)).toEqual(['Ключ', 'Название', 'Тип', 'Статус']);
  expect(rows.titles[1].slice(0, 8)).toEqual(['kp-1', 'А', 'movie', 'watched', 9, '', '', 2020]);
  expect(rows.titles[2].slice(5, 7)).toEqual(['{"1":8}', 'да']);
  expect(rows.titles[2][12]).toBe('2026-11-10 03:00');
  expect(rows.episodes.slice(1)).toEqual([
    ['tt0000002', 'Б', 1, 3, 'нет', '2026-11-10 03:00'], ['tt0000002', 'Б', 2, 1, 'да', '2026-11-10 03:00']]);
});

function googleMock({ sheets = ['Лист1', 'Тайтлы'], token = 200 } = {}) {
  const calls = [];
  const fn = vi.fn(async (url, init = {}) => {
    calls.push({ url: String(url), init });
    const u = String(url);
    if (u.startsWith('https://oauth2.googleapis.com/token')) return json(token === 200 ? { access_token: 'access' } : { error: 'x' }, token);
    if (u.includes('?fields=')) return json({ sheets: sheets.map(title => ({ properties: { title } })) });
    return json({});
  });
  fn.calls = calls;
  return fn;
}

it('creates missing sheets, writes rows and clears the tail; deleted rows are excluded', async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO titles (key, data, updated_at, deleted, seq) VALUES ('kp-1', '{\"title_ru\":\"А\"}', 1, 0, 1)"),
    env.DB.prepare("INSERT INTO titles (key, data, updated_at, deleted, seq) VALUES ('kp-2', '{\"title_ru\":\"Удалён\"}', 1, 1, 2)"),
    env.DB.prepare("INSERT INTO episodes (title_key, season, episode, watched, updated_at, deleted, seq) VALUES ('kp-1', 1, 1, 1, 1, 0, 3)"),
    env.DB.prepare("INSERT INTO episodes (title_key, season, episode, watched, updated_at, deleted, seq) VALUES ('kp-1', 1, 2, 1, 1, 1, 4)"),
  ]);
  const { sa } = await testAccount();
  const f = googleMock();
  const report = await runBackup({ DB: env.DB, GOOGLE_SA_JSON: JSON.stringify(sa), GOOGLE_SHEET_ID: 'sheet_ID-1234567' }, { fetch: f, now: NOW });
  expect(report).toEqual({ job: 'backup', titles: 1, episodes: 1, dry: false, created: ['Серии'] });
  const [token, meta, add, write, clear] = f.calls;
  expect(new URLSearchParams(token.init.body).get('grant_type')).toBe('urn:ietf:params:oauth:grant-type:jwt-bearer');
  expect(meta.init.headers.authorization).toBe('Bearer access');
  expect(JSON.parse(add.init.body)).toEqual({ requests: [{ addSheet: { properties: { title: 'Серии' } } }] });
  const written = JSON.parse(write.init.body);
  expect(write.url).toBe('https://sheets.googleapis.com/v4/spreadsheets/sheet_ID-1234567/values:batchUpdate');
  expect(written.valueInputOption).toBe('RAW');
  expect(written.data.map(d => [d.range, d.values.length])).toEqual([["'Тайтлы'!A1", 2], ["'Серии'!A1", 2]]);
  expect(written.data[0].values[1][0]).toBe('kp-1');
  expect(JSON.parse(clear.init.body).ranges).toEqual(["'Тайтлы'!A3:ZZ", "'Тайтлы'!O1:ZZ", "'Серии'!A3:ZZ", "'Серии'!G1:ZZ"]);
  expect(f.calls.length).toBe(5);
});

it('does not add sheets that exist, and reports auth or config problems by code', async () => {
  const { sa } = await testAccount();
  const envOk = { DB: env.DB, GOOGLE_SA_JSON: JSON.stringify(sa), GOOGLE_SHEET_ID: 'sheet_ID-1234567' };
  const f = googleMock({ sheets: ['Тайтлы', 'Серии'] });
  await runBackup(envOk, { fetch: f, now: NOW });
  expect(f.calls.some(c => c.url.endsWith(':batchUpdate') && !c.url.includes('/values'))).toBe(false);
  await expect(runBackup(envOk, { fetch: googleMock({ token: 400 }), now: NOW })).rejects.toThrow('google_auth_failed');
  await expect(runBackup({ ...envOk, GOOGLE_SA_JSON: '{bad' }, { fetch: googleMock(), now: NOW })).rejects.toThrow('google_sa_invalid');
  await expect(runBackup({ ...envOk, GOOGLE_SHEET_ID: '../x' }, { fetch: googleMock(), now: NOW })).rejects.toThrow('google_sheet_invalid');
  expect(await runBackup({ DB: env.DB }, { fetch: googleMock(), now: NOW })).toMatchObject({ skipped: 'not_configured' });
});
