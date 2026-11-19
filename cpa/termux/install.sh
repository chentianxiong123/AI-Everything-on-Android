#!/bin/sh
# ==============================================================================
# CLIProxyAPI (CPA) for Android (Termux Native, no root)
# Uses upstream official linux_aarch64_no-plugin static binary (musl-compatible,
# no libc dependency). Downloads from this repo's GitHub Release.
# ==============================================================================
set -eu

REPO="${REPO:-chentianxiong123/oneapi-android-apk}"
INSTALLER_URL="https://raw.githubusercontent.com/${REPO}/master/cpa/termux/install.sh"
TAR_URL="https://github.com/${REPO}/releases/latest/download/cpa-termux-arm64.tar.gz"
LATEST_URL="https://github.com/${REPO}/releases/latest"
DEFAULT_PORT="8317"

C_RESET="\033[0m"; C_BOLD="\033[1m"; C_DIM="\033[2m"
C_CYAN="\033[1;36m"; C_GREEN="\033[1;32m"; C_YELLOW="\033[1;33m"; C_RED="\033[1;31m"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
BASE_DIR="${HOME}/.cpa"
BIN_DIR="${BASE_DIR}/bin"; LOG_DIR="${BASE_DIR}/logs"; STATIC_DIR="${BASE_DIR}/static"
CONFIG_FILE="${BASE_DIR}/config.yaml"
BIN_PATH="${BIN_DIR}/cli-proxy-api"
LOG_FILE="${LOG_DIR}/service.log"
WRAPPER_PATH="${PREFIX_DIR}/bin/cpa"

step() { printf "%b[%s/5]%b %b%s%b\n" "$C_CYAN" "$1" "$C_RESET" "$C_BOLD" "$2" "$C_RESET"; }
ok()   { printf "      %b✔ %s%b\n" "$C_GREEN" "$1" "$C_RESET"; }
info() { printf "      %b• %s%b\n" "$C_DIM" "$1" "$C_RESET"; }
die()  { printf "      %b❌ %s%b\n\n" "$C_RED" "$1" "$C_RESET" >&2; exit 1; }

find_pids() { pgrep -f "^${BIN_PATH}" 2>/dev/null || true; }
stop_pids() {
    pids=$(find_pids); [ -n "$pids" ] || return 0
    kill $pids 2>/dev/null || true
    i=0; while [ $i -lt 10 ] && [ -n "$(find_pids)" ]; do sleep 0.5; i=$((i+1)); done
    pids=$(find_pids); [ -z "$pids" ] || kill -9 $pids 2>/dev/null || true
}

step 1 "Checking Termux & arch"
[ -d "${PREFIX_DIR}/bin" ] || die "Termux not detected. Run inside Termux."
case "$(uname -m)" in aarch64|arm64) ;; *) die "ARM64 required" ;; esac
ok "Termux ARM64"

step 2 "Dependencies"
for c in curl tar gzip; do command -v "$c" >/dev/null 2>&1 || pkg install -y "$c" || die "need $c"; done
ok "curl/tar/gzip ready"

step 3 "Download & install"
mkdir -p "$BIN_DIR" "$LOG_DIR" "$STATIC_DIR"
[ -z "$(find_pids)" ] || { info "Stopping running instance"; stop_pids; }
TMP=$(mktemp -d "${TMPDIR:-${PREFIX_DIR}/tmp}/cpa.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
TAG=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$LATEST_URL" 2>/dev/null | sed -n 's#.*/tag/##p' || true)
info "Release: ${TAG:-latest}"
curl -fsSL "$TAR_URL" -o "$TMP/pkg.tar.gz" || die "download failed"
tar -xzf "$TMP/pkg.tar.gz" -C "$TMP" || die "archive corrupt"
[ -f "$TMP/cli-proxy-api" ] || die "binary missing in archive"
chmod 755 "$TMP/cli-proxy-api"; mv -f "$TMP/cli-proxy-api" "$BIN_PATH"
[ -f "$TMP/management.html" ] && mv -f "$TMP/management.html" "$STATIC_DIR/management.html"
ok "Binary installed: $BIN_PATH"

step 4 "Config"
if [ ! -f "$CONFIG_FILE" ]; then
    cat > "$CONFIG_FILE" <<'EOF'
# CPA config (aligned with upstream CLIProxyAPI)
server:
  host: "0.0.0.0"
  port: 8317
access:
  api-keys:
    - "123456"
EOF
    chmod 600 "$CONFIG_FILE"
    ok "Created $CONFIG_FILE"
else
    info "Kept existing $CONFIG_FILE"
fi

step 5 "Management wrapper"
cat > "${WRAPPER_PATH}.tmp" <<EOF
#!${PREFIX_DIR}/bin/sh
# cpa wrapper (oneapi-android-apk)
BIN="${BIN_PATH}"; CONF="${CONFIG_FILE}"; LOG="${LOG_FILE}"
port() { grep -E '^[[:space:]]+port:' "\$CONF" 2>/dev/null | head -1 | awk '{print \$2}'; }
find_pids() { pgrep -f "^\$BIN" 2>/dev/null || true; }
start_proc() {
    [ -n "\$(find_pids)" ] && { echo "already running"; return 0; }
    cd "${BASE_DIR}" || exit 1
    setsid "\$BIN" -config "\$CONF" </dev/null >>"\$LOG" 2>&1 &
    echo "started pid \$!"
}
stop_proc() { pids=\$(find_pids); [ -n "\$pids" ] && kill \$pids 2>/dev/null || true; echo stopped; }
case "\${1:-}" in
  start) start_proc ;;
  stop) stop_proc ;;
  restart) stop_proc; sleep 1; start_proc ;;
  status) pids=\$(find_pids); [ -n "\$pids" ] && echo "running: \$pids" || echo "stopped"; echo "port: \$(port)"; echo "panel: http://127.0.0.1:\$(port)/management.html" ;;
  logs) tail -f "\$LOG" ;;
  conf) echo "\$CONF" ;;
  *) echo "usage: cpa {start|stop|restart|status|logs|conf}" ;;
esac
EOF
chmod 755 "${WRAPPER_PATH}.tmp"; mv -f "${WRAPPER_PATH}.tmp" "$WRAPPER_PATH"
ok "Wrapper installed: cpa"

echo
printf "%bDone.%b  Commands:\n" "$C_GREEN" "$C_RESET"
printf "  %b$ cpa start%b   %b$ cpa status%b  %b$ cpa logs%b\n" "$C_CYAN" "$C_RESET" "$C_CYAN" "$C_RESET" "$C_CYAN" "$C_RESET"
