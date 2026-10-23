# Wright source handoff: scoped Axxium machine relay

Source changes are implemented in the two owned relay worktrees. Required final
acceptance remains **blocked** by sandbox socket restrictions and inherited lint/CLJS
test compile warnings. No runtime acceptance is claimed. No commits, pushes, PRs,
SSH, Docker, live databases, credentials, board changes, cross-thread messages,
canonical ledger writes, or outside-worktree source edits were performed.

## Implementation

Authority:

- `src/cljs/axxium/law/relay.cljc`: closed operator grants, exact model-name
  policy, explicit finite expiry with maximum 30 day issuance horizon, active
  existing actor admission, fixed relay claims and <=25 second lease lifetime.
- `src/cljs/axxium/law/oidc.cljc`: optional node grants distinct from browser
  clients, unique node IDs/secrets, no node/browser shared secrets.
- `src/cljs/axxium/extern/oidc_http.cljs`: descriptor-based bounded private
  config reads, regular mode0600 file, no symlink following; mount machine route
  through the existing production actor lookup composition.
- `src/cljs/axxium/extern/relay.cljs`: constant-time digest comparison, HTTPS
  admission, current file read on every call, startup signing-config consistency,
  existing persistent RS256 key, fixed scope/type, generic no-store refusals.
- `src/cljs/axxium/law/relay_test.cljs`, `scripts/relay.integration.mjs`, and two
  package test scripts. No new authority dependency.
- `docs/relay-node-grants.md`: authorization source, renewal, revocation and limits.

Proxx:

- `src/proxx/auth/relay_law.cljc`: pure claims, hello, model/RPC and qualified
  owner routing contracts. `src/proxx/extern/relay.cljs`: JOSE/HTTP/native boundary.
- `src/proxx/runtime.cljs`, `shadow-cljs.edn`, `src/lib/cljs-runtime.ts`: runtime
  exports and typed wire contracts. Semantic admission remains in CLJC/CLJS.
- `src/lib/db/schema.ts`: additive schema version 9;
  `src/lib/db/sql-federation-store.ts`: unique issuer+jti atomic INSERT and
  conservative expiry cleanup, without a memory-only fallback.
- `src/routes/api/ui/ws.ts`: scoped identity only on the existing bridge websocket
  path. Native auth remains available; browser enrollment/upgrade refusal remains.
- `src/lib/federation/bridge-protocol.ts`, `bridge-relay.ts`, `bridge-agent.ts`:
  node mode wire shape, signed node-to-agentId hello binding, authoritative owner,
  per-request model/path checks, per-frame/delivery expiry guard, socket expiry,
  cleared pending delivery, and local HTTP cancellation on disconnect.
- `bridge-agent-autostart.ts`: optional node configuration obtains a fresh verified
  lease per connection; no broker-admin fallback. Native mode still works without
  this optional CLJS boundary. `bridge-routing.ts` and `federation-helpers.ts`:
  qualified owner routing, with verified OIDC owner admission for scoped nodes.
- `test/proxx/auth/relay_law_test.cljs`, `docs/axxium-relay.md`.
- Direct pinned `jose@6.2.13`, matching the already installed transitive version;
  lock importer updated and frozen offline validation passed. Per Proxx AGENTS,
  package version is now 0.4.0 with an **Unreleased** README history entry.

## Actual validation

Logs are under `target/relay-validation/` in the respective worktree.

| Check | Actual result |
| --- | --- |
| Authority `npm run typecheck` | PASS, 0 compiler warnings |
| Authority `npm test` | PASS: 87 CLJS tests, 437 assertions, 0 failures/errors; both Node script tests pass |
| Authority `npm run build` | PASS, 0 compiler warnings |
| Authority `npm run boundary:check` | PASS |
| Authority `npm run test:oidc-protocol` | PASS; existing socket-free RFC8628 mock-SQL fixture |
| Authority `npm run test:relay` / direct script after final Proxx rebuild | PASS: 12 grouped production adapter cases, described below |
| Proxx `pnpm build` | PASS: TS and runtime CLJS, 0 production compiler warnings |
| Proxx `pnpm typecheck` | PASS |
| Proxx `pnpm test:cljs` | PASS: 148 tests, 503 assertions, 0 failures/errors |
| Proxx no-new-TS / policy gates | PASS, 264 active TS-family files; no new TS files |
| Proxx touched TS ESLint (`--max-warnings 0`) | RED: 19 inherited warnings, 0 errors; baseline also 19 |
| Proxx native protocol/owner/auth and schema tests | PASS: 22 tests, 0 failures; schema contributes 5 cases |
| Proxx frozen offline lockfile validation | PASS |
| Both worktree `git diff --check` | PASS |
| Proxx complete Node suite | RED: 51/58 test files pass, 7 fail; listener restrictions block acceptance |
| Direct full proxy file | RED: 183 failed cases with `listen EPERM`, 1 skipped |
| Existing authority PostgreSQL fixture | BLOCKED: TCP listener `EPERM` |
| New real PostgreSQL relay fixture | BLOCKED: PostgreSQL Unix socket bind `Operation not permitted` |

The first full Proxx CLJS test compilation emitted eight inference warnings in
untouched `src/proxx/store/redis.cljs` (22,25,31,47) and
`src/proxx/store/postgres.cljs` (14,25,29,33). Incremental final compilation added
no relay warnings. These inherited warnings still prevent a clean fresh zero-warning
claim; they were not hidden or fixed by unrelated source changes.

The 12 relay adapter groups use true RS256 JWTs from the production authority HTTP
mount and existing public JWKS, actual broker JOSE/auth/SQL-wire adapters and
actual websocket upgrade/decoder/expiry logic over a socket-free Duplex. Covered:
closed credentials/TLS/browser refusal; file removal, disable, secret rotation,
expiry, malformed/schema/mode/key changes; actor disable; signed wrong/missing
issuer/audience/type/scope/node/owner/time/model claims; invalid signature;
concurrent one-use admission and store reconstruction; qualified owner routing;
exact node/topology/owner hello binding; forbidden paths/models/background RPC;
header/context stripping; no HTTP account authority; catalog narrowing; fresh
node issuance; outage/disabled-grant refusal with no admin fallback; expiry of
both established and uncompleted handshakes and failure of pending stream delivery.

Default fixture mode uses a SQL transport double and synthetic actor lookup.
It DOES NOT prove real PostgreSQL uniqueness, durability or production actor
storage. The `--postgres` mode uses an empty owned cluster, actual actor reads,
production replay SQL, concurrent inserts, pool/store replacement and cleanup;
its startup was attempted and blocked. Real network agent handshake, timed
reconnect/renewal and local inference cancellation were not accepted here.

The lint baseline comparison in Proxx `target/relay-validation/relay-lint-baseline.log`
compares each touched TypeScript file to HEAD with the same ESLint configuration.
Three newly crossed thresholds were removed by extracting transport cleanup and
upgrade/stream helpers; the final result is 19 warnings versus 19 at HEAD, with
no added warning-rule counts. This still fails `--max-warnings 0` and is not a
warning-free lint claim.

## Parent commands to clear remaining gates

Run in an environment permitted to bind local TCP/Unix sockets. Use only synthetic
test credentials; do not read a live `.env` to run these suites.

```sh
cd /home/err/.local/share/codex-work/axxium-sso/relay-proxx
pnpm install --frozen-lockfile
pnpm build
pnpm typecheck
pnpm test:cljs
pnpm check:no-new-typescript
mkdir -p target/relay-tmp
TMPDIR="$PWD/target/relay-tmp" PROXY_AUTH_TOKEN=synthetic-relay-tests-only pnpm test

cd /home/err/.local/share/codex-work/axxium-sso/relay-authority
npm run typecheck
npm test
npm run build
npm run boundary:check
mkdir -p target
TMPDIR="$PWD/target" npm run test:oidc-store
npm run test:relay
npm run test:relay-postgres
```

`AXXIUM_TEST_PG_BIN` may select installed PostgreSQL tools. The relay fixture
creates/cleans only its owned cluster under authority `target/`, never a live DB.
Dependencies were copied into these owned worktrees from existing parent modules;
parent modules were not mutated. An initial non-frozen offline metadata resolution
failed for uncached @types/cors, but no network install was needed for validation;
the final frozen offline lockfile check succeeded.

## Security limits and deliberate subset

Operator grants represent authorization, not host attestation or asserted DID.
The parent must establish actual human identity by real sign-in before configuring
real subjects. Broker and node pins must independently name that issuer/node/owner.
Stolen bearer leases may win the first-use race; replay storage does not prevent
first-use theft. Protect TLS ingress, forwarding-header trust and secret storage.

Authority revocation/actor disable stops new issuance immediately upon the next
read. Existing leases expire in <=25 seconds; bounded clock skew can add up to one
second of real-time residual window. Delivery guards and timers assume normal
clock/event-loop progress. There is no positive revocation cache. Grant revisions
are audit data, not an independent online revocation channel.

SSE, image operations, response-resource/background operations and provider
credential transfer are refused in scoped mode. SSE needs a distinct multi-request
binding/replay contract. Long inference is cut off at expiry and never silently
replayed; this can interrupt ordinary long generations. Local provider credentials
stay local. The existing local execution adapter's loopback/internal-header trust
boundary remains a local-process trust assumption. No new issuance rate limiter
or hardware proof is provided. Native federation paths retain their existing trust
model during migration.

## Reflection for parent-owned receipts

Efficiency 0.68; friction 0.52; reusable skill candidate 0.30. Better path: compile
small CLJS boundaries immediately, keep fixture transport separate from SQL
acceptance, and test owner route selection alongside authentication. No spore was
created or promoted. This report supplies evidence for the parent's owner API;
canonical receipts and board ledgers were deliberately left unchanged.
