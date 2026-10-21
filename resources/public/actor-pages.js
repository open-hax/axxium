// Fetch every active actor for the account manager. GET /api/actors returns
// at most 100 rows per page (default 50), newest first, so a single request
// would hide older actors once the registry grows past one page.
export const ACTOR_PAGE_SIZE = 100;
// Defensive bound: 1000 pages × 100 rows.
const MAX_ACTOR_PAGES = 1000;
// Pages after the first use a keyset cursor (`after=<last actor id>`). The
// server continues strictly after that row's (created_at, id), so revoking
// other actors mid-read cannot shift an active actor past the reader the way
// OFFSET pages could (riatzukiza/axxium#10 PRRT_kwDOU4Vg086s2hgv).

export function parseActorPage(result) {
  if (result == null || typeof result !== 'object' || Array.isArray(result)) {
    throw Object.assign(new Error('Malformed actor page: expected an object'), { status: 502 });
  }
  if (!Array.isArray(result.actors)) {
    throw Object.assign(new Error('Malformed actor page: actors must be an array'), { status: 502 });
  }
  for (const actor of result.actors) {
    if (actor == null || typeof actor !== 'object' || typeof actor.id !== 'string' || actor.id.length === 0) {
      throw Object.assign(new Error('Malformed actor page: each actor needs a non-empty string id'), { status: 502 });
    }
  }
  return result.actors;
}

function pagePath(pageSize, after) {
  const base = `/api/actors?limit=${pageSize}`;
  return after == null ? base : `${base}&after=${encodeURIComponent(after)}`;
}

export async function fetchAllActors(request, pageSize = ACTOR_PAGE_SIZE) {
  const byId = new Map();
  let after = null;
  for (let page = 0; page <= MAX_ACTOR_PAGES; page += 1) {
    const rows = parseActorPage(await request(pagePath(pageSize, after)));
    if (page === MAX_ACTOR_PAGES) {
      // One probe past the bound: an exactly-at-the-limit registry is
      // accepted; any further row means the list would be truncated.
      if (rows.length > 0) {
        throw Object.assign(
          new Error(`Actor registry exceeds pagination bound (${MAX_ACTOR_PAGES} pages × ${pageSize})`),
          { status: 502 },
        );
      }
      break;
    }
    let added = 0;
    for (const actor of rows) {
      if (byId.has(actor.id)) continue;
      byId.set(actor.id, actor);
      added += 1;
    }
    if (rows.length < pageSize) break;
    if (added === 0) {
      // Every row after a keyset cursor is strictly older, so a full page of
      // already-seen actors means the server ignored the cursor.
      throw Object.assign(new Error('Actor pagination cursor did not advance'), { status: 502 });
    }
    after = rows[rows.length - 1].id;
  }
  return Array.from(byId.values());
}
