# Axxium

**The axiomatic identity and auth kernel for the Promethean system.**

Axxium is the shared identity provider that proxx, knoxx, and openplanner all consume. It provides:

- **Actor registry** — Capability-bearing identities
- **Entity registry** — Pure identities (the underlying "who"
- **Session management** — Cookie + JWT-based sessions
- **OAuth provider** — For service-to-service auth
- **Portal** — User-facing identity management

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

Axxium currently owns application validation and portable packaging only. It
does not declare a testing, staging, or production host. The former direct SSH
workflows were retired; any future production image build, host placement,
deployment, and live verification must be added to the declared DigitalOcean
contract in `open-hax/services`.

## API Endpoints

### Auth
- `GET /api/auth/config` — Public auth configuration
- `POST /api/auth/signup` — Email/password registration
- `POST /api/auth/login` — Email/password login
- `POST /api/auth/logout` — Clear session
- `GET /api/auth/me` — Current actor

### Actors
- `GET /api/actors` — List actors
- `GET /api/actors/:id` — Get actor by ID
- `GET /api/actors/me` — Current actor
- `POST /api/actors/:id/capabilities` — Update capabilities

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

## Host environments and portable identity

Each instance uses `https://<environment>.axxium.promethean.rest`. Stealth and
Yoga have independent PostgreSQL state, session secrets and Ed25519 signing keys.
The browser portal is `/portal/index.html`. Register or sign in, choose a trusted
destination, confirm the current password, and copy the transfer. On the recipient,
open “Bring an identity to this computer”, paste it, and choose a new local password.
The actor and entity identifiers survive; passwords, sessions and permissions do
not travel. The recipient assigns its own basic account permissions.

A transfer is signed, recipient-bound, valid for five minutes and usable once.
Imports accept only explicitly pinned issuer public keys. Exchange public keys
through the operator's authenticated SSH connection; never distribute private
keys or copy a source instance's `.env`. Existing account collisions are refused.
After import, fresh recipient logins use the recipient database and continue when
the issuer application is stopped.

Build with `npm ci --ignore-scripts && npm run typecheck && npm test && npm run build`,
then `docker build -t axxium:<commit> .`. Prepare an instance using
`scripts/prepare-instance.mjs DIRECTORY ENV PUBLIC_ORIGIN IMAGE`; it generates local
secrets with private permissions and installs `compose.instance.yaml`. Configure
`trust.json` as an object mapping exact trusted issuer origins to their public JWKs.
Publish the loopback application port through the documented Knoxx Caddy ingress.

`node scripts/verify-identity-transfer.mjs` exercises the real HTTP/database flow;
its environment options are documented at the top of the script. The public
Stealth-to-Yoga verification passed 28 assertions, including tampering, audience,
replay, password reauthentication, sessions and account collisions. Browser proof
also includes a fresh Yoga login after the Stealth Axxium container was stopped.

The former unrestricted actor listing and capability-grant routes are no longer
public administration APIs. Actor access is self-scoped; provisioning local
permissions is an operator responsibility.

`.github/workflows/environment-promotion.yml` calls the pinned Services controller:
a code-owner `testing` label on an open main-targeted PR claims the testing slot
subject to the two-hour incumbent lease; a successful merge to main promotes the
exact merge commit to staging. The three existing repository administrators are
listed in CODEOWNERS. These workflows activate when merged to main. Production
has no automatic Axxium deployment entry point; it must acquire integration, e2e
and long mutation qualification before a production deployer is enabled.
