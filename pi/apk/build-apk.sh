#!/usr/bin/env bash
# build-apk.sh — 构建 Pi Runner APK
#
# 步骤：
#   1. build-runtime.sh 下载 Termux node + npm 装 pi，打包成 assets/runtime.tar.gz
#   2. gradle assembleRelease 编译 Java 壳
#   3. 产物拷到 pi/dist/
#
# 环境变量：
#   PI_VERSION        pi npm 版本（空 = latest）
#   PI_NODE_VERSION   仅日志展示
#   TERMUX_APT_BASE   Termux apt 镜像（默认官方源）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"          # pi/apk
PROJ_ROOT="$(cd "$HERE/../.." && pwd)"          # repo root
DIST="$HERE/../dist"
mkdir -p "$DIST"

echo "=== [1/3] 打包 runtime（node + pi）==="
bash "$HERE/scripts/build-runtime.sh"

echo "=== [2/3] gradle 编译 ==="
cd "$HERE"
# 用 wrapper 锁定 gradle 8.11.1（与 AGP 8.9.1 匹配），不依赖 CI 的 apt gradle 版本
if [ -x "$HERE/gradlew" ]; then
  "$HERE/gradlew" -p "$HERE" assembleRelease --no-daemon
elif command -v gradle >/dev/null 2>&1; then
  gradle -p "$HERE" assembleRelease --no-daemon
else
  echo "ERROR: 找不到 gradlew 和 gradle" >&2
  exit 1
fi

echo "=== [3/3] 收集产物 ==="
APK_OUT="$DIST/Pi-latest.apk"
FOUND_APK="$(find "$HERE/app/build/outputs/apk/release" -name '*.apk' | head -1)"
if [ -z "$FOUND_APK" ]; then
  echo "ERROR: 找不到 release APK" >&2
  exit 1
fi
cp "$FOUND_APK" "$APK_OUT"
echo "APK: $APK_OUT ($(du -h "$APK_OUT" | cut -f1))"
