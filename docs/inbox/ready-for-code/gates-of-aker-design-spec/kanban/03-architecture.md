---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-03-architecture-md"
title: "Architecture"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.661Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/03-architecture.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/03-architecture.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/03-architecture.md`

# Architecture

## High-level components
1. **Request Analyzer**
   - extracts intent signals (e.g., “needs latest info”, “email draft”, “create spreadsheet”)
2. **Gate Engine**
   - evaluates gates in priority order and returns constraints + required actions
3. **Planner**
   - produces a step-by-step plan that respects constraints
4. **Executor**
   - invokes tools per the plan
5. **Formatter**
   - formats the final response (incl. UI contracts like writing blocks)
6. **Artifact Packager**
   - writes and packages files for delivery (zip)

## Gate priority model
Gates are ordered by impact and irreversibility:

1. Safety / policy hard constraints
2. Tooling “must do X first” gates (e.g., artifact handoff)
3. Freshness / web-browsing gates
4. Personal context continuity gates
5. Output formatting gates
6. Optimization / style gates

## Data flow
- The Request Analyzer yields a set of **signals**
- The Gate Engine maps signals -> **obligations** and **prohibitions**
- The Planner generates a plan that satisfies them
- The Executor runs the plan and captures tool results
- The Formatter produces the final response and embeds links to artifacts
