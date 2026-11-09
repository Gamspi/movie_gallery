import { env } from 'cloudflare:test';
import { beforeAll, expect, it } from 'vitest';
import worker from '../src/worker.js';
import schema from '../schema.sql?raw';

beforeAll(async () => {
  await env.DB.batch(schema.split(';').map(s => s.trim()).filter(Boolean).map(s => env.DB.prepare(s)));
});

const request = (path = '/v1/health', key = env.LIST_KEY, method = 'GET') =>
  new Request(`https://example.test${path}`, { method, headers: key ? { 'X-VG-List-Key': key } : {} });

it('returns initial sequence', async () => {
  const response = await worker.fetch(request(), env);
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ ok: true, seq: 0 });
});

it.each(['', 'wrong-key'])('rejects missing or incorrect key: %s', async key => {
  const response = await worker.fetch(request('/v1/health', key), env);
  expect(response.status).toBe(403);
  expect(await response.json()).toEqual({ error: 'bad_key' });
});

it('fails closed without configuration', async () => {
  const response = await worker.fetch(request(), { DB: env.DB });
  expect(response.status).toBe(500);
  expect(await response.json()).toEqual({ error: 'not_configured' });
});

it('rejects unknown routes', async () => {
  const response = await worker.fetch(request('/unknown'), env);
  expect(response.status).toBe(404);
  expect(await response.json()).toEqual({ error: 'not_found' });
});

it('rejects unsupported methods', async () => {
  expect((await worker.fetch(request('/v1/health', env.LIST_KEY, 'PUT'), env)).status).toBe(405);
});
