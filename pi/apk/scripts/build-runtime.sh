#!/usr/bin/env bash
# build-runtime.sh — 下载并打包 Pi Runner 的运行时到 assets/runtime.tar
#
# 产出结构（解压到 app filesDir/runtime）：
#   runtime/
#     bin/node                    ← Termux arm64 bionic node 二进制
#     lib/*.so                    ← node 动态依赖（libicu/openssl/sqlite/c-ares/z/ffi/libc++）
#     lib/node_modules/           ← pi + 依赖树
#     pi/dist/...                 ← pi CLI（含 bundle/cli.js）—— 见下方说明
#
# 全部从 Termux 官方 apt 源 + npm registry 下载，不提交任何二进制到 git。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"           # pi/apk（本脚本在 pi/apk/scripts/ 下）
ASSETS="$HERE/app/src/main/assets"
WORK="${PI_RUNTIME_WORKDIR:-/tmp/pi-runtime-$$}"
TERMUX_BASE="${TERMUX_APT_BASE:-https://packages.termux.dev/apt/termux-main}"
NODE_VERSION="${PI_NODE_VERSION:-26.13.1}"     # 仅日志用；实际从 Packages 索引解析
PI_VERSION="${PI_VERSION:-}"                   # 空 = npm latest

mkdir -p "$ASSETS" "$WORK"
STAGE="$WORK/stage"
ROOTFS="$STAGE/runtime"
mkdir -p "$ROOTFS/bin" "$ROOTFS/lib"

echo "=== [1/5] 下载 Termux Packages 索引，解析 nodejs 及依赖 ==="
IDX="$WORK/Packages"
curl -fsSL --retry 3 "$TERMUX_BASE/dists/stable/main/binary-aarch64/Packages.gz" -o "$IDX.gz"
gunzip -f "$IDX.gz"

# 从索引里取某个包的 Filename 字段（deb 相对路径）
# 包名可能含正则特殊字符（如 libc++ 的 +），需先转义
pkg_path() {
  local name="$1"
  local esc; esc="$(printf '%s' "$name" | sed 's/[][\.*^$()+?{}|]/\\&/g')"
  awk -v pkg="^Package: ${esc}\$" '
    $0 ~ pkg { found=1; next }
    found && /^Filename:/ { sub(/^Filename: /,""); print; exit }
    found && /^$/ { found=0 }
  ' "$IDX"
}

download_deb() {
  local name="$1"; local out="$2"
  local rel; rel="$(pkg_path "$name")"
  if [ -z "$rel" ]; then
    echo "ERROR: Packages 索引里找不到 $name" >&2
    return 1
  fi
  # URL 里可能有 + 号（如 libc++），需编码为 %2B
  local enc; enc="$(printf '%s' "$rel" | sed 's/+/%2B/g')"
  echo "  下载 $name: $enc"
  curl -fsSL --retry 3 "$TERMUX_BASE/$enc" -o "$out"
}

# node 运行所需的最小依赖集（node 二进制 NEEDED 的非系统库）
NODE_DEPS=(zlib c-ares libsqlite openssl libicu libffi libc++)

echo "=== [2/5] 下载并解包 node + 依赖 ==="
mkdir -p "$WORK/debs"
download_deb nodejs "$WORK/debs/nodejs.deb"
for dep in "${NODE_DEPS[@]}"; do
  download_deb "$dep" "$WORK/debs/$dep.deb"
done

# 解每个 deb 的 data.tar.xz，抽取 usr/bin/node 和 usr/lib/*.so
extract_deb() {
  local deb="$1"
  local tmp; tmp="$WORK/extract-$$"
  rm -rf "$tmp"; mkdir -p "$tmp"
  ( cd "$tmp" && ar x "$deb" data.tar.xz && tar xf data.tar.xz )
  # node 二进制
  local nodebin; nodebin="$(find "$tmp" -path '*/bin/node' -type f | head -1)"
  if [ -n "$nodebin" ]; then
    cp "$nodebin" "$ROOTFS/bin/node"
    chmod 755 "$ROOTFS/bin/node"
  fi
  # 共享库（含 openssl 的 engines-3/ossl-modules 可选）
  find "$tmp" -path '*/lib/*.so*' -type f | while read -r so; do
    cp -n "$so" "$ROOTFS/lib/" 2>/dev/null || true
  done
  # openssl 模块目录（legacy.so 等）
  find "$tmp" -path '*/lib/ossl-modules' -type d | while read -r d; do
    mkdir -p "$ROOTFS/lib/ossl-modules"
    cp -n "$d"/*.so "$ROOTFS/lib/ossl-modules/" 2>/dev/null || true
  done
  rm -rf "$tmp"
}

extract_deb "$WORK/debs/nodejs.deb"
for dep in "${NODE_DEPS[@]}"; do
  extract_deb "$WORK/debs/$dep.deb"
done

# 清理非必需的大文件（icudata 测试库、icu 工具库、openssl engine 可选）
rm -f "$ROOTFS/lib/libicutest"* "$ROOTFS/lib/libicutu"* 2>/dev/null || true
# 删除重复的版本符号链接目标（保留实际文件即可，tar 不保留符号链接的多种命名）
echo "node + libs 解包完成:"
ls -la "$ROOTFS/bin/"
du -sh "$ROOTFS/lib"

echo "=== [3/5] npm 安装 pi 到 runtime/lib/node_modules ==="
PI_SPEC="@earendil-works/pi-coding-agent${PI_VERSION:+@$PI_VERSION}"
# 关键：不要把 arm64 node 加进 PATH（宿主是 x86，调到 arm64 node 会崩）。
# 用宿主 npm 的绝对路径，仅把目标目录设为 runtime prefix。
HOST_NPM="${NPM_BIN:-$(command -v npm || true)}"
if [ -z "$HOST_NPM" ]; then
  echo "ERROR: 宿主环境找不到 npm" >&2
  exit 1
fi
HOST_NODE_DIR="$(dirname "$(command -v node)")"
mkdir -p "$ROOTFS/lib/node_modules"
# 显式用宿主 node 跑 npm-cli.js，彻底绕开 arm64 node
NPM_CLI="$(dirname "$HOST_NPM")/lib/node_modules/npm/bin/npm-cli.js"
if [ -f "$NPM_CLI" ]; then
  "$(command -v node)" "$NPM_CLI" install -g --ignore-scripts --prefix "$ROOTFS" "$PI_SPEC"
else
  PATH="$HOST_NODE_DIR:$PATH" "$HOST_NPM" install -g --ignore-scripts --prefix "$ROOTFS" "$PI_SPEC"
fi

PI_PKG_DIR="$ROOTFS/lib/node_modules/@earendil-works/pi-coding-agent"
if [ ! -f "$PI_PKG_DIR/dist/bundle/cli.js" ]; then
  echo "ERROR: pi cli.js 未找到于 $PI_PKG_DIR/dist/bundle/cli.js" >&2
  exit 1
fi
echo "pi cli: $PI_PKG_DIR/dist/bundle/cli.js"

echo "=== [4/5] 裁剪 pi node_modules（去测试/文档/非 arm64 prebuilds）==="
# pi 的 pi-tui native 在 android 上不加载，可删 prebuilds 减体积
find "$PI_PKG_DIR" -type d -name prebuilds -exec rm -rf {} + 2>/dev/null || true
# 删掉各包的测试/文档目录
find "$ROOTFS/lib/node_modules" -type d \( -name test -o -name tests -o -name docs -o -name .github \) \
  -prune -exec rm -rf {} + 2>/dev/null || true

echo "=== [5/5] 打包 runtime.tar 到 assets ==="
cd "$STAGE"
# 用标准 ustar 格式（APK 端兜底自解只支持 ustar，不用 GNU 扩展）
# 注意：aapt2 会把 assets 里的 *.tar.gz 解压并改名为 *.tar，导致运行时找不到原文件。
# 因此直接产出裸 .tar（APK 打包时会被 deflate 压缩，最终 APK 体积不受影响）。
tar --format=ustar -cf "$ASSETS/runtime.tar" runtime
echo "runtime.tar: $(du -h "$ASSETS/runtime.tar" | cut -f1)"

# 写版本标记（PiService 用它判断是否需要重新解压）
echo "1" > "$ASSETS/runtime.version"

echo "=== 完成 ==="
echo "产物: $ASSETS/runtime.tar"
du -sh "$ROOTFS"
