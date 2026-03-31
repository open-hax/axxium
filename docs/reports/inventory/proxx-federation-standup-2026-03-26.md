# Proxx Federation Standup Report — 2026-03-26

Date: 2026-03-26
Workspace: `/home/err/devel`

## Constraint honored
- `services/proxx/docker-compose.yml` was **not restarted or shut down**.
- The canonical instance remained live at its existing container/runtime identity.

## What was stood up
The following Proxx-bearing stacks are now running alongside the canonical stack:

| Stack/source | Status | Notes |
| --- | --- | --- |
| `docker-compose.yml` | up | Root workspace Proxx stack started on `18790` |
| `services/proxx/docker-compose.federation.yml` | up | Federation peer running on `18792` |
| `services/proxx/docker-compose.blongs.yml` | up | Blongs stack running on `5277` |
| `services/cephalon-hive/docker-compose.yml` | up | Proxx service recreated and attached to `ai-infra` |
| `orgs/open-hax/proxx/docker-compose.yml` | up | Repo-local Proxx running on `18789`; web port overridden to `25176` at launch because `25174` was already occupied by `proxx-quiet-openai` |
| `orgs/open-hax/proxx/docker-compose.glm5.yml` | up | Repo-local GLM5 variant running on `28791` |
| `orgs/open-hax/proxx/docker-compose.federation-runtime.yml` | up | Four runtime federation REDACTED_SECRETs running on `ai-infra` |
| `orgs/open-hax/proxx/docker-compose.federation-e2e.yml` | up | Four e2e federation REDACTED_SECRETs running on `18891-18894` |
| `orgs/ussyverse/battlebussy/deploy/docker-compose.prod.yml` | up | Bundled OpenAI proxy running on `28889`; required adding a dedicated Postgres sidecar to support federation peer storage |

Not stood up from the earlier manifest because it is currently absent:
- `services/proxx/docker-compose.glm5.yml`

## Additional source changes required for successful standup
- Fixed REDACTED_SECRET `docker-compose.yml` build context to `./orgs/open-hax/proxx`.
- Added federation identity defaults to repo-local stacks.
- Added `host.docker.internal` mapping to federation e2e REDACTED_SECRETs.
- Attached Cephalon Hive Proxx to `ai-infra` with alias `cephalon-hive-proxx`.
- Attached BattleBussy bundled proxy to `ai-infra` with alias `battlebussy-openai-proxy`.
- Added a Postgres service plus `DATABASE_URL` to BattleBussy bundled proxy so `/api/ui/federation/peers` works.

## Canonical federation registrations
The canonical `services/proxx/docker-compose.yml` instance now has peer registrations for these standup IDs:
- `battlebussy`
- `blongs`
- `cephalon-hive`
- `e2e-a1`
- `e2e-a2`
- `e2e-b1`
- `e2e-b2`
- `federation-peer`
- `repo-glm5`
- `repo-proxx`
- `REDACTED_SECRET-proxx`
- `runtime-a1`
- `runtime-a2`
- `runtime-b1`
- `runtime-b2`

Also present on canonical were pre-existing peer IDs discovered during verification:
- `80346cef-f38d-4bd5-adbd-c6807444b924`
- `f848706e-22ae-42a3-a6d2-66f4c097c51c`
- `canonical-proxx-local`

Canonical peer count observed after standup: **18**

## Back-links to canonical
Verified that every newly started peer/REDACTED_SECRET now contains a `canonical-proxx-local` peer registration, including:
- REDACTED_SECRET stack
- services federation peer
- blongs
- cephalon hive proxx
- repo main
- repo glm5
- all four federation runtime REDACTED_SECRETs
- all four federation e2e REDACTED_SECRETs
- battlebussy bundled proxy

## Live containers observed
- `proxx-local-proxx-1` → canonical on `8789`
- `open-hax-openai-proxy-open-hax-openai-proxy-1` → REDACTED_SECRET on `18790`
- `proxx-federation-peer-proxx-federation-peer-1` → federation peer on `18792`
- `proxx-blongs-proxx-blongs-1` → blongs on `5277`
- `cephalon-hive-proxx-1` → cephalon on `18779`
- `proxx-repo-proxx-repo-1` → repo main on `18789`
- `proxx-repo-glm5-proxx-repo-glm5-1` → repo glm5 on `28791`
- `proxx-federation-runtime-federation-proxx-a1-1`
- `proxx-federation-runtime-federation-proxx-a2-1`
- `proxx-federation-runtime-federation-proxx-b1-1`
- `proxx-federation-runtime-federation-proxx-b2-1`
- `proxx-federation-e2e-federation-proxx-a1-1` → `18891`
- `proxx-federation-e2e-federation-proxx-a2-1` → `18892`
- `proxx-federation-e2e-federation-proxx-b1-1` → `18893`
- `proxx-federation-e2e-federation-proxx-b2-1` → `18894`
- `battlebussy-openai-proxy` → `28889`

## Caveats
- The canonical container still has its pre-existing live env/identity because it was intentionally not recreated.
- `orgs/open-hax/proxx/docker-compose.yml` launched with `PROXY_WEB_PORT=25176` due an existing unrelated listener on `25174`.
- Canonical already contained legacy peer rows before this operation.
