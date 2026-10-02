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
Protocol's `http://localhost` development exception; the public client uses
the HTTPS metadata URL. The administrator email configured through
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
| `JWT_SECRET` | change-me | JWT signing secret |
| `AXXIUM_PUBLIC_BASE_URL` | http://localhost:8787 | Exact public origin for callbacks and CORS |
| `GOOGLE_OAUTH_CLIENT_FILE` | | Private Google web OAuth client JSON path |
| `AXXIUM_BOOTSTRAP_ADMIN_EMAIL` | | Verified Google email granted admin on first actor creation |
| `JWT_ISSUER` | axxium | JWT issuer |
| `JWT_AUDIENCE` | promethean | JWT audience |
| `BCRYPT_SALT_ROUNDS` | 12 | Password hashing rounds |

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
