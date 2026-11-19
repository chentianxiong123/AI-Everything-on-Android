#!/usr/bin/env bash
# Build newapi-termux-arm64.tar.gz.
#
# NOTE: official upstream release binary is dynamically linked (glibc), which
# cannot run under Termux (Bionic). So we cross-compile a STATIC arm64 binary
# from the clean upstream tag (CGO_ENABLED=0, real SQLite, full web UI),
# same source as the APK payload but named as a normal executable.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="${1:-$HERE/dist}"
UPSTREAM_REPO="https://github.com/QuantumNous/new-api.git"
UPSTREAM_TAG="${NEWAPI_TAG:-v1.0.0-rc.15}"
WORK="${NEWAPI_WORKDIR:-${TMPDIR:-/tmp}/newapi-termux-$$}"

STAGE="$DIST/stage"; mkdir -p "$STAGE"

echo "=== 1/4 Fetch upstream $UPSTREAM_TAG ==="
rm -rf "$WORK"
git clone --quiet --depth 1 --branch "$UPSTREAM_TAG" "$UPSTREAM_REPO" "$WORK"

echo "=== 2/4 Build web frontends ==="
(cd "$WORK/web/default" && bun install --frozen-lockfile && bun run build)
(cd "$WORK/web/classic" && bun install --frozen-lockfile && bun run build)

echo "=== 3/4 Static cross-compile (CGO_ENABLED=0) ==="
(cd "$WORK" && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
   go build -ldflags="-s -w" -o "$STAGE/new-api" .)
chmod 755 "$STAGE/new-api"

echo "=== 4/4 Package tar.gz ==="
cp "$HERE/../README.md" "$STAGE/" 2>/dev/null || true
tar -C "$STAGE" -czf "$DIST/newapi-termux-arm64.tar.gz" new-api README.md
rm -rf "$WORK" "$STAGE"
echo "Built: $DIST/newapi-termux-arm64.tar.gz"
