---
name: list-voices
description: "Retrieve and list available voices from the Voxx gateway, with optional legacy ElevenLabs direct mode."
license: GPL-3.0-or-later
compatibility: opencode
metadata:
  audience: agents
  workflow: audio-production
  version: 2
---

# Skill: list-voices

## Purpose
Query the local Voxx gateway for provider-agnostic voice IDs, names, categories, labels, and OpenAI-compatible voice shape.

Voxx fronts Kokoro, MeloTTS, ElevenLabs, and fallback engines behind one API, so prefer Voxx voice IDs unless you explicitly need a direct provider operation.

## Dependencies
- `curl`, `jq`
- Voxx running locally, default: `http://127.0.0.1:8787`
- `VOICE_GATEWAY_API_KEY` if not using the dev default (`dev-token`)

## Usage
```bash
.agents/skills/list-voices/agent-list-voices.sh
.agents/skills/list-voices/agent-list-voices.sh --filter bright
.agents/skills/list-voices/agent-list-voices.sh --openai
.agents/skills/list-voices/agent-list-voices.sh --json
```

Optional legacy direct ElevenLabs listing:
```bash
ELEVENLABS_API_KEY=... .agents/skills/list-voices/agent-list-voices.sh --legacy-elevenlabs
```

## Voxx endpoints
- `GET /v1/voices` — ElevenLabs-compatible catalog shape
- `GET /v1/voices/openai` — OpenAI-style voice list
- `GET /v1/voices/{voice_id}/settings` — voice settings

## Skill chain
→ precedes: `elevenlabs-tts`, `voice-tts`
