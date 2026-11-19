#!/usr/bin/env bash
# Build oneapi-termux-arm64.tar.gz from the official upstream static binary.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="${1:-$HERE/dist}"
BIN_URL="https://github.com/songquanpeng/one-api/releases/download/v0.6.10/one-api-arm64"
STAGE="$DIST/stage"; mkdir -p "$STAGE"
echo "Downloading official One API arm64 binary..."
curl -fL --retry 3 -o "$STAGE/one-api" "$BIN_URL"
chmod 755 "$STAGE/one-api"
cp "$HERE/../README.md" "$STAGE/" 2>/dev/null || true
tar -C "$STAGE" -czf "$DIST/oneapi-termux-arm64.tar.gz" one-api README.md
echo "Built: $DIST/oneapi-termux-arm64.tar.gz"
