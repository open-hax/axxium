// Regression: the account manager must list every active actor, not only the
// API's default first page (open-hax/axxium#30 r4277636461).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchAllActors, ACTOR_PAGE_SIZE } from '../resources/public/actor-pages.js';

function pagedApi(total) {
  const actors = Array.from({ length: total }, (_, i) => ({ id: `actor_${i}` }));
  const calls = [];
  const request = async (path) => {
    calls.push(path);
    const url = new URL(path, 'http://portal.test');
    assert.equal(url.pathname, '/api/actors');
    const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
    const offset = Math.max(0, Number(url.searchParams.get('offset') ?? 0));
    const page = actors.slice(offset, offset + limit);
    return { ok: true, actors: page, count: page.length };
  };
  return { request, calls };
}

test('fetches every page beyond the first 50 actors', async () => {
  const { request, calls } = pagedApi(237);
  const actors = await fetchAllActors(request);
  assert.equal(actors.length, 237);
  assert.deepEqual(actors.map((a) => a.id), Array.from({ length: 237 }, (_, i) => `actor_${i}`));
  assert.equal(ACTOR_PAGE_SIZE, 100);
  assert.deepEqual(calls, [
    '/api/actors?limit=100&offset=0',
    '/api/actors?limit=100&offset=100',
    '/api/actors?limit=100&offset=200',
  ]);
});

test('an exact multiple of the page size stops on the empty page', async () => {
  const { request, calls } = pagedApi(200);
  assert.equal((await fetchAllActors(request)).length, 200);
  assert.equal(calls.length, 3);
});

test('duplicate rows from a shifting offset are listed once', async () => {
  let n = 0;
  const request = async () => {
    n += 1;
    if (n === 1) return { actors: Array.from({ length: 100 }, (_, i) => ({ id: `a${i}` })) };
    return { actors: [{ id: 'a99' }, { id: 'b0' }] };
  };
  assert.deepEqual((await fetchAllActors(request)).map((a) => a.id).slice(-2), ['a99', 'b0']);
  assert.equal((await fetchAllActors(async () => ({ actors: [] }))).length, 0);
});

test('request errors propagate (403 handling stays with the caller)', async () => {
  const forbidden = Object.assign(new Error('Forbidden'), { status: 403 });
  await assert.rejects(fetchAllActors(async () => { throw forbidden; }), (e) => e === forbidden);
});
