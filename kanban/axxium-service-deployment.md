---
uuid: axxium-service-deployment
title: "Add Axxium to the centralized DigitalOcean deployment"
status: todo
priority: P1
labels: ["axxium", "deployment", "services", "docker", "pm2"]
created_at: "2026-06-02T00:00:00Z"
source: "axxium/kanban/axxium-service-deployment.md"
points: 5
category: infrastructure
---

# Add Axxium to the centralized DigitalOcean deployment

## Goal
Add Axxium to the declared `open-hax/services` DigitalOcean stack. Until this
card is complete, Axxium has no deployed testing, staging, or production slot.

## Requirements
- [ ] Create `digitalocean/services/axxium/` with a portable Compose definition
- [ ] Add a CI-owned immutable Axxium image build
- [ ] Declare host placement in `digitalocean/hosts/production.yaml`
- [ ] Environment variable template (`.env.example`)
- [ ] Health check endpoint integration
- [ ] Database migration strategy
- [ ] Pinned host trust and protected-environment deployment gate

## Acceptance Criteria
- Services CI builds and publishes an immutable Axxium image
- the declared DigitalOcean Compose project starts the full stack
- Database migrations run automatically on startup
- Services live verification proves the health check through declared ingress
- no Axxium repository workflow mutates a host directly

## Related
- Proxx services: `services/proxx/`
- Axxium server: `orgs/open-hax/axxium/src/cljs/axxium/server.cljs`
