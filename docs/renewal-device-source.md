# Root-bound renewal and device sign-in source handoff

This is source work for parent integration. No deployment, live database,
credentials, real root owner, commit, PR, canonical ledger, or board was changed.

A registered confidential client must explicitly set `refresh-enabled?` to true
in the private OIDC JSON configuration. Existing clients retain code-only behavior.
With the global device feature enabled, `device-enabled?` may be false to exclude
an individual client. All clients still require their own strong secret and exact
registered redirect URIs. Identity scopes remain `openid profile`.

Refresh uses the documented oidc-provider `issueRefreshToken` hook, rather than
`offline_access`. Consent explains reuse only while the current root session is
active, with an absolute 24-hour maximum. `rotateRefreshToken` is always true.
The pinned engine's refresh handler calls `validateAccount` / `findAccount` before
consuming or minting tokens; our seam checks the current actor and parent grant.
It also reads the current stored parent expiry. Pure lifetime policy bounds ID,
access and refresh token issuance to that deadline. Refresh persistence independently
joins the current actor, root session, binding, and engine grant, and bounds SQL
expiry. Atomic `consume` cannot be won twice. Failed rotation destroys its grant
family using public `Provider.use` middleware; no engine monkeypatch is involved.
A refresh never calls `bind-session!`, changes the subject, or extends that binding.

Client-scoped introspection and revocation remain in place. Successful access-token
introspection supplies `root_session_expires_at` from a fresh parent lookup; RPs
require it before retaining a renewable browser session. The maintained engine owns
all opaque tokens and ID-token signatures.

Run from this owned worktree:

```sh
npm run typecheck
npm test
npm run build
npm run test:oidc-protocol
npm run test:oidc-store
```

The injected mounted-HTTP fixture covers opt-in issuance, wrong client, rotation,
replay/concurrency, actor refusal and root logout using synthetic data. It is a
mock SQL fixture and cannot prove PostgreSQL locking or SQL correctness.
The isolated PostgreSQL/HTTP fixture is the required durable qualification; it
creates and destroys an empty temporary PostgreSQL cluster and synthetic actors.
It is blocked here because the sandbox refuses the initial loopback listener:
`Error: listen EPERM: operation not permitted 127.0.0.1`.
Parent must run `npm run test:oidc-store` in a socket-capable harness before acceptance.
No live PostgreSQL or administrator identity should be substituted.

The authority unit tests, mounted injected protocol fixture, typecheck, and release
build passed with zero compiler warnings. See the parent-facing report for current
counts and product verification. Browser/RP integration with both actual consumers,
real registrations, expiry shortening, persistence across process restart, and
multi-device human UX remain integration qualification, not deployment claims.

Upstream API references: [oidc-provider 9.13.0](https://github.com/panva/node-oidc-provider/blob/v9.13.0/docs/README.md),
[maintained RP refresh](https://github.com/panva/openid-client/blob/v6.8.8/docs/functions/refreshTokenGrant.md),
[maintained RP device polling](https://github.com/panva/openid-client/blob/v6.8.8/docs/functions/pollDeviceAuthorizationGrant.md).
