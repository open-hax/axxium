# Axxium

**The axiomatic identity and auth kernel for the Promethean system.**

Axxium is an identity kernel. Local Knoxx delegates password authentication
to it. Its public deployment shares an origin with an official AT Protocol
PDS. The kernel provides:

- **Actor registry** — Capability-bearing identities
- **Entity registry** — Pure identities (the underlying "who"
- **Session management** — Cookie + JWT-based sessions
- **Google sign-in** — OIDC code flow with PKCE and verified subject binding, enabled when its exact callback is registered
- **AT identity lookup** — SSRF-protected DID/handle resolution with reciprocal verification
- **AT Protocol sign-in** — OAuth identity proof through the account's authoritative server, binding its DID to a human actor; an existing human can explicitly link a DID
- **Portal** — Human administrator account and agent credential management

Axxium itself does not create `did:plc` identities, serve repositories, or
issue AT Protocol OAuth tokens. The separately deployed PDS owns those
protocol operations. A resolved DID is public identity evidence, not proof
that an Axxium actor controls it. Only the OAuth callback binds a DID after
the official client verifies the authorization flow.

## Quick Start

```bash
# Install dependencies
npm install

# Set up environment
cp .env.example .env
# Edit .env with your database credentials

# Development
npm run watch

# Build for production
npm run build
npm start
```

## Deployment ownership

Axxium owns application validation and its image. `open-hax/services` owns
the DigitalOcean Compose topology, Caddy ingress, service DID document, and
deployment workflow. At `https://axxium.promethean.rest`, Caddy routes Axxium
account and actor paths to this image and AT Protocol paths to the official
PDS. Axxium's OAuth client metadata is served at
`/api/auth/atproto/client-metadata.json`. The local client uses the AT
Protocol's `http://localhost` development client ID with a loopback-IP callback;
the public client uses the HTTPS metadata URL. AT OAuth state and session
storage and the request lock are process-local; deploy one Axxium replica until
shared storage and locking are configured. Local portal visits and OAuth starts
from `localhost` redirect to the configured `127.0.0.1` origin before creating
session or state cookies.
The administrator email configured through
`AXXIUM_BOOTSTRAP_ADMIN_EMAIL` is reserved from password signup and gains
administrator privileges only when Google verifies it on first sign-in.

## API Endpoints

### Auth
- `GET /api/auth/config` — Public auth configuration
- `POST /api/auth/signup` — Email/password registration
- `POST /api/auth/login` — Email/password login
- `POST /api/auth/logout` — Clear session
- `GET /api/auth/me` — Current actor

### Actors
- `GET /api/actors` — Administrator-only actor list, including human/agent kind
- `GET /api/actors/:id` — Administrator-only actor lookup
- `GET /api/actors/me` — Current actor
- `POST /api/actors/agents` — Administrator creates an agent actor
- `POST /api/actors/:id/credentials` — Administrator issues an expiring agent bearer credential
- `GET /api/actors/:id/credentials` — Administrator lists credential metadata
- `DELETE /api/actors/:id/credentials/:credentialId` — Administrator revokes a credential
- `POST /api/actors/:id/capabilities` — Administrator updates capabilities

### External identity
- `GET /api/auth/google/start` — Begin Google sign-in when configured
- `GET /api/auth/google/callback` — Complete Google sign-in
- `GET /api/auth/atproto/client-metadata.json` — AT Protocol OAuth client metadata
- `GET /api/auth/atproto/start?identity=<handle-or-did>` — Begin AT sign-in; `link=1` links to the current human actor
- `GET /api/auth/atproto/callback` — Complete verified DID sign-in
- `GET /api/atproto/resolve?identity=<handle-or-did>` — Administrator-only reciprocal AT identity lookup

### Entities
- `GET /api/entities/:id` — Get entity by ID

### System
- `GET /health` — Health check
- `GET /.well-known/jwks.json` — This instance's public Ed25519 signing key
- `GET /.well-known/did.json` — did:web document for `AXXIUM_PUBLIC_BASE_URL`
- `GET /` — Portal redirect
- `GET /portal/index.html` — Axxium portal

## Configuration

All configuration is via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `AXXIUM_PORT` | 8787 | HTTP server port |
| `AXXIUM_HOST` | 0.0.0.0 | Bind address |
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | axxium | Database name |
| `DB_USER` | axxium | Database user |
| `DB_PASSWORD` | | Database password |
| `AXXIUM_SIGNING_KEY_FILE` | required | PKCS#8 PEM of this instance's Ed25519 key. See [signing-key migration](docs/signing-key-migration.md). There is no default |
| `AXXIUM_SIGNING_KID` | thumbprint | Optional kid. Unset uses the RFC 7638 SHA-256 thumbprint of the public JWK `{kty,crv,x}` |
| `AXXIUM_ISS` | `JWT_ISSUER`, else `axxium` | Instance identifier sealed as the JWT `iss` |
| `AXXIUM_SERVICE` | `promethean` | Service component of the audience |
| `AXXIUM_ENV` | `NODE_ENV`, else `development` | Environment component of the audience and the `env` claim |
| `JWT_AUDIENCE` | unset | Optional check only: must equal `AXXIUM_SERVICE+AXXIUM_ENV` (for example `promethean+development`). Leave it unset. `docker-compose.yml` and `.env.example` do not set it; a bare `promethean` is rejected at startup |
| `AXXIUM_PUBLIC_BASE_URL` | http://127.0.0.1:8787 | Exact public origin for callbacks and CORS; AT OAuth requires a loopback IP for local redirects |
| `AXXIUM_TRUST_PROXY` | unset | Fastify `trustProxy` behind a reverse proxy, so the per-client AT Protocol sign-in start limit (10/min; IPv6 clients keyed by /64) keys by the real client address. Set the trusted proxy addresses/CIDRs that match the proxy chain (`false` disables). Bare `true` is refused because it trusts every hop, so a client that reaches the port directly could spoof `X-Forwarded-For` and bypass the limit. Hop counts are refused because Fastify 5 trusts no proxy for them |
| `GOOGLE_OAUTH_CLIENT_FILE` | | Private Google web OAuth client JSON path |
| `AXXIUM_BOOTSTRAP_ADMIN_EMAIL` | | Verified Google email granted admin on first actor creation |
| `JWT_ISSUER` | axxium | Fallback instance identifier when `AXXIUM_ISS` is unset |
| `BCRYPT_SALT_ROUNDS` | 12 | Password hashing rounds |

Actor tokens and Google OAuth state are EdDSA (Ed25519), signed by `AXXIUM_SIGNING_KEY_FILE`, with this instance's `kid` in the header. They are admitted only when `iss` is this instance, `aud` is `service+env`, the `env` claim is that same environment, and the header `kid` is this instance's kid. Each actor token carries `cred_id`, the session or agent credential that acted. `JWT_SECRET` is not read. Every JWT signed before this change is rejected, including an in-flight Google state cookie; `axx_` agent credentials are not JWTs and keep working. Provision the key before startup, as described in [docs/signing-key-migration.md](docs/signing-key-migration.md).

## Architecture

```
┌─────────────────────────────────────────┐
│              AXXIUM KERNEL               │
├─────────────────────────────────────────┤
│  Actor  │  Entity  │  Session  │  OAuth │
│ Registry│ Registry │  Manager  │Provider│
└────┬────┴────┬─────┴─────┬─────┴───┬────┘
     │         │           │         │
     ▼         ▼           ▼         ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│  proxx  │ │  knoxx  │ │openplanner│ │ tooloxx │
└─────────┘ └─────────┘ └─────────┘ └─────────┘
```

## License

GPL-3.0-only
