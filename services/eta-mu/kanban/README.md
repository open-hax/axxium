# eta-mu-kanban

PM2 service for the multi-project eta-mu kanban web UI.

## What it does

Runs the `@openhax/kanban-legacy` web UI with a project dropdown backed by
`services/eta-mu/kanban/openhax.kanban.json`.

The first project is the existing Knoxx board; the rest are spec/specs folders
migrated into sibling `kanban/` directories. Each board remains a markdown source
of truth and should be manipulated with the eta-mu beta CLI.

## Start

```bash
cd services/eta-mu/kanban
pm2 start ecosystem.config.cjs
```

## Stop

```bash
pm2 stop eta-mu-kanban
```

## Access

http://127.0.0.1:8791

## Config

- Multi-project config: `services/eta-mu/kanban/openhax.kanban.json`
- Migration manifest: `services/eta-mu/kanban/spec-migration-manifest.json`
- Skipped/generated/test-source report: `services/eta-mu/kanban/spec-migration-skips.json`
- Migration helper: `services/eta-mu/kanban/scripts/migrate-specs-to-kanban.mjs`

## Managing boards

Use the kanban-capable beta CLI and point `--tasks-dir` at the specific board
folder listed in `openhax.kanban.json`:

```bash
eta-mu-beta kanban count --tasks-dir orgs/open-hax/openplanner/packages/agents/knoxx/kanban
eta-mu-beta kanban list --tasks-dir kanban
eta-mu-beta kanban update-status <uuid> in_progress --tasks-dir <board-dir>
eta-mu-beta kanban comment <uuid> "progress note" --tasks-dir <board-dir>
```

## Environment

| Variable | Default | Description |
|----------|---------|-------------|
| `KANBAN_HOST` | `127.0.0.1` | Bind address |
| `KANBAN_PORT` | `8791` | Bind port |
