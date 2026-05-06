# Π Fork Tax — Broadcast Studio audio full tree freeze

Timestamp: 20260506T231041Z
Repo: /home/err/devel
Branch: pi/fork-tax/2026-04-15-170411
Base HEAD: a847e6e8
Tag target: pi-fork-tax-broadcast-audio-full-20260506T231041Z

## Preserved recursive chain
- Knoxx commit/tag: `011a145b` / `pi-fork-tax-broadcast-audio-20260506T231041Z`
- OpenPlanner commit/tag: `75c0071e` / `pi-fork-tax-openplanner-knoxx-audio-20260506T231041Z`
- This parent commit pins OpenPlanner and the llama.cpp service runtime defaults.

## Preserved llama.cpp working defaults
- `LLAMACPP_CHAT_CTX_SIZE` default `16384`
- `LLAMACPP_PARALLEL` default `1`
- `LLAMACPP_CHAT_BATCH_SIZE` / `LLAMACPP_CHAT_UBATCH_SIZE` default `256`
- `LLAMACPP_CACHE_RAM` default `0`
- `bin/run-chat-server.sh` supports `--cache-ram`.
- Live ignored `.env` is fingerprinted in `.ημ/Π_MANIFEST.sha256` and intentionally not committed.

## Verification evidence
- Knoxx: `clj-kondo` on `turn.cljs` only showed pre-existing findings; `shadow-cljs compile server` exited 0.
- Runtime: `docker compose config --quiet` exited 0 for `services/llamacpp-stack`.
- Runtime: `GET http://127.0.0.1:8082/health` returned `{"status":"ok"}`.
- Audio: repeated relative `/api/studio/stream` Cathedral direct-start runs completed with actual audio-derived notes.

## Concurrent dirt intentionally not absorbed
Parent `/home/err/devel` has extensive unrelated deletes/assets/submodule dirt. This fork tax stages only `services/llamacpp-stack`, `.ημ`, receipts, and the OpenPlanner submodule pointer.
