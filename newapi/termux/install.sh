#!/bin/sh
# ==============================================================================
# New API for Android (Termux Native, no root)
# Downloads official static arm64 binary from GitHub Release and installs a
# cliproxyapi-style management wrapper into $PREFIX/bin.
# ==============================================================================
set -eu

REPO="${REPO:-chentianxiong123/oneapi-android-apk}"
INSTALLER_URL="https://raw.githubusercontent.com/${REPO}/master/newapi/termux/install.sh"
TAR_URL="https://github.com/${REPO}/releases/latest/download/newapi-termux-arm64.tar.gz"
LATEST_URL="https://github.com/${REPO}/releases/latest"
DEFAULT_PORT="3000"

C_RESET="\033[0m"; C_BOLD="\033[1m"; C_DIM="\033[2m"
C_CYAN="\033[1;36m"; C_GREEN="\033[1;32m"; C_YELLOW="\033[1;33m"; C_RED="\033[1;31m"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
BASE_DIR="${HOME}/.newapi"
BIN_DIR="${BASE_DIR}/bin"; LOG_DIR="${BASE_DIR}/logs"
CONFIG_FILE="${BASE_DIR}/env.conf"
BIN_PATH="${BIN_DIR}/new-api"
LOG_FILE="${LOG_DIR}/service.log"
WRAPPER_PATH="${PREFIX_DIR}/bin/newapi"

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
mkdir -p "$BIN_DIR" "$LOG_DIR"
[ -z "$(find_pids)" ] || { info "Stopping running instance"; stop_pids; }
TMP=$(mktemp -d "${TMPDIR:-${PREFIX_DIR}/tmp}/newapi.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
TAG=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$LATEST_URL" 2>/dev/null | sed -n 's#.*/tag/##p' || true)
info "Release: ${TAG:-latest}"
curl -fsSL "$TAR_URL" -o "$TMP/pkg.tar.gz" || die "download failed"
tar -xzf "$TMP/pkg.tar.gz" -C "$TMP" || die "archive corrupt"
[ -f "$TMP/new-api" ] || die "binary missing in archive"
chmod 755 "$TMP/new-api"; mv -f "$TMP/new-api" "$BIN_PATH"
ok "Binary installed: $BIN_PATH"

step 4 "Config"
if [ ! -f "$CONFIG_FILE" ]; then
    cat > "$CONFIG_FILE" <<EOF
# New API environment (one KEY=VALUE per line)
PORT=${DEFAULT_PORT}
SQL_DSN=
EOF
    ok "Created $CONFIG_FILE"
else
    info "Kept existing $CONFIG_FILE"
fi

step 5 "Management wrapper"
cat > "${WRAPPER_PATH}.tmp" <<EOF
#!${PREFIX_DIR}/bin/sh
# newapi wrapper (oneapi-android-apk)
BIN="${BIN_PATH}"; CONF="${CONFIG_FILE}"; LOG="${LOG_FILE}"
port() { grep -E '^PORT=' "\$CONF" 2>/dev/null | head -1 | cut -d= -f2; }
start_proc() {
    [ -n "\$(find_pids)" ] && { echo "already running"; return 0; }
    cd "${BASE_DIR}" || exit 1
    . "\$CONF" 2>/dev/null || true
    setsid "\$BIN" --port "\${PORT:-${DEFAULT_PORT}}" </dev/null >>"\$LOG" 2>&1 &
    echo "started pid \$!"
}
find_pids() { pgrep -f "^\$BIN" 2>/dev/null || true; }
stop_proc() { pids=\$(find_pids); [ -n "\$pids" ] && kill \$pids 2>/dev/null || true; echo stopped; }
case "\${1:-}" in
  start) start_proc ;;
  stop) stop_proc ;;
  restart) stop_proc; sleep 1; start_proc ;;
  status) pids=\$(find_pids); [ -n "\$pids" ] && echo "running: \$pids" || echo "stopped"; echo "port: \$(port)"; echo "url: http://127.0.0.1:\$(port)" ;;
  logs) tail -f "\$LOG" ;;
  conf) echo "\$CONF" ;;
  *) echo "usage: newapi {start|stop|restart|status|logs|conf}" ;;
esac
EOF
chmod 755 "${WRAPPER_PATH}.tmp"; mv -f "${WRAPPER_PATH}.tmp" "$WRAPPER_PATH"
ok "Wrapper installed: oneapi"

echo
printf "%bDone.%b  Commands:\n" "$C_GREEN" "$C_RESET"
printf "  %b$ newapi start%b   %b$ newapi status%b  %b$ newapi logs%b\n" "$C_CYAN" "$C_RESET" "$C_CYAN" "$C_RESET" "$C_CYAN" "$C_RESET"
