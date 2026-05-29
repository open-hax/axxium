---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-07-ui-output-contracts-md"
title: "UI Output Contracts"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.664Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/07-ui-output-contracts.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/07-ui-output-contracts.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/07-ui-output-contracts.md`

# UI Output Contracts

## Writing blocks (email only)
- Use writing blocks only when drafting emails.
- Required fields:
  - `id` (unique 5 digits)
  - `variant="email"`
  - `subject="..."`
- Do not place code in writing blocks; use code fences for code.
- Do not put the subject line in the email body.

## Links to generated artifacts
- When a file is created, provide a clickable link like:
  - `[Download ...](sandbox:/mnt/data/filename.ext)`

## General tone and structure
- Default: natural, direct, helpful collaborator.
- Avoid repeating user wording.
- Prefer precision over breadth.
