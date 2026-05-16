# Π Fork Tax Snapshot — devel root

- Timestamp: 2026-05-16T04:00:00Z
- Branch: pi/fork-tax/2026-04-15-170411
- Base: b7d2b950
- OpenPlanner submodule target: a25ff73f (`pi/fork-tax/20260516-openplanner-a25ff73f`)
- Knoxx nested submodule target: 094273c3 (`pi/fork-tax/20260516-knoxx-094273c3`)
- Scope: OpenPlanner/Proxx compose stabilization, OpenPlanner Kafka profile wiring, and submodule pointer preservation.

## Included work

- OpenPlanner Mongo/graph/API compose resource caps and graph workload throttles.
- OpenPlanner Redpanda/Kafka profile compose slice.
- OpenPlanner compose env passthrough for Kafka publisher settings.
- Proxx compose resource caps.
- Root submodule pin to the pushed OpenPlanner fork-tax commit.
- Root receipts and handoff artifacts.

## Verification

- `docker compose --profile kafka --profile kafka-replay config --quiet` passed from `services/openplanner`.
- OpenPlanner Clojure worker images built.
- Audit consumer is running and logged a heartbeat.
- Replay dry-run processed 5 messages.
- Bounded non-dry-run replay `[0,1)` processed 1 event twice without duplicate-key failure.
- OpenPlanner `/v1/health` and `/v1/metrics` Kafka checks passed.
- Earlier service health checks covered OpenPlanner, Knoxx, Proxx, and graph-weaver after controlled restarts/recreates.

## Residual dirt intentionally not absorbed

The root repo had substantial pre-existing staged/unstaged work outside this fork-tax scope. It was left untouched and not included by the path-scoped commit:

- staged `.gitmodules`
- unstaged `AGENTS.md`
- many staged `Blaze/responses/*` JSON artifacts
- many staged `Graphics/**` reorganizations/additions
- staged `Lore/fork-tales/**` additions
- other non-target root work shown by `git status`

No destructive cleanup, blanket reset, blanket restore, or blanket add was performed.
