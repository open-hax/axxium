// Regression: the account manager must list every active actor, not only the
// API's default first page (open-hax/axxium#30 r4277636461).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchAllActors, ACTOR_PAGE_SIZE, parseActorPage } from '../resources/public/actor-pages.js';

function pagedApi(total) {
  const actors = Array.from({ length: total }, (_, i) => ({ id: `actor_${i}` }));
  const index = new Map(actors.map((a, i) => [a.id, i]));
  const calls = [];
  const request = async (path) => {
    calls.push(path);
    const url = new URL(path, 'http://portal.test');
    assert.equal(url.pathname, '/api/actors');
    const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') ?? 50)));
    let start = Math.max(0, Number(url.searchParams.get('offset') ?? 0));
    if (url.searchParams.has('after')) {
      const idx = index.get(url.searchParams.get('after'));
      if (idx === undefined) throw Object.assign(new Error('Unknown cursor'), { status: 400 });
      start = idx + 1;
    }
    const page = actors.slice(start, start + limit);
    return { ok: true, actors: page, count: page.length };
  };
  return { request, calls, actors };
}

// Keyset server over a mutable registry: `revoke(id)` flips status only, so
// the cursor row still anchors the next page.
function liveKeysetApi(total, beforeRequest = () => {}) {
  const all = Array.from({ length: total }, (_, i) => ({ id: `actor_${i}` }));
  const revoked = new Set();
  const calls = [];
  const request = async (path) => {
    calls.push(path);
    beforeRequest(calls.length, (id) => revoked.add(id));
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    let start = 0;
    if (url.searchParams.has('after')) {
      start = all.findIndex((a) => a.id === url.searchParams.get('after')) + 1;
    }
    return { actors: all.slice(start).filter((a) => !revoked.has(a.id)).slice(0, limit) };
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
    '/api/actors?limit=100',
    '/api/actors?limit=100&after=actor_99',
    '/api/actors?limit=100&after=actor_199',
  ]);
});

test('an exact multiple of the page size stops on the empty page', async () => {
  const { request, calls } = pagedApi(200);
  assert.equal((await fetchAllActors(request)).length, 200);
  // Pages: first, after actor_99, after actor_199 (empty).
  assert.equal(calls.length, 3);
});

test('duplicate rows are listed once', async () => {
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

test('a revocation after the first page does not skip the next actor', async () => {
  // Under OFFSET, revoking actor_0 after page 0 moved actor_100 to offset 99
  // and a request at offset 100 skipped it. The cursor anchors on actor_99.
  const { request } = liveKeysetApi(150, (n, revoke) => { if (n === 2) revoke('actor_0'); });
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_100'));
  assert.equal(ids.length, 150);
  assert.equal(new Set(ids).size, ids.length);
});

test('a revocation on an earlier page does not skip actors on later pages', async () => {
  const { request } = liveKeysetApi(250, (n, revoke) => { if (n === 3) revoke('actor_100'); });
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_200'));
  assert.equal(new Set(ids).size, ids.length);
});

test('a full first page continues by cursor even when a revocation empties the tail', async () => {
  const { request } = liveKeysetApi(101, (n, revoke) => { if (n === 2) revoke('actor_0'); });
  const ids = (await fetchAllActors(request)).map((a) => a.id);
  assert.ok(ids.includes('actor_100'));
});

test('a server that ignores the cursor is rejected instead of looping', async () => {
  const fullPage = { actors: Array.from({ length: 100 }, (_, i) => ({ id: `a${i}` })) };
  await assert.rejects(
    fetchAllActors(async () => fullPage),
    (e) => /cursor did not advance/.test(e.message) && e.status === 502,
  );
});


test('exceeding the page bound rejects instead of returning a truncated list', async () => {
  const request = async (path) => {
    const url = new URL(path, 'http://portal.test');
    const limit = Number(url.searchParams.get('limit'));
    const after = url.searchParams.get('after');
    const start = after == null ? 0 : Number(after.slice('actor_'.length)) + 1;
    // Every page is full through the bound.
    return { actors: Array.from({ length: limit }, (_, i) => ({ id: `actor_${start + i}` })) };
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
  assert.equal(calls.at(-1), '/api/actors?limit=100&after=actor_99999', 'must probe one past the bound');
  assert.equal(calls.length, 1001);
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
