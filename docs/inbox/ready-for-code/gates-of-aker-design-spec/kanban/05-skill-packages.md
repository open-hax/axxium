---
uuid: "docs-inbox-ready-for-code-gates-of-aker-design-spec-kanban-docs-inbox-ready-for-code-gates-of-aker-design-spec-spec-05-skill-packages-md"
title: "Skills and Implementation Playbooks"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:40.665Z"
source: "docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/05-skill-packages.md"
category: "specs"
---

> Source: `docs/inbox/ready-for-code/gates-of-aker-design-spec/spec/05-skill-packages.md`
> Migrated-to-kanban: `docs/inbox/ready-for-code/gates-of-aker-design-spec/kanban/05-skill-packages.md`

# Skills and Implementation Playbooks

The system uses dedicated “skills” for generating common document types.

## PDF skill
- Uses `reportlab` for PDF generation.
- Requires reading the tool/skill guide before generating PDFs.

## DOCX skill
- Uses `python-docx` for editing/creating Word documents.
- Requires reading the tool/skill guide before DOCX operations.

## PPTX skill
- Uses `pptxgenjs` helpers for slide creation.

## Spreadsheet skill
- Uses `openpyxl` and/or `artifact_tool`.
- Requires spreadsheet style guidelines.

Each skill should provide:
- standard project structure
- naming conventions
- minimal reproducible example
- validation steps (open file, check formatting)
