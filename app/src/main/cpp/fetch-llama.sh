#!/usr/bin/env bash
# Vendors the pinned llama.cpp source used by the GGUF backend.
# The tree itself is gitignored (large); run this once before building.
set -euo pipefail

PIN="8452824611be321246f33339727f60a90c02c277"
DIR="$(cd "$(dirname "$0")" && pwd)/llama.cpp"

if [ -d "$DIR/.git" ] || [ -f "$DIR/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $DIR"
  exit 0
fi

echo "Cloning llama.cpp @ $PIN ..."
git clone https://github.com/ggml-org/llama.cpp.git "$DIR"
git -C "$DIR" checkout "$PIN"
rm -rf "$DIR/.git"
echo "Done: $DIR (pinned $PIN)"
