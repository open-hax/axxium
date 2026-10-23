# Relay acceptance follow-up source handoff

2026-10-23. Owned worktrees only: `relay-authority` and `relay-proxx`.
Based on authority `21a4b6fc` and Proxx `7360d78`; HEADs were not changed.
This is source/test evidence, not native listener, TLS, real-owner, rollout or
cross-host acceptance. Parent evidence in `../PARENT-INTEGRATION-FINDINGS.md`
was read as context; its earlier 12-group real PostgreSQL run does not establish
PostgreSQL acceptance of this follow-up.

## Source behavior

- Authority grants now require explicit nonempty unique `allowed-tenants` and
  sign audience-scoped `allowed_tenants`. Closed schema, private-file checks,
  per-issuance reread, active actor lookup and 25-second maximum remain intact.
- A route-scoped Fastify error handler covers malformed and oversized JSON before
  the issuance handler. Parser errors and submitted credential text are neither
  serialized nor logged. Fixture checks both response and captured logger output.
- An existing scoped websocket renews every ~10 seconds using explicit
  `reauthorize` / `reauthorize_ack` frames, a fresh signed lease and exact previous
  jti. Agent and broker verify locally; broker durable atomic consumption and
  generation comparison precede deadline advancement. Only same identity and
  exact or strictly narrower model/tenant scope can renew. Failed, superseded,
  raced or widened renewal cannot revive a closed generation.
- Broker and agent check current scope on delivery. Narrowing aborts unauthorized
  running work and clears buffered delivery; authorized long work continues.
  Cancellation, timeout, close and async SQL/handler races cannot revive work.
- Optional operator-owned `AXXIUM_RELAY_DELEGATIONS` binds current SQL keyId plus
  tenantId to an already human-verified issuer/subject, finite expiry, nodes,
  models and operations. Only server-resolved active API-key auth with
  `proxy:use` reaches this decision, after existing local policy. No positive
  auth cache, admin inference, caller identity field or credential export was
  added. Synthetic operator examples are in the paired configuration docs.
- Each scoped RPC binds a server UUID nonce, method, exact route/model, SHA-256
  of exact UTF-8 body bytes, actor/tenant/node/audience/issuer and grant revision.
  Closed binding schema, 120-second validity, 5-second clock bound, no queries,
  and complete-body verification precede local execution. Background is permitted
  only when absent or literally false, so malformed truthy types cannot opt in. Durable local replay
  consumption reuses `SqlFederationStore.consumeRelayLease` with a disjoint RPC
  namespace and expiry at issuedAt+125; existing cleanup retains another 60s.
  No prompts/media/bodies are stored in the replay table. No schema migration. Capability advertisement uses the existing protocol
  enum `non_exportable`; the previous `non_importable` value was rejected by the
  real decoder and is covered by the router fixture.
- Scoped dispatch failure is terminal in the CLJS law and mapped to a generic
  502 or terminated stream by the transport. An uncertain/completed effect does
  not fall through to another node/provider. Native bridge migration semantics
  remain separate.

Policy/config/admission/execution decisions live in `.cljc`/CLJS. TypeScript
changes are interface, wire scheduling, cancellation and HTTP/SQL adapter plumbing.

## Validation

Authority checks completed with zero failures/errors and zero compiler warnings:

- `npm run typecheck`
- `npm test`: 87 CLJS tests / 440 assertions; two JavaScript test files pass.
- `npm run build`
- `npm run boundary:check`

Proxx checks completed:

- `npm run typecheck`
- `pnpm build`: release runtime has zero compiler warnings.
- `pnpm test:cljs`: 152 tests / 550 assertions, zero failures/errors. Eight
  inherited inference warnings remain in `src/proxx/store/redis.cljs` (22, 25,
  31, 47) and `src/proxx/store/postgres.cljs` (14, 25, 29, 37). These unrelated
  adapters were not changed.
- Socket-free Node test files: request-auth, federation-owner-subject,
  federation-bridge-protocol and federation-owner-credential pass.
- `pnpm run check:no-new-typescript`: inventory and policy-boundary gates pass.
- Strict targeted clj-kondo with missing-docstring, unused-value, shadowed-var,
  used-underscored-binding, warn-on-reflection, unsorted-required-namespaces and
  refer enabled: zero errors/warnings in changed relay source/test files.
- Targeted ESLint: zero errors, 12 inherited warning sites remain across app,
  runtime, autostart, protocol, relay, agent and routing (file/function size and
  existing complexity). New scheduling/delivery helpers were split to avoid
  new warning sites; oversized existing files remain oversized.

The paired `npm run test:relay` fixture uses production authority HTTP/JOSE,
production broker upgrade/codec, actual agent websocket codecs over explicitly
synthetic paired Duplex transport, and the production SQL adapter over an explicit
SQL transport double. It exercises real elapsed time: 55-second streamed response,
>=5 renewals, one websocket, one handler call; wrong tenant and delegated key
negatives; replay races and reconstruction including mutated RPC bodies; close
while durable admission is pending; running-work outage/removal/actor disable;
narrowing/cancellation with no late delivery; parser response/log redaction; and
terminal scoped dispatch without replay. The final paired run passed all 22 adapter groups. This run makes no native PostgreSQL or TLS claim.

An expiry test originally observed a peer's independent timer one event-loop turn
before it destroyed its socket. The fixture now waits a bounded 200ms for both
already-due timers before asserting destruction; production expiry is unchanged.

## Parent-owned checks and integration constraints

The existing native autostart test was attempted and failed with
`listen EPERM: operation not permitted 127.0.0.1`. No native listener suite or
live database was treated as verified. The native proxy and remaining federation
suites, owned PostgreSQL follow-up, independent review and rollout remain parent
work. Exact commands in a permitted parent environment, using synthetic test auth:

```sh
cd /home/err/.local/share/codex-work/axxium-sso/relay-proxx
pnpm build
PROXY_AUTH_TOKEN=synthetic-fixture-only pnpm exec tsx --test src/tests/proxy.test.ts
node --test dist/tests/federation-bridge-agent.test.js dist/tests/federation-bridge-relay.test.js dist/tests/federation-bridge-autostart.test.js
cd ../relay-authority
PROXX_RELAY_WORKTREE=../relay-proxx npm run test:relay-postgres
```

The PostgreSQL fixture owns a new empty cluster, exercises the production replay
SQL and production current API-key lookup/revocation, and performs no live-service
DB operation. The worker did not rerun it because native listener acceptance is
parent-owned and blocked here.

Parent must retain its logging redaction commits when integrating this source.
Services owns the dedicated private configuration-directory mount; a single-file
bind does not observe atomic replacement. These sources document that constraint
and do not assert that deployment mounts have been repaired.

No commit, push, PR, live host/DB/SSH/Docker/deployment, real identity/credential,
board, ledger, global configuration or other-thread message was performed.

## Parent composed-release verification

The initial source snapshot is retained byte-faithfully in
`relay-initial-source-handoff.md`; its sandbox limitations are historical.
The parent composed this relay delta with renewable browser/device login on
accepted actor foundation99e6c725, preserving independent source/receipt streams
in their original commits. The combined source passes88 CLJS tests/446 assertions
and15 JavaScript tests, with zero compiler warnings. Its actual owned PostgreSQL
OIDC/device fixture passes. With PROXX_RELAY_WORKTREE explicitly selecting the
composed current-release consumer, all22 relay adapter groups also pass against
real owned PostgreSQL, including the55-second streamed response over renewed
leases on one socket. No synthetic fixture is a claim of human or fleet acceptance.

The selected real Axxium owner is verified. Proxx's existing product credential
store has explicitly provisioned its local owner accounts on the droplet,
Stealth and Yoga. No OIDC authority activation, node secret installation or host
cutover has happened. Qualified images, review, native controller/host enrollment
and real browser/federation acceptance remain pending.
