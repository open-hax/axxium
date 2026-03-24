#!/usr/bin/env bash
set -euo pipefail
pkill -f "server.py --REDACTED_SECRET dist --host 127.0.0.1 --port 8794" || true
