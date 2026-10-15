// Fetch every active actor for the account manager. GET /api/actors returns
// at most 100 rows per page (default 50), newest first, so a single request
// would hide older actors once the registry grows past one page.
export const ACTOR_PAGE_SIZE = 100;
// Defensive bound: 1000 pages × 100 rows. A server that ignored `offset`
// would otherwise repeat the same full page forever.
const MAX_ACTOR_PAGES = 1000;

export async function fetchAllActors(request, pageSize = ACTOR_PAGE_SIZE) {
  const seen = new Set();
  const actors = [];
  for (let page = 0; page < MAX_ACTOR_PAGES; page += 1) {
    const offset = page * pageSize;
    const result = await request(`/api/actors?limit=${pageSize}&offset=${offset}`);
    const rows = Array.isArray(result?.actors) ? result.actors : [];
    let added = 0;
    for (const actor of rows) {
      // Offset pages can shift when actors are created or revoked between
      // requests; list each actor once.
      if (seen.has(actor.id)) continue;
      seen.add(actor.id);
      actors.push(actor);
      added += 1;
    }
    if (rows.length < pageSize || added === 0) break;
  }
  return actors;
}
