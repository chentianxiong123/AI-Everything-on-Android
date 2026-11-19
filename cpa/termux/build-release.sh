#!/usr/bin/env bash
# Build cpa-termux-arm64.tar.gz from upstream CLIProxyAPI official static no-plugin binary.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="${1:-$HERE/dist}"
# Pin via env: CPA_TAG=v8.0.79 ; default = latest
TAG="${CPA_TAG:-v8.0.79}"
BIN_URL="https://github.com/router-for-me/CLIProxyAPI/releases/download/${TAG}/CLIProxyAPI_${TAG#v}_linux_aarch64_no-plugin.tar.gz"
PANEL_URL="https://github.com/router-for-me/Cli-Proxy-API-Management-Center/releases/latest/download/management.html"
STAGE="$DIST/stage"; TMP="$DIST/tmp"; mkdir -p "$STAGE" "$TMP"
echo "Downloading CPA $TAG no-plugin (static, musl-compatible)..."
curl -fL --retry 3 -o "$TMP/cpa.tar.gz" "$BIN_URL"
tar -xzf "$TMP/cpa.tar.gz" -C "$TMP"
BIN=$(find "$TMP" -maxdepth 2 -type f -name 'cli-proxy-api' | head -1)
[ -n "$BIN" ] || { echo "cli-proxy-api not found in archive" >&2; exit 1; }
cp "$BIN" "$STAGE/cli-proxy-api"; chmod 755 "$STAGE/cli-proxy-api"
echo "Downloading management panel..."
curl -fsSL "$PANEL_URL" -o "$STAGE/management.html" || echo "<html><body>CPA Panel</body></html>" > "$STAGE/management.html"
cp "$HERE/../README.md" "$STAGE/" 2>/dev/null || true
tar -C "$STAGE" -czf "$DIST/cpa-termux-arm64.tar.gz" cli-proxy-api management.html README.md
echo "Built: $DIST/cpa-termux-arm64.tar.gz"
