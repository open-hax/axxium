# Voxx runtime agent guidance

- This runtime should default voice synthesis to Voxx + Kokoro.
- Prefer `VOICE_GATEWAY_TTS_BACKEND_ORDER=xiaomi_mimo,kokoro,espeak` for local/agent use; keep MeloTTS opt-in only.
- Keep Voxx STT disabled by default (`VOICE_GATEWAY_STT_ENABLED=0`); use the host Knoxx NPU STT service for Whisper.
- Do not add direct voice-provider credentials or SDK routes to this compose stack; put any approved provider behind Voxx explicitly.
