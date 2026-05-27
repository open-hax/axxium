#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v shellcheck >/dev/null 2>&1; then
  echo "shellcheck is required" >&2
  exit 1
fi

mapfile -t SHELL_FILES < <(find scripts -type f -name '*.sh' | sort)
mapfile -t TRACKED_SERVICE_RUNTIME_PATHS < <(
  git ls-files -- \
    'services/*/data/**' \
    'services/*/.data/**' \
    'services/*/runtime-secrets/**' \
    'services/*/secrets/**' \
    'services/*/storage/**' \
    'services/*/uploads/**' \
    'services/*/tmp/**'
)

if [[ "${#TRACKED_SERVICE_RUNTIME_PATHS[@]}" -ne 0 ]]; then
  echo "tracked service runtime data paths are forbidden:" >&2
  printf '  %s\n' "${TRACKED_SERVICE_RUNTIME_PATHS[@]}" >&2
  echo "move runtime state to ignored local volumes or deployment secrets instead." >&2
  exit 1
fi

if [[ "${#SHELL_FILES[@]}" -eq 0 ]]; then
  echo "no shell files found"
  exit 0
fi

shellcheck "${SHELL_FILES[@]}"
