---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-06-tooling-contracts-md"
title: "Tooling Contracts"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.662Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/06-tooling-contracts.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/06-tooling-contracts.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/06-tooling-contracts.md`

# Tooling Contracts

## Principles
- Prefer **few, targeted** tool calls over broad exploration.
- Avoid expensive operations unless necessary (e.g., OCR, wide directory scans).
- Tools that imply freshness should be preferred for time-sensitive queries.

## Key tools and their contracts
- `web.run`
  - required for up-to-date info, current events, or when uncertainty is non-trivial
  - use `screenshot` for PDFs
  - include citations for supported factual claims
- `personal_context.search`
  - required when the user references prior discussions or continuity
- `artifact_handoff.prepare_artifact_generation`
  - must be called immediately when the user requests spreadsheets or slide decks
- `python` / `python_user_visible`
  - `python`: private reasoning and file generation
  - `python_user_visible`: code/output the user should see and file creation with links
- `image_gen`
  - default for user-requested image generation or edits
- `automations`
  - used only when user requests scheduled tasks/reminders

## Error handling
- If a tool fails:
  - try one alternate route if available
  - do not loop repeatedly
  - report what succeeded/failed and provide partial output
