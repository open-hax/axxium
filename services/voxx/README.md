# voxx devops home

Canonical source: `../../orgs/open-hax/voxx`

This directory is the workspace-local home for runtime/devops material for `voxx`:
- canonical runtime Compose stack (`compose.yaml`)
- Kokoro sidecar Dockerfile (`Dockerfile.kokoro`)
- env example
- persistent runtime volume managed by Compose

The Voxx application image Dockerfile is not duplicated here. `compose.yaml` builds it from the source package at `../../orgs/open-hax/voxx/Dockerfile.compose`.

## Local compose
```bash
cd /home/err/devel/services/voxx
docker compose up --build -d
curl http://127.0.0.1:8787/healthz
```

The compose default now binds Voxx to loopback (`127.0.0.1`) so it can sit behind a reverse proxy without exposing a raw public port. Override with `VOXX_BIND_HOST=0.0.0.0` only when you explicitly want direct network exposure.

For smarter TTS quality without changing callers away from Voxx, pass remote-provider creds straight into the compose runtime and let Voxx fall back automatically. The local default is Kokoro, MeloTTS, then eSpeak. If you opt into a remote/free provider such as Xiaomi MiMo, keep local providers after it so quota, auth, status-code, or outage failures degrade to Kokoro/Melo/eSpeak instead of requiring prompt edits.

```bash
cd /home/err/devel/services/voxx
XIAOMI_MIMO_API_BASE_URL=https://api.xiaomimimo.com/v1 \
XIAOMI_MIMO_API_KEY=... \
VOICE_GATEWAY_TTS_BACKEND_ORDER=xiaomi_mimo,kokoro,melo,espeak \
docker compose up --build -d
```

Xiaomi MiMo also accepts the legacy typo-prefixed env names `XAIOMI_MIMO_API_BASE_URL` and `XAIOMI_MIMO_API_KEY` so existing local env files keep working while callers migrate to `XIAOMI_*`.

Kokoro runs as a non-root derived image (`Dockerfile.kokoro`) with CUDA-enabled PyTorch (`torch+cu128`) and its English spaCy model installed at build time.

MeloTTS runs inside the Voxx application container. The source `Dockerfile.compose` installs Python 3.11, CPU PyTorch/Torchaudio, MeloTTS from GitHub, UniDic, and NLTK assets needed by `melo.api.TTS`. Melo is local and queue-protected by Voxx, so it is a good fallback when remote/free providers are unavailable.

The compose runtime requests NVIDIA GPU access for Voxx/Kokoro and pins both containers to host CPUs `2-21` by default (`VOXX_CPUSET=2-21`) so CPUs 0-1 remain available for the host. It also enables a conservative Voxx TTS queue (`TTS_QUEUE_MAX_CONCURRENT=1`, `TTS_QUEUE_MAX_PENDING=32`, `TTS_QUEUE_TIMEOUT_SECONDS=120`) to prevent agent bursts from spawning unbounded synthesis work.

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

To force Melo for a single validation run without changing callers:

```bash
cd /home/err/devel/services/voxx
VOICE_GATEWAY_TTS_BACKEND_ORDER=melo docker compose up -d --no-build voxx
curl -X POST 'http://127.0.0.1:8787/v1/audio/speech?postprocess=off' \
  -H "Authorization: Bearer ${VOICE_GATEWAY_API_KEY:-dev-token}" \
  -H 'Content-Type: application/json' \
  --data '{"model":"kokoro","voice":"alloy","input":"Melo local fallback check.","response_format":"mp3"}' \
  --output /tmp/voxx-melo.mp3
# restore stable local order afterward
VOICE_GATEWAY_TTS_BACKEND_ORDER=kokoro,melo,espeak docker compose up -d --no-build voxx
```

Prompt-aware performance is opt-in by default. Enable per request with `?prompt_aware=1` or JSON `"prompt_aware": true`; Voxx then asks prompt-capable backends to treat tags like `[excited]`, `[whisper]`, `[pause]`, or `<break time="500ms" />` as performance directions rather than spoken text. Local Kokoro/Melo/eSpeak do not guarantee tag interpretation.

If a request hits provider status-code failures such as quota/rate limit/auth/5xx, do not rewrite prompts for a new provider. Keep the caller pointed at Voxx and set the runtime backend order with local fallbacks, e.g. `VOICE_GATEWAY_TTS_BACKEND_ORDER=xiaomi_mimo,kokoro,melo,espeak`.

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
