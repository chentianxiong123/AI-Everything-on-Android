#!/usr/bin/env bash
# Build the OFFICIAL New API APK payload: clean upstream source, real SQLite, full web UI.
# No patches. (Benchmark patches live in patches/benchmark-archive/ and are not used here.)
set -euo pipefail

UPSTREAM_REPO="https://github.com/QuantumNous/new-api.git"
UPSTREAM_TAG="${NEWAPI_TAG:-v1.0.0-rc.15}"   # tagged release = official binary this project always used
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${NEWAPI_WORKDIR:-${TMPDIR:-/tmp}/newapi-official-$$}"

echo "=== 1/4 Fetch upstream $UPSTREAM_TAG ==="
rm -rf "$WORK"
git clone --quiet --depth 1 --branch "$UPSTREAM_TAG" "$UPSTREAM_REPO" "$WORK"

echo "=== 2/4 Build both web frontends ==="
(cd "$WORK/web/default" && bun install --frozen-lockfile && bun run build)
(cd "$WORK/web/classic" && bun install --frozen-lockfile && bun run build)

echo "=== 3/4 Cross-compile Go -> Android .so (real SQLite, CGO_ENABLED=0) ==="
OUT="${1:-$HERE/app/src/main/jniLibs/arm64-v8a/liboneapi.so}"
mkdir -p "$(dirname "$OUT")"
(cd "$WORK" && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
   go build -ldflags="-s -w" -o "$OUT" .)

echo "=== 4/4 Done ==="
ls -lh "$OUT"
echo "Source kept at: $WORK (delete manually; no upstream source in this repo)"
