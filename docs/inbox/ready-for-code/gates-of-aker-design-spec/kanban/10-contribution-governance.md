---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-10-contribution-governance-md"
title: "Contribution and Governance"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.662Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/10-contribution-governance.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/10-contribution-governance.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/10-contribution-governance.md`

# Contribution and Governance

## Adding a new gate
1. Create a gate feature spec in `gates/`
2. Add an ADR if it changes architecture or policy priority
3. Add tests to the evaluation suite
4. Update `gates/index.md`

## Versioning
- Semantic versioning for gate catalog:
  - MAJOR: behavior changes that affect outputs
  - MINOR: new gate additions (backward compatible)
  - PATCH: clarifications / non-behavior changes
