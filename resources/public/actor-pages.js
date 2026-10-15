// Fetch every active actor for the account manager. GET /api/actors returns
// at most 100 rows per page (default 50), newest first, so a single request
// would hide older actors once the registry grows past one page.
export const ACTOR_PAGE_SIZE = 100;
// Defensive bound: 1000 pages × 100 rows. A server that ignored `offset`
// would otherwise repeat the same full page forever.
const MAX_ACTOR_PAGES = 1000;
// Offset pages can skip actors when rows shift between requests. Extra passes
// reconcile omissions before we claim completeness.
const MAX_RECONCILE_PASSES = 3;

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

export async function fetchAllActors(request, pageSize = ACTOR_PAGE_SIZE) {
  const byId = new Map();
  let firstPageWasShort = false;
  for (let pass = 0; pass < MAX_RECONCILE_PASSES; pass += 1) {
    let addedThisPass = 0;
    let hitPageCap = false;
    for (let page = 0; page < MAX_ACTOR_PAGES; page += 1) {
      const offset = page * pageSize;
      const rows = parseActorPage(await request(`/api/actors?limit=${pageSize}&offset=${offset}`));
      if (pass === 0 && page === 0) {
        firstPageWasShort = rows.length < pageSize;
      }
      for (const actor of rows) {
        if (byId.has(actor.id)) continue;
        byId.set(actor.id, actor);
        addedThisPass += 1;
      }
      if (rows.length < pageSize) break;
      if (page === MAX_ACTOR_PAGES - 1) hitPageCap = true;
    }
    if (hitPageCap) {
      throw Object.assign(
        new Error(`Actor registry exceeds pagination bound (${MAX_ACTOR_PAGES} pages × ${pageSize})`),
        { status: 502 },
      );
    }
    if (pass === 0 && firstPageWasShort) break;
    if (pass > 0 && addedThisPass === 0) break;
  }
  return Array.from(byId.values());
}
