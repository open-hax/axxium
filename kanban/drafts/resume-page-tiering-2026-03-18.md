---
uuid: "kanban-specs-drafts-resume-page-tiering-2026-03-18-md"
title: "Resume Page Tiering Draft — 2026-03-18"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:44.550Z"
source: "specs/drafts/resume-page-tiering-2026-03-18.md"
category: "specs"
---

> Source: `specs/drafts/resume-page-tiering-2026-03-18.md`
> Migrated-to-kanban: `kanban/drafts/resume-page-tiering-2026-03-18.md`

# Resume Page Tiering Draft — 2026-03-18

## Goal
Keep richer 2-page resume variants where they add value, while also producing compact 1-page variants for roles that need tighter submission-ready forms.

## Scope
- Preserve current 2-page richer variants
- Create explicit 1-page compact variants for ML+OSS and DevSecOps+AI
- Keep wording truthful and ATS-friendly
- Verify resulting PDFs are 1 page

## Risks
- Over-compressing could remove useful differentiation
- New naming must remain clear enough to avoid confusion with ATS/fnord dimensions

## Implementation
1. Create new compact source variants
2. Build PDFs
3. Verify page counts
4. Optionally run parser sanity check later

## Affected Files
- `resume/aaron-beavers-ml-oss-1p.*`
- `resume/aaron-beavers-devsecops-ai-1p.*`

## Definition of Done
- New compact variants exist
- Both build successfully
- Both are 1 page
