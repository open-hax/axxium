---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-02-terminology-md"
title: "Terminology"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.663Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/02-terminology.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/02-terminology.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/02-terminology.md`

# Terminology

- **Gate**: A policy module with a *trigger* and *enforcement* behavior (must/should/must-not).
- **Skill**: A documented workflow for producing a class of results (e.g., “PDF creation”).
- **Tool**: An executable capability available to the assistant at runtime (web.run, python, etc.).
- **Artifact**: A file delivered to the user (zip, pdf, docx, pptx, xlsx).
- **Trigger**: A condition derived from the user request or context (e.g., “mentions prior chats”).
- **Enforcement**: The required action or constraint once a gate triggers.
