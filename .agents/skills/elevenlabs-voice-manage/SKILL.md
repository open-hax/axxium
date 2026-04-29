---
name: elevenlabs-voice-manage
description: "Manage ElevenLabs voices: clone from samples, adjust settings, and delete voices."
license: GPL-3.0-or-later
compatibility: opencode
metadata:
  audience: agents
  workflow: audio-production
  version: 1
---

# Skill: elevenlabs-voice-manage

## Purpose
Manage the ElevenLabs voice library, allowing for the creation of new voices through cloning, the fine-tuning of voice parameters (stability, similarity, style), and the removal of unused voices.

## Dependencies
- `curl`, `jq`, `ELEVENLABS_API_KEY`

## Usage

### Clone a Voice
Create a new voice using one or more audio samples.
```bash
ELEVENLABS_API_KEY=... .agent/skills/elevenlabs-voice-manage/agent-voice-manage.sh   --clone "My New Voice"   --sample sample1.wav   --sample sample2.wav   --description "A calm, professional narrator"
```

### Adjust Settings
Update stability, similarity boost, and style exaggeration.
```bash
ELEVENLABS_API_KEY=... .agent/skills/elevenlabs-voice-manage/agent-voice-manage.sh   --settings <VOICE_ID>   --stability 0.5   --similarity 0.75   --style 0.2
```

### Delete a Voice
Remove a voice from the account.
```bash
ELEVENLABS_API_KEY=... .agent/skills/elevenlabs-voice-manage/agent-voice-manage.sh   --delete <VOICE_ID>
```

## Inputs
| Param | Description |
|---|---|
| `--clone NAME` | Name of the voice to create |
| `--sample FILE` | Path to a WAV sample (can be used multiple times) |
| `--description TEXT` | Optional description of the voice |
| `--settings ID` | Voice ID to update |
| `--stability VAL` | Stability (0.0 to 1.0) |
| `--similarity VAL` | Similarity boost (0.0 to 1.0) |
| `--style VAL` | Style exaggeration (0.0 to 1.0) |
| `--delete ID` | Voice ID to remove |

## Skill chain
→ precedes: `elevenlabs-tts`
← follows: `list-voices` (to identify target IDs)
