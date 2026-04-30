# voxx devops home

Canonical source: `../../orgs/open-hax/voxx`

This directory is the workspace-local home for runtime/devops material for `voxx`:
- Compose stack
- compose-only Dockerfile
- env example
- persistent runtime volume managed by Compose

## Local compose
```bash
cd /home/err/devel/services/voxx
docker compose up --build -d
curl http://127.0.0.1:8787/healthz
```

The compose default now binds Voxx to loopback (`127.0.0.1`) so it can sit behind a reverse proxy without exposing a raw public port. Override with `VOXX_BIND_HOST=0.0.0.0` only when you explicitly want direct network exposure.

For smarter TTS quality without changing callers away from Voxx, pass remote-provider creds straight into the compose runtime and let Voxx fall back automatically. The local default is Kokoro, MeloTTS, then eSpeak. Agents should strongly prefer this local Voxx + Kokoro path and only opt into remote providers when explicitly required.

```bash
cd /home/err/devel/services/voxx
XIAOMI_MIMO_API_BASE_URL=https://api.xiaomimimo.com/v1 \
XIAOMI_MIMO_API_KEY=... \
VOICE_GATEWAY_TTS_BACKEND_ORDER=kokoro,xiaomi_mimo,melo,espeak \
docker compose up --build -d
```

Xiaomi MiMo also accepts the legacy typo-prefixed env names `XAIOMI_MIMO_API_BASE_URL` and `XAIOMI_MIMO_API_KEY` so existing local env files keep working while callers migrate to `XIAOMI_*`.

Kokoro runs as a non-root derived image (`Dockerfile.kokoro`) with its English spaCy model installed at build time. MeloTTS is still optional: if `melo` is selected without the Python package present, Voxx returns 503 or falls through to the next backend.

Voxx carries backend-agnostic postprocess profiles by default, so any provider voice can be pushed toward a consistent mastered texture:

```bash
TTS_POSTPROCESS_ENABLED=1
TTS_POSTPROCESS_PROFILE=sports-commentator-v1
```

Profiles can also be selected per request, without restarting the stack:

```bash
curl -X POST 'http://127.0.0.1:8787/v1/audio/speech?postprocess_profile=radio&prompt_aware=1' \
  -H "Authorization: Bearer ${VOICE_GATEWAY_API_KEY:-dev-token}" \
  -H 'Content-Type: application/json' \
  --data '{"model":"kokoro","voice":"alloy","input":"[excited] Local Voxx is alive!","response_format":"mp3"}' \
  --output /tmp/voxx-radio.mp3
```

Available profile aliases: `sports`, `broadcast`, `narrator`, `radio`, `soft`; list the full catalog at `GET /v1/audio/postprocess-profiles`. Disable final mastering globally with `TTS_POSTPROCESS_ENABLED=0` or per request with `?postprocess=off`.

Prompt-aware performance is opt-in by default. Enable per request with `?prompt_aware=1` or JSON `"prompt_aware": true`; Voxx then asks prompt-capable backends to treat tags like `[excited]`, `[whisper]`, `[pause]`, or `<break time="500ms" />` as performance directions rather than spoken text. Local Kokoro/Melo/eSpeak do not guarantee tag interpretation.

If port `8788` is busy:
```bash
VOXX_PORT=8798 docker compose up --build -d
```

## Deploys from source-repo `main`

The source repo now carries a GitHub Actions pipeline at:

- `orgs/open-hax/voxx/.github/workflows/voxx-main.yml`

That pipeline publishes a GHCR image and, on successful `main` pushes, updates this runtime by SSH.

Operational contract on the host:
- keep runtime secrets in `services/voxx/.env`
- let CI own `services/voxx/.env.deploy`
- `.env.deploy` pins `VOXX_IMAGE=ghcr.io/...:sha-<commit>` for the currently deployed build
- deploy step runs `docker compose pull voxx && docker compose up -d --no-build voxx`

## Source workflows
For source edits, work in `../../orgs/open-hax/voxx`.
