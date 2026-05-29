---
uuid: "kanban-specs-eta-mu-extraction-vault-md"
title: "Fork Tales → Eta-Mu Extraction Vault"
status: incoming
priority: P3
labels: ["specs", "migrated-spec"]
created_at: "2026-05-29T04:29:44.528Z"
source: "specs/eta-mu-extraction-vault.md"
category: "specs"
---

> Source: `specs/eta-mu-extraction-vault.md`
> Migrated-to-kanban: `kanban/eta-mu-extraction-vault.md`

# Fork Tales → Eta-Mu Extraction Vault

> *What wants to exist separately, must be cut free.*

## Purpose

`fork_tales` is a vault — a corpus of ideas, experiments, and dense hacks. Each "presence," "panel," and "muse" was meant to share the same intelligence system substrate. That substrate is what we're capturing in the eta-mu monorepo.

This document catalogs the distinct systems buried inside, their contracts, and their extraction paths.

---

## The Vault Map

```
orgs/octave-commons/fork_tales/
├── part64/
│   ├── code/                    # Python runtime (dense, coupled)
│   │   ├── world_web/           # ✗ DO NOT MIGRATE — 300KB+ files, extreme coupling
│   │   ├── web_graph_weaver.js  # ✓ Extract → packages/web-graph-weaver
│   │   ├── lore.py              # ✓ Extract → packages/lore (entity manifest)
│   │   ├── myth_bridge.py       # ✓ Extract → packages/myth-bridge
│   │   └── *.py (other)         # ✗ Tightly coupled — concepts only
│   └── frontend/
│       └── src/
│           ├── app/             # ✓ Extract concepts → panels composition
│           ├── components/       # ✓ Extract patterns → ui components
│           └── types/            # ✓ Extract domain types
├── world_building/              # Lore corpus — NOT code
└── artifacts/                   # Experiments
```

---

## Systems That Want to Exist

### 1. Presence System

**What it is:**
Named entities in a field with anchors, roles, and budget governance. Not just "users" — presences are *positions* in a semantic space, each with its own DAIMON (distributed AI monitor) budget, context, and coordination role.

**Key concepts:**
- `presence_id` — unique position identifier
- DAIMON budgets — resource allocation per-presence
- Field assignment — which semantic field a presence inhabits
- Handoff protocol — CAS-style ownership transfer

**Extraction path:**
```
packages/presence-core/
├── src/
│   ├── presence.ts         # Core presence type
│   ├── daimon-budget.ts    # Budget governance
│   ├── handoff.ts          # Ownership transfer
│   └── field-assignment.ts # Semantic field mapping
└── package.json
```

**Source vault files (concepts only, do not copy):**
- `world_web/presence_runtime.py` — Redis-backed presence state
- `world_web/constants.py` — `PRESENCE_OPERATIONAL_ROLE_BY_ID`
- `world_web/simulation_backend_particles.py` — `_PARTICLE_ROLE_BY_PRESENCE`

---

### 2. Muse System

**What it is:**
Agent runtime with modes, resource claims, context manifests, and media handling. Muses *run* on behalf of presences, with GPU claims, mode switching, and threat fallback strategies.

**Key concepts:**
- `muse_id` — agent instance identifier
- Mode strategy — `witness_thread`, `chaos`, `github_security_review`, etc.
- Resource REDACTED_SECRET — GPU/memory claims
- Context manifest — what the muse knows
- Media strategy — audio/image intent classification

**Extraction path:**
```
packages/muse-core/
├── src/
│   ├── muse.ts              # Core muse type
│   ├── mode-strategy.ts     # Mode selection
│   ├── resource-REDACTED_SECRET.ts     # Claims management
│   ├── context-manifest.ts  # Context assembly
│   └── media-strategy.ts    # Intent classification
└── package.json
```

**Source vault files (concepts only):**
- `world_web/muse_runtime.py` — Main runtime
- `world_web/muse_mode_strategy.py` — Mode selection
- `world_web/muse_media_strategy.py` — Media handling
- `world_web/muse_threat_fallback_strategy.py` — Fallback routing

---

### 3. Panel Composition System

**What it is:**
React UI layer that composes "panels" — self-contained views that can be pinned, floated, and arranged. Each panel connects to a presence or substrate service.

**Key concepts:**
- `WorldPanelLayoutEntry` — panel position and anchor
- `PanelWindowState` — float/dock/pinned state
- `OverlayApi` — shared overlay surface for panels
- Panel types: ThreatRadar, WebGraphWeaver, MusePresence, DaimoiPresence, etc.

**Extraction path:**
```
packages/panel-composer/
├── src/
│   ├── panel-layout.ts      # Layout engine
│   ├── panel-state.ts       # Window state management
│   ├── overlay-api.ts       # Shared overlay surface
│   └── panels/              # Panel component registry
│       ├── threat-radar.tsx
│       ├── presence-call-deck.tsx
│       └── catalog.tsx
└── package.json
```

**Source vault files:**
- `frontend/src/app/worldPanelLayout.ts` — Layout primitives
- `frontend/src/app/appShellTypes.ts` — Types
- `frontend/src/components/Panels/*.tsx` — Panel implementations

---

### 4. Web Graph Weaver

**What it is:**
Web crawler and RSS feed consumer. Fetches pages, follows links, builds knowledge graph. Was the RSS seed source for Threat Radar.

**Key concepts:**
- Watchlist seeds — URLs to crawl
- Robots.txt compliance
- Concurrency limits, depth limits
- WebSocket streaming of crawl progress
- ArXiv, Wikipedia, feed extraction

**Extraction path:**
```
packages/web-graph-weaver/
├── src/
│   ├── weaver.ts           # Core crawler
│   ├── watchlist.ts        # Seed management
│   ├── feed-parser.ts      # RSS/Atom handling
│   ├── robots.ts           # Robots.txt
│   └── knowledge-graph.ts  # Node/edge extraction
└── package.json
```

**Source vault files:**
- `part64/code/web_graph_weaver.js` — Full implementation
- Uses `@open-hax/signal-watchlists` and `@open-hax/signal-source-utils`

---

### 5. Lore / Entity Manifest

**What it is:**
Canonical definitions of entities, voice lines, role hints, and system prompt templates. The "characters" in the simulation.

**Key concepts:**
- `ENTITY_MANIFEST` — all entity definitions
- `VOICE_LINE_BANK` — localized voice lines (ja/en)
- `NAME_HINTS`, `ROLE_HINTS` — semantic hints
- `SYSTEM_PROMPT_TEMPLATE` — LLM prompts

**Extraction path:**
```
packages/lore/
├── src/
│   ├── entities.ts        # Entity manifest types
│   ├── voice-lines.ts     # Localized lines
│   ├── hints.ts           # Name/role hints
│   └── prompts.ts         # System templates
└── package.json
```

**Source vault files:**
- `code/lore.py` — All definitions

---

### 6. Myth Bridge

**What it is:**
Event bridge between simulation and external systems. Emits `cover_field_presence` events and handles `media_presence` attribution.

**Extraction path:**
```
packages/myth-bridge/
├── src/
│   ├── bridge.ts           # Event bridge
│   └── attribution.ts      # Media attribution
└── package.json
```

**Source vault files:**
- `code/myth_bridge.py` — Event handling

---

### 7. Threat Radar (✓ Already Extracted)

**Status:** Already exists as `packages/radar-core`.

**What it is:**
Signal definitions, assessment packets, evidence indexing, reduction, and snapshot sealing.

**Next steps:**
- Add RSS adapter (`rss-adapter.ts`)
- Add Discord output channel

---

### 8. Daimoi System (Field Physics)

**What it is:**
Particle dynamics for semantic fields. Each presence has a "daimon" that moves through the field based on attraction/repulsion rules, collision semantics, and resource economics.

**Key concepts:**
- `DAIMO_FORCE_KAPPA`, `DAIMO_DAMPING` — physics parameters
- Quadtree spatial indexing
- Collision semantics
- Field absorption

**Extraction path:**
```
packages/daimoi-core/
├── src/
│   ├── physics.ts          # Force calculations
│   ├── quadtree.ts         # Spatial indexing
│   ├── collision.ts        # Collision detection
│   └── field.ts            # Field abstraction
└── package.json
```

**Source vault files:**
- `world_web/daimoi_*.py` — All daimoi modules

---

### 9. World Building (Narrative Layer)

**What it is:**
Not code — the story universe, character profiles, world bible. Gates of Truth lore.

**Extraction path:**
Keep in vault. Reference for narrative coherence, do not migrate to code packages.

**Source vault files:**
- `world_building/bible/World_Bible.md`
- `world_building/characters/Character_Profiles.md`
- `MANUSCRIPT_FULL.md`

---

### 10. Simulation Backend

**What it is:**
The core world simulation — particles, fields, WebSocket broadcasting, state management. Massive coupling (397KB `simulation.py`).

**Status:** DO NOT MIGRATE AS-IS. Extract concepts only.

**Key concepts:**
- Tick-based simulation
- Particle system
- WebSocket delta protocol
- Governor rate limiting

**Extraction requires:**
- Complete rewrite, not migration
- Extract `packages/simulation-core/` from first principles

---

## Already Extracted

| System | Package | Status |
|--------|---------|--------|
| Threat Radar | `packages/radar-core` | ✓ Active |
| Signal ATProto | `packages/signal-atproto` | ✓ Active |
| Signal Embed Browser | `packages/signal-embed-browser` | ✓ Active |
| Thread Assessment | `packages/thread-assessment` | ✓ Active |
| FSM | `packages/fsm` | ✓ Active |
| Event | `packages/event` | ✓ Active |
| Logger | `packages/logger` | ✓ Active |
| Utils | `packages/utils` | ✓ Active |

---

## Extraction Priority

**Phase 1: Core Substrate**
1. `packages/presence-core/` — Presence identity and field assignment
2. `packages/muse-core/` — Agent runtime
3. `packages/lore/` — Entity manifest

**Phase 2: Intelligence Layer**
4. `packages/daimoi-core/` — Field physics
5. `packages/myth-bridge/` — Event bridge
6. `packages/web-graph-weaver/` — Crawler (feeds Threat Radar)

**Phase 3: UI Layer**
7. `packages/panel-composer/` — Panel composition
8. Build Threat Radar web UI as reference panel

**Phase 4: Integration**
9. Connect to shuv's `syndicussy` for RSS source management
10. Daily briefing renderer

---

## The Eta-Mu Monorepo Structure

```
packages/
├── presence-core/        # Presence identity
├── muse-core/            # Agent runtime
├── daimoi-core/          # Field physics
├── lore/                 # Entity manifest
├── myth-bridge/          # Event bridge
├── radar-core/           # ✓ Threat radar
├── web-graph-weaver/     # Web crawler
├── panel-composer/       # UI composition
├── signal-atproto/       # ✓ ATProto signals
├── signal-embed-browser/ # ✓ Embed browser
├── thread-assessment/    # ✓ Thread analysis
├── fsm/                  # ✓ State machine
├── event/                # ✓ Event primitives
├── logger/               # ✓ Logging
└── utils/                # ✓ Utilities
```

---

## Source Anchors

| Concept | Vault File | Extracted Concept |
|---------|------------|-------------------|
| Presence identity | `presence_runtime.py` | `presence-core/presence.ts` |
| Daimon budget | `presence_runtime.py:L30-70` | `presence-core/daimon-budget.ts` |
| Muse runtime | `muse_runtime.py` | `muse-core/muse.ts` |
| Mode strategy | `muse_mode_strategy.py` | `muse-core/mode-strategy.ts` |
| Panel layout | `worldPanelLayout.ts` | `panel-composer/panel-layout.ts` |
| Daimoi physics | `daimoi_probabilistic.py` | `daimoi-core/physics.ts` |
| Web crawler | `web_graph_weaver.js` | `web-graph-weaver/weaver.ts` |
| Entity manifest | `lore.py` | `lore/entities.ts` |

---

## Next

1. Create `specs/presence-core-spec.md` — Detailed extraction contract
2. Create `specs/muse-core-spec.md` — Detailed extraction contract
3. Verify shuv's `syndicussy` API surface for integration