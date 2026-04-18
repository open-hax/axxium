# llama.cpp Stack

Drop-in replacement for the `ollama-stack` service, providing local GPU-accelerated LLM inference via [llama.cpp](https://github.com/ggerganov/llama.cpp).

## Services

| Service | Port | Purpose |
|---|---|---|
| `llamacpp-chat` | 8080 | Chat/completions (`/v1/chat/completions`) |
| `llamacpp-embed` | 8081 | Embeddings (`/v1/embeddings`) |

Both join the `ai-infra` Docker network and expose OpenAI-compatible endpoints.

## Setup

### 1. Download GGUF models

```bash
mkdir -p models

# Chat model (gemma-4-e4b, Q4_K_M ~2.4GB)
wget -O models/gemma-4-e4b-Q4_K_M.gguf \
  "https://huggingface.co/bartowski/gemma-4-e4b-GGUF/resolve/main/gemma-4-e4b-Q4_K_M.gguf"

# Embedding model (qwen3-embedding-0.6b, F16 ~1.1GB)
wget -O models/qwen3-embedding-0.6b-F16.gguf \
  "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/qwen3-embedding-0.6b-f16.gguf"
```

Additional models you may want:

| Model | Recommended quant | Hugging Face source |
|---|---|---|
| `gemma-4-e2b` | Q4_K_M (~1.4GB) | [bartowski/gemma-4-e2b-GGUF](https://huggingface.co/bartowski/gemma-4-e2b-GGUF) |
| `qwen3.5-8b` | Q4_K_M (~5GB) | [bartowski/Qwen3.5-8B-GGUF](https://huggingface.co/bartowski/Qwen3.5-8B-GGUF) |
| `qwen3-embedding-4b` | F16 | [Qwen/Qwen3-Embedding-4B-GGUF](https://huggingface.co/Qwen/Qwen3-Embedding-4B-GGUF) |

### 2. Start the stack

```bash
cd services/llamacpp-stack
docker compose up -d
```

### 3. Verify

```bash
# Chat endpoint
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-e4b","messages":[{"role":"user","content":"hello"}]}'

# Embedding endpoint
curl http://127.0.0.1:8081/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3-embedding-0.6b","input":"hello world"}'
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `LLAMACPP_CHAT_PORT` | 8080 | Host port for chat server |
| `LLAMACPP_EMBED_PORT` | 8081 | Host port for embedding server |
| `LLAMACPP_CHAT_MODEL` | gemma-4-e4b-Q4_K_M.gguf | Chat model filename in `/models` |
| `LLAMACPP_EMBED_MODEL` | qwen3-embedding-0.6b-F16.gguf | Embedding model filename in `/models` |
| `LLAMACPP_MODELS_DIR` | ./models | Host directory containing GGUF files |
| `LLAMACPP_N_GPU_LAYERS` | 999 | GPU layers (999 = all layers on GPU) |
| `LLAMACPP_CTX_SIZE` | 8192 | Context window size (chat only) |
| `LLAMACPP_PARALLEL` | 4 | Parallel sequences (chat only) |
| `CUDA_VISIBLE_DEVICES` | 0 | GPU device index |

## Why llama.cpp over Ollama

- **Explicit GPU control**: `--n-gpu-layers 999` forces all layers to VRAM, bypassing Ollama's broken automatic offload heuristics
- **OpenAI-compatible API natively**: Standard `/v1/chat/completions` and `/v1/embeddings` without custom Ollama paths
- **Validated on Gemma 4 GGUFs**: No mangling issues that Ollama has ([ollama#15237](https://github.com/ollama/ollama/issues/15237))
