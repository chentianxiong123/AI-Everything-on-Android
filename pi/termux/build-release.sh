#!/data/data/com.termux/files/usr/bin/bash
# build-release.sh — 构建 Pi Runner Termux 离线安装包
#
# 产出：pi-termux-arm64.tar.gz
#   内含完整 node_modules + bridge.js + index.html + 安装脚本，
#   离线可用（不依赖 npm registry）。
set -euo pipefail

PREFIX="/data/data/com.termux/files/usr"
VERSION="${PI_VERSION:-}"
WORK="$(mktemp -d)"
STAGE="$WORK/pi-termux"
mkdir -p "$STAGE/lib/node_modules"

echo "==> 收集 pi + 依赖树"
# 从全局 node_modules 拷贝 pi（已含完整依赖树）
PI_SRC="$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent"
if [ ! -d "$PI_SRC" ]; then
  echo "ERROR: pi 未安装（先跑 install.sh）" >&2
  exit 1
fi
mkdir -p "$STAGE/lib/node_modules/@earendil-works"
cp -r "$PI_SRC" "$STAGE/lib/node_modules/@earendil-works/"

echo "==> 裁剪（去 prebuilds/测试/文档）"
find "$STAGE" -type d -name prebuilds -exec rm -rf {} + 2>/dev/null || true
find "$STAGE" -type d \( -name test -o -name tests -o -name docs -o -name .github \) \
  -prune -exec rm -rf {} + 2>/dev/null || true

echo "==> 加入 bridge + 前端"
# bridge.js/index.html 从 pi 源的 assets 取（install.sh 时 pi 包里带）
# 这里从 repo 的 assets 路径拷（构建机场景），失败则从 pi 包里找
BRIDGE_SRC="$(dirname "$0")/../apk/app/src/main/assets/bridge.js"
INDEX_SRC="$(dirname "$0")/../apk/app/src/main/assets/index.html"
if [ -f "$BRIDGE_SRC" ]; then
  cp "$BRIDGE_SRC" "$STAGE/lib/node_modules/@earendil-works/pi-coding-agent/bridge.js"
  cp "$INDEX_SRC" "$STAGE/lib/node_modules/@earendil-works/pi-coding-agent/index.html"
fi

echo "==> 打包"
cd "$WORK"
OUT="/data/data/com.termux/files/home/pi-termux-arm64.tar.gz"
tar -czf "$OUT" -C "$WORK" pi-termux
echo "产物: $OUT ($(du -h "$OUT" | cut -f1))"
rm -rf "$WORK"
