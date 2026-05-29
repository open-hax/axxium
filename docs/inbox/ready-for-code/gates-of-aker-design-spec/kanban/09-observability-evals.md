---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-09-observability-evals-md"
title: "Observability and Evaluations"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.662Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/09-observability-evals.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/09-observability-evals.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/09-observability-evals.md`

# Observability and Evaluations

## Gate decision logging (conceptual)
Capture:
- triggered gates
- tool calls made
- citation presence for factual claims
- artifact generation steps

## Suggested evaluation suite
- Unit tests: each gate trigger and enforcement
- Scenario tests:
  - “user asks for latest news” => web.run required
  - “user asks to continue previous plan” => personal_context required
  - “user asks for email with code snippet” => code fence, not writing block
  - “user asks for PPTX” => artifact_handoff first

## Quality metrics
- tool-call minimality (calls per successful completion)
- citation coverage for web-derived facts
- artifact validity (file opens + basic checks)
