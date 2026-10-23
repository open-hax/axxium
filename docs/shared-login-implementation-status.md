# Shared login and federation implementation status

Prepared 2026-10-23 UTC. This is an implementation handoff, not deployment acceptance.

## Published plans

- Personal Knoxx migration PR: https://github.com/riatzukiza/axxium/pull/3
  Extended at 7e46fa41, including client/actor provenance, transport refusal,
  machine device polling and a concrete corpus-memory acceptance fixture.
- Personal Proxx migration PR: https://github.com/riatzukiza/axxium/pull/4
  Extended at f7445761. Three native review findings settled against 5a811cd1;
  review-body fixture finding answered in native comment 6356114581.
- Current authority source base: open-hax/axxium main 99e6c725.

## Implemented preparation

`axxium.law.device-polling` validates a persisted polling record and an
explicit clock, rejects wrong clients, consumed/expired grants and malformed
input, and proposes bounded polling backoff without browser Origin requirements.
The persistence adapter must lock, read, decide and persist atomically before
calling the protocol engine. A continuation never supplies user approval.

Verified locally: 78 CLJS tests, 352 assertions, zero failures/errors; 14 JS tests
pass. clj-kondo reports zero errors/warnings for the new files. Backend typecheck
and production build complete with zero compiler warnings. Native deployment
has not consumed this source.

A separate loopback spike uses pinned oidc-provider 9.13.0 with synthetic
fixtures only. Discovery, code exchange with S256 PKCE, state/nonce, client
audience, replay rejection, token introspection/revocation and reuse of one
synthetic browser login across two clients pass. Its device fast-poll test
remains red: the library does not implement the required backoff itself.
The pure law is not wired into that spike or any production adapter yet.
The spike uses development in-memory state and is not a production service.

## Current gates

Planning review is not admitted. CodeRabbit reported an included-review limit;
Codex reported account quota unavailable. No current-head approval is claimed.
Use the canonical pr-flow CLI to collect fresh native status before resuming.

PR 3 finding 4355555629 remains valid: a newly appended historical receipt omits
repo and pi. A new versioned owner-validated correction preserves its bytes and
hash; whole-ledger validation still fails. The old helper script is incompatible
with current owner-required fields and must not be used for new events.

https://github.com/open-hax/axxium/issues/27 holds composition of independently
introduced append-only receipt/reflection ledgers pending reviewed owning policy.
An attempted integration was aborted; no side was selected, no history rewritten.
Rheos ready transitions have not been performed. Installed eta-mu commands returned
generic help rather than a usable receipt/board operation; no alternate board
implementation or manual status transition was created.

## Deployment work remaining

1. Obtain current-head planning admission, reviewed ledger policy and lawful
   Rheos readiness; preserve existing migration requirements and all ledger bytes.
2. Add durable PostgreSQL OIDC storage, signing-key lifecycle, interaction routes
   backed by existing Axxium actor sessions, registered clients, exact redirects,
   authenticated status/revocation, and the atomic device-poll law adapter.
3. Integrate Knoxx and Proxx browser redirects with issuer/subject identity;
   retain product-owned authorization and prevent email-based identity merging.
4. Bind separate, revocable federation node grants to the approved owner. Keep
   provider credentials local to their source node; laptops connect outbound.
5. Deploy through existing Services manifests to droplet, Stealth and Yoga;
   preserve runtime-specific effective environment and rollback material.
6. Exercise a real operator login and approved second-device login, cross-product
   reuse, revocation/wrong-audience/expired rejection, and real actor-memory
   retrieval plus a generated answer routed through each authorized source.

The droplet replaces Big Ussy in the target topology. No live identity/session,
provider billing, firewall, credential or deployment changes were made in this
preparation. Shared login and federation are not yet operational on the three hosts.
