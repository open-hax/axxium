// Pure helpers for the identity manager (tested in scripts/identity-tree.test.mjs).

// Flat actor list -> depth-first rows with depth, rooted at actors whose
// parent is not visible (the signed-in actor).
export function treeRows(actors) {
  const byParent = new Map();
  const dids = new Set(actors.map(a => a.did));
  for (const a of actors) {
    const key = dids.has(a.parent) ? a.parent : null;
    if (!byParent.has(key)) byParent.set(key, []);
    byParent.get(key).push(a);
  }
  const rows = [];
  const walk = (parent, depth) => {
    for (const a of (byParent.get(parent) || []).sort((x, y) => x.did.localeCompare(y.did))) {
      rows.push({ ...a, depth });
      walk(a.did, depth + 1);
    }
  };
  walk(null, 0);
  return rows;
}

const list = s => String(s || '').split(/[\s,]+/).map(x => x.trim()).filter(Boolean);

// "contents=write, issues=read" -> {contents:"write", issues:"read"}
export function parsePermissions(text) {
  const out = {};
  for (const pair of list(text)) {
    const [k, v] = pair.split('=');
    if (k && v) out[k] = v;
  }
  return out;
}

export function formatPermissions(perms) {
  return Object.entries(perms || {}).map(([k, v]) => `${k}=${v}`).join(', ');
}

// Form fields -> policy JSON for POST /api/identity/admin.
export function policyFromFields({ repos, permissions, actions, forbid }) {
  return { repos: list(repos), permissions: parsePermissions(permissions),
           actions: list(actions), forbid: list(forbid) };
}

// A child form starts from the parent's policy (G1: never wider).
export function fieldsFromPolicy(p) {
  p = p || {};
  return { repos: (p.repos || []).join(', '), permissions: formatPermissions(p.permissions),
           actions: (p.actions || []).join(', '), forbid: (p.forbid || []).join(', ') };
}

