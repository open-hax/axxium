# Π fork tax — Knoxx host services decoupling — 20260526T204054Z

- Timestamp: 20260526T204054Z
- Root repo: `/home/err/devel`
- Scope: path-scoped preservation of Knoxx host PM2 migration into `services/openplanner`.
- Mode: bottom-up nested submodule commit, non-destructive concurrent-agent staging.

## Preserved child refs

- Knoxx source repo: `c066cf0b206961ef70d46bd869b7db006b314d6f` (`c066cf0b2069`)
  - Branch: `pi/fork-tax/20260526T204054Z-knoxx-host-services`
  - Tag: `pi/fork-tax/20260526T204054Z/knoxx-host-services`
  - Remote: `git@github.com:open-hax/knoxx.git`
- OpenPlanner parent repo: `56d6effee7a8d97a8ddde030f381b12bd3c52ebb` (`56d6effee7a8`)
  - Branch: `pi/fork-tax/20260526T204054Z-openplanner-knoxx-host-services`
  - Tag: `pi/fork-tax/20260526T204054Z/openplanner-knoxx-host-services`
  - Remote: `git@github.com:open-hax/openplanner.git`

## Root paths staged for final snapshot

- `services/openplanner/ecosystem.host.config.cjs` — service-owned PM2 ecosystem for Knoxx host services.
- `services/openplanner/README.md` — operator docs for the service-owned PM2 path.
- `orgs/open-hax/openplanner` — submodule pointer advanced to the OpenPlanner commit above.
- `receipts.edn` and `orgs/open-hax/openplanner/packages/agents/knoxx/receipts.edn` — receipt-river evidence.
- `.ημ/Π_LAST.md`, `.ημ/Π_STATE.sexp`, `.ημ/Π_MANIFEST.sha256` — handoff artifacts.

## Verification

- `REDACTED_SECRET -c services/openplanner/ecosystem.host.config.cjs`
- `REDACTED_SECRET -c orgs/open-hax/openplanner/packages/agents/knoxx/ecosystem.config.cjs`
- `pnpm -C orgs/open-hax/openplanner/packages/agents/knoxx/backend exec shadow-cljs compile test` — 396 tests, 1111 assertions, 0 failures, 0 errors.
- `pnpm -C orgs/open-hax/openplanner/packages/agents/knoxx/backend exec shadow-cljs compile server` — 0 warnings.
- `pnpm -C orgs/open-hax/openplanner/packages/agents/knoxx/frontend typecheck`
- `pnpm -C orgs/open-hax/openplanner/packages/agents/knoxx/frontend test -- BroadcastStudioPage.test.tsx CmsPage.test.tsx` — 47 files, 213 passed, 41 todo.
- `cd orgs/open-hax/openplanner/packages/agents/knoxx/ingestion && clojure -M:test` — 45 tests, 189 assertions, 0 failures, 0 errors.
- PM2 migration: old Knoxx/Shoedelussy entries deleted and restarted from `services/openplanner/ecosystem.host.config.cjs`; `pm2 save` completed.
- Authenticated `GET http://127.0.0.1:8000/health` returned `status=ok`.
- Hardcoded-source check: no `/home/err/devel`, `/app/workspace/devel`, `devel workspace`, or `devel corpus` in service ecosystem or Knoxx runtime source scope.
- `git diff --cached --check` and high-risk secret heuristic scans passed for child commits; REDACTED_SECRET staged checks run before final commit.

## Concurrent dirt intentionally left untouched

- Existing unrelated REDACTED_SECRET workspace dirt is not staged or cleaned.
- This fork tax stages only the Knoxx/OpenPlanner/service-owned PM2 migration paths listed above.
- Historical broad-deletion/REDACTED_SECRET clutter described in the previous recursive fork-tax artifacts remains outside this path-scoped snapshot unless explicitly requested.

## Root finalization

- Planned REDACTED_SECRET branch: `pi/fork-tax/20260526T204054Z-knoxx-host-services`
- Planned REDACTED_SECRET tag: `pi/fork-tax/20260526T204054Z/devel-knoxx-host-services`
- Root commit: pending at artifact render time; final tag points to the committed snapshot.
