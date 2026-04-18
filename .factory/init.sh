#!/usr/bin/env bash
set -euo pipefail

cd /home/err/devel

# Install workspace dependencies
pnpm install --frozen-lockfile 2>/dev/null || pnpm install

# Build shared packages (order matters: radar-core first, then dependents)
pnpm --filter @workspace/radar-core build 2>/dev/null || true
pnpm --filter @workspace/mcp-foundation build 2>/dev/null || true
pnpm --filter @workspace/signal-atproto build 2>/dev/null || true
pnpm --filter @workspace/signal-embed-browser build 2>/dev/null || true

# Create .env for threat-radar-mcp if missing
if [ ! -f orgs/riatzukiza/threat-radar-mcp/.env ]; then
  cat > orgs/riatzukiza/threat-radar-mcp/.env <<'EOF'
PORT=9001
ADMIN_AUTH_KEY=\${ADMIN_AUTH_KEY:-placeholder}
DATABASE_URL=postgres://${PGUSER:-REDACTED_SECRET}:${PGPASSWORD:-changeme}@localhost:5432/REDACTED_SECRET
ALLOW_UNAUTH_LOCAL=true
EOF
fi

# Ensure threat-radar database exists in local Postgres
docker exec open-hax-openai-proxy-open-hax-openai-proxy-db-1 \
  psql -U REDACTED_SECRET -tc "SELECT 1 FROM pg_database WHERE datname='REDACTED_SECRET'" | grep -q 1 \
  || docker exec open-hax-openai-proxy-open-hax-openai-proxy-db-1 \
    psql -U REDACTED_SECRET -c "CREATE DATABASE REDACTED_SECRET" 2>/dev/null || true

echo "[init.sh] Environment ready"
