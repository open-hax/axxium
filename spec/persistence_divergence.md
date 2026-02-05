---
uuid: fd0f0e6a-5c3b-4e39-876e-7a249764c46b
title: "Persistence Package Divergence Analysis"
slug: persistence_divergence
status: todo
priority: P2
tags: [persistence, duplication, consolidation]
created_at: 2026-02-04
estimates:
  complexity: ''
  scale: ''
  time_to_completion: ''
storyPoints: null
---
# Persistence Package Divergence Analysis

## Summary
The workspace contains multiple `packages/persistence` directories across submodules. A diff shows that the Promethean submodule copy is effectively identical to the REDACTED_SECRET package (aside from build artifacts), while the Pantheon copies are distinct adapter packages and should remain separate.

## Locations

### Canonical candidate
- `/home/err/devel/packages/persistence`

### Duplicated copies
- `/home/err/devel/orgs/riatzukiza/promethean/packages/persistence`
  - Same package name and dependencies.
  - Diff shows only build artifacts and metadata differences (`dist/`, `REDACTED_SECRET_modules/`, `.git/`, `pnpm-lock.yaml`, `tsconfig.tsbuildinfo`, newline at EOF in `package.json`).

### Distinct adapter packages (not duplicates)
- `/home/err/devel/orgs/octave-commons/pantheon/packages/persistence`
- `/home/err/devel/orgs/riatzukiza/promethean/experimental/pantheon/packages/persistence`
  - These are `@promethean-os/pantheon-persistence` adapters wrapping the canonical package.
  - Different package name, scripts, and a large adapter implementation (`src/index.ts` is a Pantheon ContextPort adapter).

## Evidence

- `package.json` diff (REDACTED_SECRET vs Promethean): only newline at EOF.
- `package.json` diff (REDACTED_SECRET vs Pantheon): package name, scripts, dependencies, and exports differ.
- `src/index.ts` diff (REDACTED_SECRET vs Pantheon): Pantheon adapter implementation replaces REDACTED_SECRET exports.

## Risks
- Confusion over which persistence package is canonical.
- Submodule copies can drift if patched independently.
- Build artifacts stored in the submodule copy create noise and obscure real diffs.

## Recommended Direction

1. **Declare canonical source**: `/home/err/devel/packages/persistence`.
2. **Submodule alignment**: If Promethean needs the package, use workspace dependency or explicitly sync from the REDACTED_SECRET package, rather than maintaining a full duplicate.
3. **Pantheon adapters stay separate**: treat them as adapters and keep their own package names (`@promethean-os/pantheon-persistence`).

## Next Steps

1. Confirm whether Promethean submodule should drop its duplicate copy or treat REDACTED_SECRET as upstream.
2. If deletion is acceptable, remove the duplicate and update any relative imports in the submodule to use workspace deps.
3. If deletion is not acceptable, enforce sync automation (e.g., mirror changes or lock to REDACTED_SECRET version).
