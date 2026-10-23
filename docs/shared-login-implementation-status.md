# Shared login and federation implementation status

Updated 2026-10-23 UTC. Shared login and federation are not yet deployed.

## Implemented and tested

- Pure device polling admission: clock/input validation, client binding,
  expiry/consumption refusal, bounded interval/backoff and clock regression.
- PostgreSQL protocol adapter: model-scoped records, expiry-aware lookup,
  persistent consumed evidence, atomic one-use consume and grant revocation.
- OIDC authority constructor pinned to oidc-provider 9.13.0: registered server
  clients with distinct strong secrets and exact HTTPS redirects, mandatory
  S256 PKCE, persistent configured signing/cookie keys, active-actor lookup,
  client-scoped token introspection and revocation. Device issuance is optional and disabled by default; when configured it uses
  explicit root-session approval, durable atomic polling/backoff and one-use redemption.
- Optional Fastify protocol and consent routes are composed into server startup,
  enabled only by AXXIUM_OIDC_CONFIG_FILE naming a private operator-owned JSON
  file. Normal Axxium startup stays unchanged when absent. Root-session consent
  uses Origin/CSRF checks and resumes the existing account portal. No ephemeral
  signing keys or synthetic interactions are enabled in the product transport.

Validation: 85 CLJS tests / 419 assertions and 15 JS tests pass; changed Clojure files lint with
zero warnings/errors. The optimized integration target compiles with zero
warnings. Backend typecheck/build and JS boundary checks pass. Run
`npm run test:oidc-store` with PostgreSQL 16 tools installed (or set
AXXIUM_TEST_PG_BIN): it creates an owned empty cluster, exercises actual SQL and
provider HTTP exchanges, then stops/removes the cluster and fixture HTTP server.
No existing service database or Docker container is used by the fixture.

The synthetic integration proves durable restart/reconnect behavior, model
isolation, expiry, concurrent one-use consume, shared browser login across two
clients, code/PKCE/state/nonce/audience, token replay rejection, independent
client introspection/revocation and live disabled-actor refusal. Actual mounted
Fastify discovery, root-session consent, Origin/CSRF rejection, registered redirect
refusal, code exchange and parent-session revocation also pass against isolated
PostgreSQL. Private grant/session bindings store only the root token digest. Detected code
reuse may revoke the whole grant, including an earlier successful response;
clients must begin a fresh authorization flow instead of replaying the code.

## Ledger and board repair

Rheos 0.2.0 is installed at /home/err/.local/bin/rheos with its full dependency
package retained. Both planning worktrees were read with explicit board config;
comments went through Rheos and its own tracked event ledger. Both drift checks
passed. No manual lifecycle transition or alternate board implementation exists.

Receipt River PR https://github.com/riatzukiza/receipt-river/pull/8 is the minimal
owner-policy successor based on current fork main 0de65e6. It retains the existing
contextual reader and ledger prefix. Its full suite (42 tests/293 assertions),
zero-warning lint/release and 15 existing public API cases pass locally; hosted
CI passed. Native review/adoption remains required. Superseded PR7 and its source
commits/native finding settlement remain inspectable without rewritten history.

Axxium planning streams contain explicit candidate metadata completions bound to
exact invalid historical line numbers and SHA256. Original prefixes remain
byte-identical. Candidate validateLedger admission passes, while raw validity
remains false. This does not resolve the independent receipt/reflection history
composition issue https://github.com/open-hax/axxium/issues/27.

## Published plans

- Knoxx: https://github.com/riatzukiza/axxium/pull/3
- Proxx: https://github.com/riatzukiza/axxium/pull/4
- Accepted organization actor foundation: 99e6c725; personal sync PR11 pending.

## Required remaining integration

1. Qualify owner metadata-completion policy and source-qualified composition;
   collect current-head planning review and perform lawful Rheos readiness.
2. Obtain independent review of the implemented session-bound authority and
   mounted protocol/consent transport; qualify actual provider sign-in/portal
   resume and ingress trust configuration before live activation.
3. Validate the source in the real deployment image and registered host clients;
   configure persistent private signing/cookie/client material outside Git.
4. Qualify the mounted device flow in the live portal before enabling issuance.
   Durable SQL and mounted approval/denial tests pass; human sign-in remains owed.
5. Integrate Knoxx and Proxx consumers, preserving issuer/subject identity,
   product authorization, existing account/resource attribution and retirement
   obligations. Never merge identities on email alone.
6. Bind revocable, relay-only federation node grants to the approved owner;
   retain provider credentials on their source nodes and laptop outbound relays.
7. Deploy using existing Services manifests/effective environments to droplet,
   Stealth and Yoga. The droplet replaces Big Ussy in the target topology.
8. Verify a real operator login, cross-product and approved cross-device reuse,
   revocation/wrong-audience/expiry refusal, and a real creative-memory actor
   answer routed through each approved federation source.

No live identity/session, provider billing, firewall, signing key, client secret
or deployment was changed by these preparations. No production acceptance is claimed.

## Consumer source validation

The bounded Knoxx and Proxx implementation workers have finished. Parent tests
passed: Knoxx backend 1865 tests/9211 assertions and frontend 462/2056;
Proxx CLJS 127/405, OIDC 13/101 and proxy 183 pass/one existing skip. Both
products build and typecheck. The production actor lookup now has a wiring
regression using the actual database query contract. Device integration exercises
real PostgreSQL, mounted engine forms, Origin/XSRF, explicit denial, client binding,
poll backoff/expiry and root logout. No live account was used by these tests.

Consumers retain product-local authorization and require explicit qualified
issuer/subject links. No email merging or remote role import is enabled. Browser
grant lifetime is presently five minutes; refresh and device-initiation product
UX remain work before final acceptance. Machine federation grants remain separate.
Parent retains integration, review and deployment.
