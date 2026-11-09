import { env } from 'cloudflare:test';
import { beforeAll, beforeEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.js';
import schema from '../schema.sql?raw';
beforeAll(async () => { await env.DB.batch(schema.split(';').map(s => s.trim()).filter(Boolean).map(s => env.DB.prepare(s))); });
beforeEach(async () => {
  vi.restoreAllMocks();
  await env.DB.batch(['DELETE FROM titles','DELETE FROM episodes','DELETE FROM positions',"UPDATE meta SET v=0 WHERE k='seq'"].map(s => env.DB.prepare(s)));
});
const title = (updated_at = 100, key = 'tt0111161') => ({ key, data: { name: 'Фильм' }, updated_at, deleted: false });
const ep = (updated_at = 100) => ({ title_key: 'tt0111161', season: 1, episode: 1, watched: true, updated_at, deleted: false });
const pos = (updated_at = 100) => ({ ...ep(updated_at), position_ms: 12, duration_ms: 100 });
async function call(path, body) {
 const r = await worker.fetch(new Request('https://example.test'+path, { method: body === undefined ? 'GET' : 'POST', headers: {'X-VG-List-Key':env.LIST_KEY}, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) }), env);
 return { status: r.status, body: await r.json() };
}
const batch = b => call('/v1/batch', b);
it('inserts, replaces whole objects, ignores older/equal writes and retries', async () => {
 expect((await batch({titles:[title()]})).body).toEqual({seq:1,applied:1});
 for (const time of [100,99]) expect((await batch({titles:[title(time)]})).body).toEqual({seq:1,applied:0});
 expect((await batch({titles:[{...title(101),data:{rating:8}}]})).body).toEqual({seq:2,applied:1});
 expect((await call('/v1/changes')).body.titles[0].data).toEqual({rating:8});
});
it('clamps future timestamps and ignores a later retry', async () => {
 vi.spyOn(Date,'now').mockReturnValue(1000000);
 const row = title(100000000);
 await batch({titles:[row]});
 vi.spyOn(Date,'now').mockReturnValue(1001000);
 expect((await batch({titles:[row]})).body).toEqual({seq:1,applied:0});
 expect((await call('/v1/changes')).body.titles[0].updated_at).toBe(1300000);
});
it('preserves tombstones against stale writes', async () => {
 await batch({titles:[title(),{...title(101),deleted:true},title(99)]});
 const row = (await call('/v1/changes')).body.titles[0];
 expect(row.deleted).toBe(true); expect(row.data).toEqual(title().data); expect(row.seq).toBe(2);
});
it('keeps episodes and positions independent and applies their conflicts', async () => {
 const data = {titles:[title()],episodes:[ep()],positions:[pos()]};
 expect((await batch(data)).body).toEqual({seq:3,applied:3});
 expect((await batch(data)).body).toEqual({seq:3,applied:0});
 await batch({titles:[title(200)],episodes:[{...ep(101),watched:false}],positions:[{...pos(101),position_ms:55}]});
 const feed = (await call('/v1/changes')).body;
 expect(feed.episodes[0].watched).toBe(false); expect(feed.positions[0].position_ms).toBe(55);
});
it.each([
 ['{',400,'bad_json'], [null,400,'bad_batch'], [{titles:{}},400,'bad_batch'],
 [{titles:[{...title(),key:'invalid'}]},400,'bad_key_format'],
 [{titles:[{...title(),data:[]}]},400,'bad_data'],
 [{titles:[{...title(),updated_at:1.1}]},400,'bad_timestamp'],
 [{titles:[{...title(),deleted:1}]},400,'bad_deleted'],
 [{episodes:[{...ep(),season:-1}]},400,'bad_episode'],
 [{episodes:[{...ep(),episode:10000}]},400,'bad_episode'],
 [{episodes:[{...ep(),watched:1}]},400,'bad_watched'],
 [{positions:[{...pos(),position_ms:-1}]},400,'bad_position'],
 [{positions:[{...pos(),duration_ms:1.5}]},400,'bad_position'],
 [{titles:Array.from({length:501},()=>title())},413,'too_many'],
 [{titles:[{...title(),data:{text:'я'.repeat(8192)}}]},413,'too_large'],
 [' '.repeat(1048577),413,'too_large'],
])('rejects invalid input atomically %#', async (body,status,error) => {
 if (body && typeof body==='object' && Array.isArray(body.titles) && body.titles.length < 500) body.titles.unshift(title(1,'kp-1'));
 expect(await batch(body === null ? 'null' : body)).toEqual({status,body:{error}});
 expect((await call('/v1/health')).body.seq).toBe(0);
 const feed = (await call('/v1/changes')).body;
 expect([...feed.titles,...feed.episodes,...feed.positions]).toEqual([]);
});
it('returns empty feeds and validates queries', async () => {
 expect((await call('/v1/changes')).body).toEqual({titles:[],episodes:[],positions:[],next:0,more:false});
 for (const q of ['since=-1','since=abc','limit=0','limit=5000','since=1.5','since=1&since=2']) expect((await call('/v1/changes?'+q)).status).toBe(400);
 expect((await call('/v1/changes?since=50')).body.next).toBe(50);
});
it('paginates 1200 mixed rows without loss or duplicates', async () => {
 for(let page=0;page<3;page++) {
  const data={titles:[],episodes:[],positions:[]};
  for(let i=0;i<400;i++) { const n=page*400+i; const key='kp-'+n;
   if(n%3===0) data.titles.push(title(100,key));
   if(n%3===1) data.episodes.push({...ep(),title_key:key});
   if(n%3===2) data.positions.push({...pos(),title_key:key});
  }
  expect((await batch(data)).body.applied).toBe(400);
 }
 let next=0; const seqs=[];
 for(const count of [500,500,200]) {
  const page=(await call('/v1/changes?since='+next+'&limit=500')).body;
  const rows=[...page.titles,...page.episodes,...page.positions];
  expect(rows.length).toBe(count); expect(page.more).toBe(count===500);
  seqs.push(...rows.map(r=>r.seq)); next=page.next;
 }
 expect(new Set(seqs).size).toBe(1200); expect(next).toBe(1200);
},30000);
it('rolls back the entire transaction on a database failure', async () => {
 const broken = { ...env, DB: { prepare: (...args) => env.DB.prepare(...args), batch: statements => env.DB.batch([...statements,env.DB.prepare('INSERT INTO missing_table VALUES (1)')]) } };
 const response = await worker.fetch(new Request('https://example.test/v1/batch',{method:'POST',headers:{'X-VG-List-Key':env.LIST_KEY},body:JSON.stringify({titles:[title()]})}),broken);
 expect(response.status).toBe(500);
 expect((await call('/v1/health')).body.seq).toBe(0);
 expect((await call('/v1/changes')).body.titles).toEqual([]);
});
it('serializes competing batches with unique sequences', async () => {
 await Promise.all([batch({titles:[title(100)]}),batch({titles:[title(200)]})]);
 const feed=(await call('/v1/changes')).body;
 expect(feed.titles[0].updated_at).toBe(200);
 expect(feed.next).toBeGreaterThanOrEqual(1);
});
it('accepts an empty batch and the maximum batch size', async () => {
 expect((await batch({})).body).toEqual({seq:0,applied:0});
 expect((await batch({titles:Array.from({length:500},(_,i)=>title(100,'kp-'+i))})).body).toEqual({seq:500,applied:500});
});
