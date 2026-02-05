---
uuid: 62dd6133-a573-4824-b7dc-ab917788561b
title: "Promethean OS module registry spec"
slug: promethean-os-module-registry
status: incoming
priority: P2
tags: []
created_at: "2026-02-03T06:36:00.409448Z"
estimates:
  complexity: ''
  scale: ''
  time_to_completion: ''
storyPoints: null
---
# Promethean OS module registry spec

## Scope
Target repository: `/home/err/devel/promethean`

## Intent
Reframe Promethean as an OS-level orchestrator and document modules as OS components (kernel, services, tools, libraries, pipelines), with migration guidance toward Clojure-first infrastructure.

## Requirements
1. Build a module registry covering all workspace REDACTED_SECRETs in `pnpm-workspace.yaml`.
2. Classify each module by type: kernel/glue, service, library, pipeline, CLI, tool, experimental, legacy.
3. Capture key interfaces for each module (CLI, HTTP, stdio, config files, shared packages).
4. Mark consolidation targets (duplicate MCP stacks, overlapping tooling, deprecated paths).
5. Provide migration notes toward Clojure-first implementations where applicable.

## Inputs
- `pnpm-workspace.yaml` for workspace boundaries.
- `nx.json` for project layout/targets.
- Root `package.json` scripts for orchestration layer.
- `README.md` for declared system goals.
- `services/mcp/` and `docs/design/pantheon/` for MCP and agent OS design history.

## Module registry (draft structure)
Each entry should include:
- `name`
- `path`
- `category`
- `runtime` (Clojure, TypeScript/Node, mixed)
- `interfaces` (CLI/HTTP/stdio/config/etc.)
- `dependencies` (workspace packages or external services)
- `role` (OS kernel/glue vs capability library)
- `status` (core, active, experimental, legacy)
- `migration_notes` (Clojure consolidation target or leave as adapter)

## Module registry (initial pass)
Notes:
- `runtime` and `interfaces` are inferred from package scripts and bin flags; verify before acting on them.
- Stryker temp directories, worktrees, and test-docs are excluded.

| name | path | category | runtime | interfaces | status |
| --- | --- | --- | --- | --- | --- |
| .opencode | .opencode | .opencode | REDACTED_SECRET/ts | lib | active |
| @promethean-os/apply-patch | cli/apply-patch | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/compiler | cli/compiler | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/docs-cli | cli/docs | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/ecosystem-dsl | cli/ecosystem-dsl | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/kanban | cli/kanban | cli | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/kanban-plugin-content | cli/kanban/packages/kanban-plugin-content | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/kanban-plugin-git-index | cli/kanban/packages/kanban-plugin-git-index | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/kanban-plugin-heal | cli/kanban/packages/kanban-plugin-heal | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/kanban-sdk | cli/kanban/packages/kanban-sdk | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/kanban-transition-rules | cli/kanban/packages/kanban-transition-rules | cli | REDACTED_SECRET/ts | build | active |
| @promethean/obsidian-export | cli/obsidian-export | cli | REDACTED_SECRET/ts | build | active |
| @promethean-os/ai-learning | experimental/ai-learning | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/auth-service | experimental/auth-service | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/cephalon | experimental/cephalon | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/compliance-monitor | experimental/compliance-monitor | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/docs-system | experimental/docs-system | experimental | REDACTED_SECRET/ts | build+cli+service | experimental |
| @promethean-os/eidolon-field | experimental/eidolon-field | experimental | REDACTED_SECRET/ts | service | experimental |
| @promethean-os/embedding-cache | experimental/embedding-cache | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/enso-agent-communication | experimental/enso-agent-communication | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/enso-browser-gateway | experimental/enso-browser-gateway | experimental | REDACTED_SECRET/ts | service | experimental |
| @promethean-os/enso-protocol | experimental/enso-protocol | experimental | REDACTED_SECRET/ts | build+service | experimental |
| heartbeat-service | experimental/heartbeat | experimental | REDACTED_SECRET/ts | service | experimental |
| @promethean-os/llm | experimental/llm | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/omni-tools | experimental/omni-tools | experimental | mixed | build+cli+service | experimental |
| @promethean-os/pantheon | experimental/pantheon | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-coordination | experimental/pantheon/packages/coordination | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-core | experimental/pantheon/packages/core | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-ecs | experimental/pantheon/packages/ecs | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/pantheon-generator | experimental/pantheon/packages/generator | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-llm-claude | experimental/pantheon/packages/llm-claude | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-llm-openai | experimental/pantheon/packages/llm-openai | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-mcp | experimental/pantheon/packages/mcp | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/pantheon-orchestrator | experimental/pantheon/packages/orchestrator | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-persistence | experimental/pantheon/packages/persistence | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-protocol | experimental/pantheon/packages/protocol | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-state | experimental/pantheon/packages/state | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-ui | experimental/pantheon/packages/ui | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/pantheon-workflow | experimental/pantheon/packages/workflow | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/pipeline-automation | experimental/pipeline-automation | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/plugin-hooks | experimental/plugin-hooks | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/scar | experimental/scar | experimental | REDACTED_SECRET/ts | build+service | experimental |
| @promethean-os/voice-service | experimental/voice | experimental | REDACTED_SECRET/ts | build | experimental |
| @promethean-os/benchmark | packages/benchmark | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/broker | packages/broker | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/discord | packages/discord | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/ds | packages/ds | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/duck-audio | packages/duck-audio | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/effects | packages/effects | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/embedding | packages/embedding | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/event | packages/event | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/file-indexer | packages/file-indexer | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/frontend | packages/frontend | packages | mixed | build+service | active |
| @promethean-os/fs | packages/fs | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/fsm | packages/fsm | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/github-sync | packages/github-sync | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/http | packages/http | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/legacy | packages/legacy | packages | REDACTED_SECRET/ts | build | legacy |
| @promethean-os/level-cache | packages/level-cache | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/lmdb-cache | packages/lmdb-cache | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/logger | packages/logger | packages | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/markdown | packages/markdown | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/math-utils | packages/math-utils | packages | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/messaging | packages/messaging | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/migrations | packages/migrations | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/ollama-queue | packages/ollama-queue | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/pantheon-ecs | packages/pantheon-ecs | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/persistence | packages/persistence | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/platform | packages/platform | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/pm2-helpers | packages/pm2-helpers | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/providers | packages/providers | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/report-forge | packages/report-forge | packages | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/security | packages/security | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/test-utils | packages/test-utils | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/trello | packages/trello | packages | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/utils | packages/utils | packages | REDACTED_SECRET/ts | build | active |
| @promethean-os/boardrev | pipelines/boardrev | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/buildfix | pipelines/buildfix | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/codemods | pipelines/codemods | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/codepack | pipelines/codepack | pipelines | REDACTED_SECRET/ts | build | active |
| @promethean-os/cookbookflow | pipelines/cookbookflow | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/pipeline-core | pipelines/core | pipelines | REDACTED_SECRET/ts | build | active |
| @promethean-os/docops | pipelines/docops | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/lint-taskgen | pipelines/lint-taskgen | pipelines | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/piper | pipelines/piper | pipelines | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/readmeflow | pipelines/readmeflow | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/semverguard | pipelines/semverguard | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/simtasks | pipelines/simtask | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/sonarflow | pipelines/sonarflow | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/symdocs | pipelines/symdocs | pipelines | REDACTED_SECRET/ts | build+cli | active |
| @promethean-os/testgap | pipelines/testgap | pipelines | REDACTED_SECRET/ts | build+cli | active |
| promethean | . | REDACTED_SECRET | mixed | build+cli+service | active |
| @promethean/auto-run-scripts | scripts | scripts | REDACTED_SECRET/ts | lib | active |
| @promethean-os/autocommit | services/autocommit | services | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/file-indexer-service | services/file-indexer-service | services | REDACTED_SECRET/ts | build | active |
| @promethean-os/frontend-service | services/frontend-service | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/knowledge-graph | services/knowledge-graph | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/knowledge-graph-domain | services/knowledge-graph/packages/knowledge-graph-domain | services | REDACTED_SECRET/ts | build | active |
| @promethean-os/knowledge-graph-simulation | services/knowledge-graph/packages/knowledge-graph-simulation | services | REDACTED_SECRET/ts | build | active |
| @promethean-os/knowledge-graph-storage | services/knowledge-graph/packages/knowledge-graph-storage | services | REDACTED_SECRET/ts | build | active |
| @promethean-os/knowledge-graph-ui | services/knowledge-graph/packages/knowledge-graph-ui | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/mcp | services/mcp | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/mcp-dev-ui-frontend | services/mcp-dev-ui-frontend | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/mcp-express-server | services/mcp-express-server | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/mcp-kanban-bridge | services/mcp-kanban-bridge | services | REDACTED_SECRET/ts | build+cli+service | active |
| @promethean-os/openai-server | services/openai-server | services | REDACTED_SECRET/ts | build | active |
| @promethean-os/sentinel | services/sentinel | services | REDACTED_SECRET/ts | build+service | active |
| @promethean-os/tools | tools | tools | REDACTED_SECRET/ts | lib | active |

## Initial category map (non-exhaustive)
### Kernel / glue
- Root orchestrator scripts (`package.json`, `scripts/`, `tools/`)
- Pipelines (automation/scheduler layer)

### Services
- `services/sentinel`
- `services/openai-server`
- `services/frontend-service`
- `services/file-indexer-service`
- `services/mcp-kanban-bridge`
- `services/mcp-dev-ui-frontend`
- `services/mcp` (legacy MCP stack; consolidation target)

### Libraries
- `packages/*` (utils, fs, messaging, security, persistence, caching, etc.)

### CLI
- `cli/kanban` + subpackages
- `cli/apply-patch`, `cli/docs`, `cli/compiler`, `cli/ecosystem-dsl`, `cli/obsidian-export`

### Pipelines
- `pipelines/*` (buildfix, codemods, docops, lint-taskgen, piper, readmeflow, semverguard, simtask, sonarflow, symdocs, testgap, etc.)

### Experimental
- `experimental/pantheon` (agent OS concepts + MCP overlap)
- `experimental/llm`, `experimental/pipeline-automation`, `experimental/scar`, `experimental/enso-*`, `experimental/voice`, `experimental/ai-learning`, etc.

## Consolidation targets
- MCP: unify `services/mcp` with `experimental/pantheon/packages/mcp` under the Clojure agent system.
- Agent OS: treat pantheon docs as canonical and migrate older TS implementations into Clojure agents/tools.
- Pipelines: align automation with agent-driven workflows.

## Migration principles
1. New core logic goes into Clojure agent system first; TS remains as adapters.
2. Deprecate duplicated services when a Clojure equivalent exists and is stable.
3. Keep Promethean OS responsible for orchestration, policy, configuration, and integration.

## Definition of done
- A populated registry table exists with all workspace modules and categories.
- Consolidation targets and migration notes are identified.
- Open questions and risks are captured for follow-up.
