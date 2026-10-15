// Regression: the account manager must list every active actor, not only the
// API's default first page (open-hax/axxium#30 r4277636461).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchAllActors, ACTOR_PAGE_SIZE, parseActorPage } from '../resources/public/actor-pages.js';

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
  return { request, calls, actors };
}

test('fetches every page beyond the first 50 actors', async () => {
  const { request, calls } = pagedApi(237);
  const actors = await fetchAllActors(request);
  assert.equal(actors.length, 237);
  assert.deepEqual(actors.map((a) => a.id), Array.from({ length: 237 }, (_, i) => `actor_${i}`));
  assert.equal(ACTOR_PAGE_SIZE, 100);
  // One reconcile pass after a multi-page read (offsets can shift between pages).
  assert.deepEqual(calls.slice(0, 3), [
    '/api/actors?limit=100&offset=0',
    '/api/actors?limit=100&offset=100',
    '/api/actors?limit=100&offset=200',
  ]);
  assert.ok(calls.length >= 3);
});

test('an exact multiple of the page size stops on the empty page', async () => {
  const { request, calls } = pagedApi(200);
  assert.equal((await fetchAllActors(request)).length, 200);
  // First pass: pages at 0, 100, 200 (empty). Reconcile pass follows.
  assert.ok(calls.length >= 3);
});

test('duplicate rows from a shifting offset are listed once', async () => {
  let n = 0;
  const request = async () => {
    n += 1;
    if (n === 1) return { actors: Array.from({ length: 100 }, (_, i) => ({ id: `a${i}` })) };
    if (n === 2) return { actors: [{ id: 'a99' }, { id: 'b0' }] };
    // Reconcile / later pages: stabilize
    return { actors: [] };
  };
  assert.deepEqual((await fetchAllActors(request)).map((a) => a.id).slice(-2), ['a99', 'b0']);
  assert.equal((await fetchAllActors(async () => ({ actors: [] }))).length, 0);
});

test('a mid-pagination revocation that shifts offsets is recovered on reconcile', async () => {
  // Registry starts as actor_0..actor_149. After page 0, actor_0 is revoked so
  // former actor_100 moves to offset 99. A naive page-1 request at offset 100
  // would skip it; the reconcile pass must recover actor_100.
  let live = Array.from({ length: 150 }, (_, i) => ({ id: `actor_${i}` }));
  let sawPage0 = false;
  const request = async (path) => {
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    const offset = Number(url.searchParams.get('offset'));
    if (offset === 0) sawPage0 = true;
    else if (sawPage0 && live.some((a) => a.id === 'actor_0')) {
      live = live.filter((a) => a.id !== 'actor_0');
    }
    return { actors: live.slice(offset, offset + limit) };
  };
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_100'), 'shifted actor_100 must be recovered');
  assert.equal(new Set(ids).size, ids.length);
});


test('reconcile continues past a full page of already-known actors', async () => {
  // 250 actors; after offsets 0 and 100, revoke actor_100 so actor_200 shifts
  // into offset 199. Pass 1 page 0 is all known → must still reach offset 100.
  let live = Array.from({ length: 250 }, (_, i) => ({ id: `actor_${i}` }));
  let requests = 0;
  const request = async (path) => {
    requests += 1;
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    const offset = Number(url.searchParams.get('offset'));
    if (requests === 3) {
      // Before first offset-200 fetch: revoke actor_100.
      live = live.filter((a) => a.id !== 'actor_100');
    }
    return { actors: live.slice(offset, offset + limit) };
  };
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_200'), 'actor shifted across a later page must be recovered');
  assert.equal(new Set(ids).size, ids.length);
});

test('a full first page still reconciles when the next offset is empty after a shift', async () => {
  // 101 actors; after page 0 (100 rows), revoke actor_0 so actor_100 moves to
  // offset 99 and offset 100 returns []. Must still reconcile.
  let live = Array.from({ length: 101 }, (_, i) => ({ id: `actor_${i}` }));
  let page0 = false;
  const request = async (path) => {
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    const offset = Number(url.searchParams.get('offset'));
    if (offset === 0) page0 = true;
    else if (page0 && live.some((a) => a.id === 'actor_0')) {
      live = live.filter((a) => a.id !== 'actor_0');
    }
    return { actors: live.slice(offset, offset + limit) };
  };
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_100'), 'actor shifted into page 0 must be recovered');
});

test('malformed actor-page payloads are rejected', async () => {
  assert.throws(() => parseActorPage(null), /Malformed/);
  assert.throws(() => parseActorPage({}), /actors must be an array/);
  assert.throws(() => parseActorPage({ actors: [{ id: 1 }] }), /non-empty string id/);
  assert.throws(() => parseActorPage({ actors: [{ id: '' }] }), /non-empty string id/);
  await assert.rejects(
    fetchAllActors(async () => ({ ok: true })),
    (e) => /Malformed/.test(e.message) && e.status === 502,
  );
  await assert.rejects(
    fetchAllActors(async () => ({ actors: 'nope' })),
    (e) => /Malformed/.test(e.message),
  );
});

test('request errors propagate (403 handling stays with the caller)', async () => {
  const forbidden = Object.assign(new Error('Forbidden'), { status: 403 });
  await assert.rejects(fetchAllActors(async () => { throw forbidden; }), (e) => e === forbidden);
});
