#!/bin/sh
set -eu

if [ "$(uname -s)" != Linux ]; then
  echo 'The Codex peer guard requires Linux.' >&2
  exit 1
fi

repo="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
headers="${AXXIUM_NODE_INCLUDE_DIR:-/usr/include/node}"
if [ ! -r "$headers/node_api.h" ]; then
  echo 'Set AXXIUM_NODE_INCLUDE_DIR to the directory with node_api.h.' >&2
  exit 1
fi

output="$repo/target/codex-peer"
mkdir -p "$output"
staged="$(mktemp "$output/.codex_peer.XXXXXXXX")"
trap 'rm -f -- "$staged"' EXIT
trap 'exit 1' HUP INT TERM

"${AXXIUM_PEER_CC:-cc}" -std=c11 -O2 -fPIC -shared \
  -Wall -Wextra -Werror -Wl,-z,relro,-z,now -I "$headers" \
  "$repo/native/codex_peer.c" -o "$staged"
mv -- "$staged" "$output/codex_peer.node"
printf '%s\n' "$output/codex_peer.node"
