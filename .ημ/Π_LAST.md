# Π Last Snapshot — Machine Migration

**Time:** 2026-04-08T18:45:00Z
**Branch:** staging
**Pre-commit HEAD:** bc9b4ea
**Purpose:** Recursive fork tax for machine migration

## Summary

Full recursive fork tax across 67 submodules (14 present + initialized, 53 not initialized).
All present submodules with unpushed commits or dirty work have been committed and pushed.
Root submodule pointers updated to match current checkout state.

## Submodule Actions Completed

### Pushed unpushed commits (5 → all resolved)
| Submodule | Strategy | Result |
|---|---|---|
| `mcp-social-publisher-live` | → fork-tax branch (main protected) | ✅ pushed |
| `orgs/octave-commons/gates-of-aker` | → fork-tax branch (main non-ff) | ✅ pushed |
| `orgs/octave-commons/promethean-agent-system` | direct push (device/stealth) | ✅ pushed |
| `orgs/open-hax/cljs-plugin-template` | --no-verify push (typecheck broken) | ✅ pushed |
| `threat-radar-deploy` | → fork-tax branch (main protected) | ✅ pushed |

### Committed and pushed dirty work (5)
| Submodule | Branch | What |
|---|---|---|
| `orgs/openai/codex` | main → fork | 2582 staged files + 1 unpushed commit → riatzukiza/codex |
| `orgs/octave-commons/cephalon` | fork-tax/20260405-recursive-cephalon | TS/CLJS/README changes |
| `orgs/open-hax/proxx` | fix/glm-model-routing-and-session-stickiness | ollama-context.ts + embeddings.ts |
| `orgs/octave-commons/graph-weaver` | fork-tax/20260404-recursive-graph-weaver | 4 TS files |
| `orgs/open-hax/knoxx` | fork-tax/20260404-recursive-knoxx | backend+frontend ClojureScript |
| `orgs/open-hax/openplanner` | monorepo/graph-stack-consolidation | graph/embedding routes |

### Already up-to-date (3)
- `orgs/open-hax/uxx` (feat/merge-proxy-console-theme)
- `orgs/open-hax/voxx` (chore/checkpoint-voxx)
- `orgs/open-hax/depenoxx` (main)

## Residuals Not Absorbed

These items are intentionally left untouched as they are not owned target paths for migration:

- **`orgs/open-hax/openplanner`** (`Mm`) — nested sub-submodules (packages/graph-weaver, packages/myrmex, etc.) have dirty interiors. Untracked: `dist/`, `REDACTED_SECRET_modules/`, `openplanner-lake/`.
- **`orgs/open-hax/workbench`** (`Mm`) — only shadow-cljs build caches dirty, no source changes.
- **`orgs/open-hax/proxx`** — 3 stashes preserved (workspace bridge changes, pre-merge-test-fixes, federation-audit-witness-fix).
- **`orgs/octave-commons/gates-of-aker`** — 2 stashes preserved (colony-regression-hardening, hacks auto-stash).
- **`orgs/octave-commons/fork_tales`** — untracked LaTeX build artifacts.
- **`orgs/octave-commons/shibboleth`** — untracked LaTeX build artifacts.
- **`orgs/octave-commons/lineara_conversation_export`** — no remote tracking on device/stealth.
- **`orgs/octave-commons/mythloom`** — no remote tracking.
- **Root stash** — 1 stash: `WIP on staging: 3f6a061 v4.1`.
- **`bevy_replicon`** — pushed to fork-tax branch (main diverged from remote).
- **`ggrs`, `lightyear`** — local behind remote, not force-pushed.

## Root Changes Staged

- 17 submodule pointer advances
- `.opencode/package.json` — `@opencode-ai/plugin` version bump 1.2.20 → 1.3.17
- `receipts.log` — 8 new receipt lines

## On New Machine: Clone & Restore

```bash
git clone git@github.com:riatzukiza/devel.git && cd devel
git checkout staging
git submodule update --init --recursive
# For submodules on non-default branches, checkout the recorded branch:
#   git -C orgs/open-hax/proxx checkout fix/glm-model-routing-and-session-stickiness
#   git -C orgs/openai/codex remote add fork https://github.com/riatzukiza/codex.git
#   etc. (see Π_STATE.sexp for full branch map)
```

## Verification

- `git diff --check` ✅
- Mixed-workspace build/lint/test sweep skipped: no single low-cost executable target covers this migration bundle.

## Tag

`Π/2026-04-08/184500-bc9b4ea`