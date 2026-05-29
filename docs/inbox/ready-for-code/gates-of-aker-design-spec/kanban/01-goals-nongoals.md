---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-01-goals-nongoals-md"
title: "Goals and Non-goals"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.664Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/01-goals-nongoals.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/01-goals-nongoals.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/01-goals-nongoals.md`

# Goals and Non-goals

## Goals
- Deterministic, auditable decisioning for:
  - web browsing and citations
  - personal context retrieval
  - artifact generation workflows
  - safety / trust rules and refusal behavior
  - output formatting conventions
- “Small, targeted tool calls” and minimal exploration by default.
- Clear extension path: new gates can be added without breaking existing ones.

## Non-goals
- Replacing the underlying model with a rules engine.
- Perfect automation of all product requirements not present in the current context.
- Any privileged access to external services (e.g., private repos) unless explicitly provided.
