---
uuid: "kanban-specs-drafts-add-agustif-codex-linux-submodule-md"
title: "Add agustif/codex-linux as a git submodule"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:44.542Z"
source: "specs/drafts/add-agustif-codex-linux-submodule.md"
category: "specs"
---

> Source: `specs/drafts/add-agustif-codex-linux-submodule.md`
> Migrated-to-kanban: `kanban/drafts/add-agustif-codex-linux-submodule.md`

# Add agustif/codex-linux as a git submodule

## Summary
Add `git@github.com:agustif/codex-linux.git` to this monorepo under `orgs/agustif/codex-linux` as a git submodule.

## Priority
Low (workspace organization / dependency wiring)

## Open questions
- None.

## Risks
- SSH access required for `git@github.com:...` URL.
- Superproject may already have uncommitted changes in other submodules; ensure the commit for this change does **not** accidentally update other submodule pointers.

## Implementation phases
### Phase 1 — Add submodule (this change)
- Create directory `orgs/agustif/` (if missing)
- `git submodule add -b main git@github.com:agustif/codex-linux.git orgs/agustif/codex-linux`
- Verify `.gitmodules` updated and submodule clones cleanly

## Affected files
- `.gitmodules`
- `orgs/agustif/codex-linux` (gitlink entry)

## Dependencies
- `git` with submodule support
- GitHub SSH key configured

## Definition of done
- `git submodule status orgs/agustif/codex-linux` succeeds
- `.gitmodules` contains an entry for `orgs/agustif/codex-linux` with the correct URL and `branch = main`
