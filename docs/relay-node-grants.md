# Operator-approved machine relay grants

Source implementation only. No runtime acceptance, identity verification, live
node enrollment, deployment or real-subject configuration is established here.

The optional `AXXIUM_OIDC_CONFIG_FILE` remains the private authority configuration.
It must be a regular **mode 0600** JSON file, at most 1 MiB; symlinks are refused.
Keep it outside Git. Persistent RS256 private keys must remain consistent with
`/oidc/jwks`. This change creates no keys in a request handler.

An optional `node-grants` array is separate from `clients`. Every entry has exactly
these fields (the following values are synthetic examples, never live identity):

```json
{
  "node-id": "synthetic-node",
  "secret": "REPLACE_WITH_A_DISTINCT_RANDOM_SECRET_OF_AT_LEAST_32_CHARACTERS",
  "owner-subject": "synthetic-example-only",
  "broker-audience": "https://broker.example",
  "expires-at": 1792800000,
  "enabled": false,
  "allowed-models": ["example-model"]
}
```

The operator must first establish the real human's Axxium subject through actual
sign-in, then explicitly authorize that existing active actor ID. Neither a
hostname nor an email match, DID assertion, OIDC client, browser session, remote
role, or caller-supplied owner constitutes this authorization. Entries are closed:
unknown fields refuse configuration. Nodes and secrets are unique; node secrets
must differ from every browser-client secret. There may be at most 64 grants.

`expires-at` is an explicit finite integer Unix timestamp in seconds. Issuance
requires it to be in the future and no more than 30 days away. Operators should
activate/renew with an expiry within that bound and record their approval through
their owning operational system. Nothing auto-renews grants. An expired grant
stays refused until the operator changes it. A grant may contain 1–128 unique
exact model names, each 1–128 ASCII characters from letters, digits, `.`, `_`, `:`,
`/`, and `-`, starting with an alphanumeric character. Their total length must be
at most 4096 characters. Regexes, wildcards and prefix matching are unsupported.

`POST /api/federation/relay-lease` accepts only a JSON object containing `node-id`
and `secret`. It requires HTTPS as reported by Fastify's configured trusted proxy
boundary. The ingress must preserve that boundary; do not trust client-supplied
forwarding headers from arbitrary Internet peers. Secrets belong in the request
body, never URLs. Responses and refusals use `Cache-Control: no-store`; errors
are generic and contain no credentials or configuration details.

Every issuance opens and rereads the operator's private file, validates its mode,
size and closed schema, compares non-grant configuration to startup, compares
secret digests with constant-time equality, and uses the production root actor
lookup to recheck the exact actor ID and `active` status. Configuration read,
actor lookup, schema, TLS and signing failures refuse issuance. There is no
positive grant cache. Changes to issuer, keys, cookies or browser clients refuse
issuance until authority restart; grant-only edits take effect on the next call.

Leases use the existing RS256 key and `typ=axxium-relay+jwt`, fixed
`scope=proxx:relay`, exact issuer/audience, configured owner and node, unique
`jti`, integer `iat`/`exp`, exact `allowed_models`, and a digest of the non-secret
grant as `grant_revision`. Lifetime is at most 25 seconds and ends no later than
grant expiry. Public verification uses existing `/oidc/jwks`.

To revoke issuance, remove or disable the entry, rotate its secret, expire it,
or disable the actor. Write the complete replacement privately and atomically;
malformed or unavailable files refuse requests. Already-issued leases remain
usable only until their original expiry. The Proxx websocket and pending delivery
end at that expiry, so grant removal or actor disable has a maximum residual
capability window of 25 seconds under normally progressing clocks/event loops.
Leases are bearer credentials: theft can win the first connection race before
atomic replay consumption. They are neither hardware attestation nor proof of
host identity. Protect node secret storage and both TLS endpoints. The endpoint
has no new rate limiter; ingress request limits remain an operator responsibility.

See the paired Proxx `docs/axxium-relay.md` for broker/node wiring and exclusions.
