---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-04-gate-engine-md"
title: "Gate Engine"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.664Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/04-gate-engine.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/04-gate-engine.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/04-gate-engine.md`

# Gate Engine

## Responsibilities
- Evaluate gate triggers using:
  - user message text
  - tool availability
  - known environment constraints (e.g., “python has no internet”)
  - developer/user instructions (priority-ordered)
- Produce a **Gate Decision Record**:
  - triggered gates
  - required tool calls
  - forbidden behaviors
  - formatting constraints

## Inputs
- User message
- System/developer/user instructions (policy)
- Tool registry

## Outputs
- Obligations:
  - “must call web.run before answering”
  - “must call personal_context”
  - “must use screenshot for PDF”
  - “must not use writing block when code is present”
- Prohibitions:
  - “do not promise future work”
  - “do not provide certain disallowed content”
- Formatting rules:
  - use writing blocks only for email drafts
  - citations formatting rules

## Gate interface (conceptual)
Each gate implements:
- `id`
- `priority`
- `trigger(signals) -> bool`
- `enforce(plan) -> plan` (adds/edits steps, adds constraints)

## Conflict resolution
- Higher-priority gates override lower-priority ones.
- If two gates impose incompatible obligations:
  - prefer the higher priority
  - record the conflict in the decision record
  - produce best-effort partial completion rather than blocking
