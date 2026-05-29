# Π Last Handoff — eta-mu multi-project kanban service

- timestamp: 20260529T043750Z
- branch: pi/fork-tax/20260529T022118Z-main-softreset-all-dirt
- scope: services/eta-mu/kanban multi-project config plus REDACTED_SECRET specs-to-kanban migration and eta-mu submodule pointer
- verification:
  - pnpm -C orgs/open-hax/eta-mu/packages/kanban test
  - pnpm -C orgs/open-hax/eta-mu/packages/kanban build
  - pnpm -C orgs/open-hax/eta-mu/packages/coding-agent build
  - eta-mu-beta kanban serve --config services/eta-mu/kanban/openhax.kanban.json --host 127.0.0.1 --port 8899 + /api/projects and /api/board?project=knoxx smoke
  - eta-mu-beta kanban count for REDACTED_SECRET, docs inbox, host-fleet-dashboard, fork-tales-site, and eta-mu boards
- migrated projects: see services/eta-mu/kanban/spec-migration-manifest.json and service config with 75 projects
- skipped generated/test/archive/reference/source-copy dirs: see services/eta-mu/kanban/spec-migration-skips.json
- concurrent dirt: multiple submodule pointers and unrelated generated/log files remain unstaged by design.
