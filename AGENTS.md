# Axxium — Agent Guidance

Axiomatic identity/auth kernel. All-CLJS Fastify + PostgreSQL server.

## Quick commands

```bash
# Build
pnpm build

# Tests
pnpm test

# Lint
pnpm lint:kondo

# Boundary check
pnpm boundary:check

# Watch
pnpm watch

# Clean
pnpm clean
```

## Architecture

- Fastify HTTP server with JWT/cookie sessions
- PostgreSQL via pg + next.jdbc
- Password auth via bcryptjs
- Actor read surface + entity read endpoint
- JS boundary enforced by scripts/check-js-boundary.mjs

## Dependencies

- Maven: malli, promesa
- npm: fastify, @fastify/cookie, @fastify/cors, @fastify/static, bcryptjs, jose, pg
- Node built-ins: crypto, fs, path, url
- No workspace or sibling dependencies

## License

GPL-3.0-or-later
