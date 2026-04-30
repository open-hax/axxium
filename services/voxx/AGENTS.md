# Voxx runtime agent guidance

- This runtime should default voice synthesis to Voxx + Kokoro.
- Prefer `VOICE_GATEWAY_TTS_BACKEND_ORDER=kokoro,melo,espeak` for local/agent use.
- Do not add direct voice-provider credentials or SDK routes to this compose stack; put any approved provider behind Voxx explicitly.
