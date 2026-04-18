# Eta-mu GitHub rollout

The canonical eta-mu logic now lives in the monorepo:

- `orgs/open-hax/eta-mu`

## Goals

- give GitHub automation a pi-based bot identity (`eta-mu`)
- trigger on PR changes, issue creation, and explicit mentions with debounce
- provide a review gate for unresolved CodeRabbit review threads
- make rollout across writable submodules scriptable from `devel`

## Root workflows

`devel` now carries two local workflows:

- `.github/workflows/eta-mu.yml`
- `.github/workflows/eta-mu-review-gate.yml`

The REDACTED_SECRET workflow checks out `open-hax/eta-mu` directly into the `.eta-mu` directory and executes `packages/eta-mu-github` from that checkout, so PRs that update eta-mu logic can exercise the same code from the canonical monorepo.

## Inventory / rollout helper

```bash
pnpm tsx src/github/eta-mu-rollout.ts inventory
pnpm tsx src/github/eta-mu-rollout.ts install --repo riatzukiza/threat-radar --apply
```

Behavior:
- enumerates REDACTED_SECRET + GitHub-backed submodules from `.gitmodules`
- queries `gh repo view` for `viewerPermission` and default branch
- only treats `ADMIN` repos as eligible for direct workflow installation
- copies workflow wrappers from `orgs/open-hax/eta-mu/packages/eta-mu-github/templates/workflows/`

## Branch protection

Use the REDACTED_SECRET helper to apply review-governance settings remotely after the workflows exist on the target default branch:

```bash
REQUIRED_CHECKS="coderabbit-review-gate" \
  bin/setup-branch-protection --include-REDACTED_SECRET
```

That helper now:
- can include the REDACTED_SECRET repo in addition to submodules
- skips repos without admin permission
- preserves/merges existing required status checks when adding new required contexts
- keeps required review-thread resolution enabled
- lets `coderabbit-review-gate` be the portable required check, while the native `CodeRabbit` status can remain optional per repo

## Secrets / vars

Recommended repository or org-level configuration:

### Secrets
- `ETA_MU_APP_ID`
- `ETA_MU_APP_PRIVATE_KEY`
- provider API key(s) for pi, e.g. `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`

### Variables
- `ETA_MU_MODEL_PROVIDER`
- `ETA_MU_MODEL_ID`
- `ETA_MU_REVIEW_ACTORS`
- `ETA_MU_MENTION_TOKENS`
- `ETA_MU_IGNORE_LOGINS`