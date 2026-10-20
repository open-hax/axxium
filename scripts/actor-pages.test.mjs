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


test('exceeding the page bound rejects instead of returning a truncated list', async () => {
  const request = async (path) => {
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    const offset = Number(url.searchParams.get('offset'));
    // Every page is full through the bound.
    return { actors: Array.from({ length: limit }, (_, i) => ({ id: `actor_${offset + i}` })) };
  };
  await assert.rejects(fetchAllActors(request), (e) => /pagination bound/.test(e.message) && e.status === 502);
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

test('exactly MAX_ACTOR_PAGES full pages is accepted after an empty probe', async () => {
  // 1000 × 100 = 100_000 actors: last in-bound page is full, but offset 100_000
  // is empty — must succeed (Codex PRRT_kwDOU4Vg086s1cwJ).
  const { request, calls } = pagedApi(100_000);
  assert.equal((await fetchAllActors(request)).length, 100_000);
  assert.ok(calls.includes('/api/actors?limit=100&offset=100000'), 'must probe one past the bound');
  assert.ok(!calls.includes('/api/actors?limit=100&offset=100100'));
});

test('a non-empty probe past the page bound still rejects', async () => {
  const { request } = pagedApi(100_001);
  await assert.rejects(fetchAllActors(request), (e) => /pagination bound/.test(e.message) && e.status === 502);
});

// Codex PRRT_kwDOU4Vg086s2hgv (riatzukiza/axxium#10): offset pages cannot prove
// completeness while actors are revoked, because each revocation shifts later
// rows ahead of the requested offset. A keyset cursor (`after=<last id>`) is
// anchored on a row, so revocations elsewhere cannot hide an active actor.
function revokingKeysetApi(total, revokePerRequest) {
  // Newest first, matching ORDER BY created_at DESC, id DESC.
  const all = Array.from({ length: total }, (_, i) => ({ id: `actor_${i}` }));
  const revoked = new Set();
  const calls = [];
  let nextRevoke = 0;
  const request = async (path) => {
    calls.push(path);
    if (calls.length > 1) {
      // Concurrent administrators keep revoking the newest remaining actors.
      for (let k = 0; k < revokePerRequest && nextRevoke < total - 1; k += 1) {
        revoked.add(all[nextRevoke].id);
        nextRevoke += 1;
      }
    }
    const url = new URL(path, 'http://portal.test');
    const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
    let start = 0;
    if (url.searchParams.has('after')) {
      // Cursor rows stay in the table after revocation (status changes only).
      const idx = all.findIndex((a) => a.id === url.searchParams.get('after'));
      if (idx < 0) throw Object.assign(new Error('Unknown cursor'), { status: 400 });
      start = idx + 1;
    } else {
      const offset = Math.max(0, Number(url.searchParams.get('offset') ?? 0));
      // Offset applies to the *active* rows at request time.
      const active = all.filter((a) => !revoked.has(a.id));
      return { actors: active.slice(offset, offset + limit) };
    }
    return { actors: all.slice(start).filter((a) => !revoked.has(a.id)).slice(0, limit) };
  };
  return { request, calls, all, revoked };
}

test('continuous revocations during pagination never hide a never-revoked actor', async () => {
  const { request, revoked, all } = revokingKeysetApi(450, 40);
  const ids = new Set((await fetchAllActors(request)).map((a) => a.id));
  const neverRevoked = all.filter((a) => !revoked.has(a.id)).map((a) => a.id);
  const missing = neverRevoked.filter((id) => !ids.has(id));
  assert.deepEqual(missing, [], 'every actor active throughout the read must be listed');
});

test('pages after the first are requested by keyset cursor, not offset', async () => {
  const { request, calls } = revokingKeysetApi(237, 0);
  assert.equal((await fetchAllActors(request)).length, 237);
  assert.deepEqual(calls, [
    '/api/actors?limit=100',
    '/api/actors?limit=100&after=actor_99',
    '/api/actors?limit=100&after=actor_199',
  ]);
});
