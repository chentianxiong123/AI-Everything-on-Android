#!/data/data/com.termux/files/usr/bin/bash
# install.sh — Pi Runner Termux 安装脚本
#
# 用法：
#   curl -fsSL https://raw.githubusercontent.com/chentianxiong123/oneapi-android-apk/master/pi/termux/install.sh | bash
#
# 安装内容：
#   1. Node.js（Termux 官方 arm64 bionic 包，pi 需要 >=22）
#   2. pi（@earendil-works/pi-coding-agent，npm 全局安装）
#   3. pi / pi-start / pi-stop / pi-status / pi-logs 包装命令
set -euo pipefail

PREFIX="/data/data/com.termux/files/usr"
BIN="$PREFIX/bin"
VERSION="${PI_VERSION:-}"

echo "==> [1/3] 安装 Node.js"
pkg install -y nodejs

echo "==> [2/3] 安装 pi ($([ -n "$VERSION" ] && echo "$VERSION" || echo latest))"
npm install -g --ignore-scripts @earendil-works/pi-coding-agent${VERSION:+@$VERSION}

echo "==> [3/3] 写入包装命令"

cat > "$BIN/pi-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# pi-start — 后台启动 pi RPC bridge
set -euo pipefail
DIR="$PREFIX/var/pi"
mkdir -p "$DIR"
PORT="${PI_PORT:-8765}"
PROVIDER="${PI_PROVIDER:-anthropic}"
APIKEY="${PI_APIKEY:-}"
MODEL="${PI_MODEL:-claude-sonnet-4-5}"

cat > "$DIR/pi-config.json" <<JSON
{"port": $PORT, "provider": "$PROVIDER", "apiKey": "$APIKEY", "model": "$MODEL", "runtimeDir": ""}
JSON

# 复制 bridge.js（从 pi 包里带的，或 npm 包 assets）
BRIDGE="$DIR/bridge.js"
if [ ! -f "$BRIDGE" ]; then
  cp "$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/bridge.js" "$BRIDGE" 2>/dev/null \
    || { echo "ERROR: 找不到 bridge.js" >&2; exit 1; }
fi
cp "$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/index.html" "$DIR/" 2>/dev/null || true

# 解压 runtime（node + pi 已在 termux 全局，bridge 直接用）
nohup node "$BRIDGE" > "$DIR/pi.stdout.log" 2>&1 < /dev/null &
echo $! > "$DIR/pi.pid"
echo "pi 启动 PID=$(cat "$DIR/pi.pid") 端口=$PORT"
echo "聊天页: http://127.0.0.1:$PORT"
EOF
chmod +x "$BIN/pi-start"

cat > "$BIN/pi-stop" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
DIR="$PREFIX/var/pi"
PIDFILE="$DIR/pi.pid"
if [ -f "$PIDFILE" ]; then
  pid=$(cat "$PIDFILE")
  kill "$pid" 2>/dev/null || true
  rm -f "$PIDFILE"
  echo "pi 已停止 (PID=$pid)"
else
  echo "pi 未运行"
fi
EOF
chmod +x "$BIN/pi-stop"

cat > "$BIN/pi-status" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
DIR="$PREFIX/var/pi"
PIDFILE="$DIR/pi.pid"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "运行中 (PID=$(cat "$PIDFILE"))"
  port=$(grep -oE '"port": [0-9]+' "$DIR/pi-config.json" 2>/dev/null | grep -oE '[0-9]+' | head -1)
  [ -n "$port" ] && echo "聊天页: http://127.0.0.1:$port"
else
  echo "未运行"
fi
EOF
chmod +x "$BIN/pi-status"

cat > "$BIN/pi-logs" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
DIR="$PREFIX/var/pi"
tail -n 50 "$DIR/pi.stdout.log" 2>/dev/null || echo "无日志"
EOF
chmod +x "$BIN/pi-logs"

echo
echo "==> 安装完成"
echo "用法："
echo "  PI_APIKEY=sk-xxx PI_PROVIDER=anthropic PI_MODEL=claude-sonnet-4-5 pi-start"
echo "  pi-status   # 查状态"
echo "  pi-logs     # 看日志"
echo "  pi-stop     # 停止"
