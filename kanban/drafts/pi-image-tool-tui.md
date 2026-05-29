---
uuid: "kanban-specs-drafts-pi-image-tool-tui-md"
title: "Spec Draft: Image tool for pi TUI rendering"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:44.567Z"
source: "specs/drafts/pi-image-tool-tui.md"
category: "specs"
---

> Source: `specs/drafts/pi-image-tool-tui.md`
> Migrated-to-kanban: `kanban/drafts/pi-image-tool-tui.md`

# Spec Draft: Image tool for pi TUI rendering

## Summary
Add a custom pi extension under `~/.pi/agent/extensions/` that registers an image-rendering tool. The tool accepts a local file path, URL, or data URL, reads the image, and renders it in the TUI using the built-in image component.

## Open Questions
- None.

## Risk Analysis
- **Large images** could inflate tool payloads; enforce a default size limit and surface clear errors.
- **Terminal support** varies; users may need to enable image rendering in supported terminals (Kitty/iTerm2/Ghostty/WezTerm).

## Priority
Medium.

## Implementation Phases
1. **Extension scaffold**
   - Create `~/.pi/agent/extensions/image-render.ts` and register the tool with schema + description.
2. **Image loading + validation**
   - Read from local file, URL, or data URL.
   - Validate MIME type and enforce size limit.
3. **TUI rendering**
   - Implement tool renderers to display the image in the TUI via `Image` component.
   - Provide a text summary for non-UI modes.

## Affected Files
- `~/.pi/agent/extensions/image-render.ts`
- `specs/drafts/pi-image-tool-tui.md`

## Dependencies
- `@mariozechner/pi-coding-agent`
- `@mariozechner/pi-tui`

## Definition of Done
- Tool appears in pi and can render an image in TUI when given a local file path or URL.
- Non-UI modes return a clear text summary.
- Errors are reported for invalid input or oversized files.

## Todo
- [x] Phase 1: Extension scaffold (Spec section: Implementation Phases #1)
- [x] Phase 2: Image loading + validation (Spec section: Implementation Phases #2)
- [x] Phase 3: TUI rendering (Spec section: Implementation Phases #3)
