#!/usr/bin/env bash
# build-runtime.sh — 下载并打包 DSH Runner 的运行时到 assets/runtime.tar
#
# 产出结构（解压到 app filesDir/runtime）：
#   runtime/
#     bin/node                    ← Termux arm64 bionic node 二进制
#     lib/*.so                    ← node 动态依赖（libicu/openssl/sqlite/c-ares/z/ffi/libc++）
#     lib/node_modules/@deepseek-ai/dsh  ← dsh（官方 CLI + 内置 web UI + 全套依赖）
#
# 全部从 Termux 官方 apt 源 + npm registry 下载，不提交任何二进制到 git。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"           # pi/apk（本脚本在 pi/apk/scripts/ 下）
ASSETS="$HERE/app/src/main/assets"
WORK="${PI_RUNTIME_WORKDIR:-/tmp/pi-runtime-$$}"
TERMUX_BASE="${TERMUX_APT_BASE:-https://packages.termux.dev/apt/termux-main}"
NODE_VERSION="${PI_NODE_VERSION:-26.13.1}"     # 仅日志用；实际从 Packages 索引解析
DSH_VERSION="${DSH_VERSION:-}"                   # 空 = npm latest

mkdir -p "$ASSETS" "$WORK"
STAGE="$WORK/stage"
ROOTFS="$STAGE/runtime"
mkdir -p "$ROOTFS/bin" "$ROOTFS/lib"

echo "=== [1/5] 下载 Termux Packages 索引，解析 nodejs 及依赖 ==="
IDX="$WORK/Packages"
curl -fsSL --retry 3 "$TERMUX_BASE/dists/stable/main/binary-aarch64/Packages.gz" -o "$IDX.gz"
gunzip -f "$IDX.gz"

# 从索引里取某个包的 Filename 字段（deb 相对路径）
# 用字符串精确比较（不用正则），避免 libc++ 这类含 + 的包名在不同 awk 实现下转义不一致
pkg_path() {
  local name="$1"
  awk -v want="Package: $name" '
    $0 == want { found=1; next }
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

# 创建 soname 符号链接：node 的 DT_NEEDED 写的是无 patch 版 soname（如 libz.so.1），
# 但 deb 里放的是带完整版本的实际文件（libz.so.1.4）。Android bionic linker 按
# DT_NEEDED 的确切名字 + LD_LIBRARY_PATH 查找，缺 symlink 就报 "library not found"。
if command -v readelf >/dev/null 2>&1; then
  echo "创建 soname 符号链接（递归扫所有 .so 的依赖）..."
  # 扫所有 .so*（包括 node 本体 + lib/*.so），而不仅是 node：
  # 传递依赖（如 libicuuc.so.79.1 需要 libicudata.so.79）也要 symlink
  { readelf -d "$ROOTFS/bin/node" 2>/dev/null
    for so in "$ROOTFS"/lib/*.so*; do readelf -d "$so" 2>/dev/null; done
  } | grep NEEDED | sed 's/.*\[\(.*\)\].*/\1/' | sort -u | while read -r soname; do
    # libc/libm/libdl 等是 Android 系统库，linker 自带，不处理
    case "$soname" in libc.so|libm.so|libdl.so|libpthread.so|librt.so|libdl_android.so) continue;; esac
    if [ ! -e "$ROOTFS/lib/$soname" ]; then
      base=$(echo "$soname" | sed 's/\.so\(\.[0-9]*\)*$//')   # libicudata
      target=$(find "$ROOTFS/lib" -maxdepth 1 -name "${base}.so.*" -type f | head -1)
      if [ -n "$target" ]; then
        ln -sf "$(basename "$target")" "$ROOTFS/lib/$soname"
        echo "  symlink $soname -> $(basename "$target")"
      else
        echo "  [警告] 找不到 $soname 的目标文件 (base=$base)"
      fi
    fi
  done
else
  echo "[警告] 无 readelf，跳过 soname symlink 创建（可能导致 node 启动失败）"
fi

# 删除重复的版本符号链接目标（保留实际文件即可，tar 不保留符号链接的多种命名）
echo "node + libs 解包完成:"
ls -la "$ROOTFS/bin/"
du -sh "$ROOTFS/lib"

echo "=== [3/5] npm 安装 dsh 到 runtime/lib/node_modules ==="
# dsh（@deepseek-ai/dsh）官方 CLI，自带内置 web UI，装它带上全套依赖。
# APK 只需 spawn `dsh web`、WebView 加载其界面（带 token）。
DSH_SPEC="${DSH_SPEC:-@deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION}}"
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
  "$(command -v node)" "$NPM_CLI" install -g --ignore-scripts --prefix "$ROOTFS" "$DSH_SPEC"
else
  PATH="$HOST_NODE_DIR:$PATH" "$HOST_NPM" install -g --ignore-scripts --prefix "$ROOTFS" "$DSH_SPEC"
fi

DSH_PKG_DIR="$ROOTFS/lib/node_modules/@deepseek-ai/dsh"
if [ ! -f "$DSH_PKG_DIR/lib/bin.js" ]; then
  echo "ERROR: dsh lib/bin.js 未找到于 $DSH_PKG_DIR/lib/bin.js" >&2
  exit 1
fi
echo "dsh bin: $DSH_PKG_DIR/lib/bin.js"

echo "=== [3.5/5] 编译 node-addon-require-builtin（android-arm64，dsh 必需）==="
# dsh 的 cordis-plugin-loader 需要 require_builtin.node（android-arm64）。
# npm 包不带 android 预编译（只 darwin/linux/win32），用 NDK 交叉编译。
# 该 addon 基于 node-addon-api（N-API），无 V8 内部依赖，可交叉编译。
NARBT_SRC="$(echo "$STAGE"/dsh-node-addon-require-builtin-master)"
NARBT_DEST="$DSH_PKG_DIR/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64"
mkdir -p "$NARBT_DEST"
curl -sL https://github.com/deepseek-ai/dsh-node-addon-require-builtin/archive/refs/heads/master.tar.gz \
  | tar xz -C "$STAGE"
NARBT_NATIVE="$NARBT_SRC/packages/native"
if [ ! -d "$NARBT_NATIVE" ]; then
  echo "ERROR: node-addon-require-builtin 源码未找到: $NARBT_NATIVE" >&2
  exit 1
fi
# NDK 交叉编译器（CI 需设置 ANDROID_NDK_HOME；assembleRelease 已依赖 NDK）
NDK_BIN="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk-bundle}/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$NDK_BIN" ]; then
  echo "WARNING: NDK 未找到（$NDK_BIN），跳过交叉编译 → dsh 可能缺 require_builtin.node 而启动失败" >&2
else
  ( cd "$NARBT_NATIVE" \
    && PATH="$HOST_NODE_DIR:$PATH" "$HOST_NPM" install node-addon-api --no-save --ignore-scripts \
    && export CC="$NDK_BIN/aarch64-linux-android21-clang" \
    && export CXX="$NDK_BIN/aarch64-linux-android21-clang++" \
    && npx node-gyp rebuild --arch=arm64 -- -Dnarb_product=require-builtin \
    && cp build/Release/require_builtin.node "$NARBT_DEST/" ) \
    && echo "require_builtin.node: $(ls -la "$NARBT_DEST/require_builtin.node" 2>/dev/null | awk '{print $5, $9}')" \
    || echo "ERROR: 交叉编译 node-addon-require-builtin 失败" >&2
fi

echo "=== [4/5] 裁剪 node_modules（去测试/文档/非 arm64 prebuilds）==="
# node-pty（web 终端用）在 android 无预编译，但它是 createTerminal 内 lazy require +
# try-catch，加载失败只影响 web 终端，不影响聊天/配置/文件。删掉它的预编译目录减体积。
find "$ROOTFS/lib/node_modules" -type d -path '*node-pty*build*' -exec rm -rf {} + 2>/dev/null || true
find "$ROOTFS/lib/node_modules" -type d -name prebuilds -exec rm -rf {} + 2>/dev/null || true
# 删掉 x86_64 glibc 的 native 模块（sharp/swc）：它们是给“开发者现场 next build”用的，
# 裁剪给"开发者现场 next build"用的 x64 native 模块（dsh 不用 Next，但依赖里可能有，删掉排雷）。
# serve 不编译，不用 swc）。arm64 bionic 加载 x64 .node 会报 ELF 架构错直接崩，删掉零
# 影响（运行时不 require），纯排雷。
find "$ROOTFS/lib/node_modules" -type d \( -name '@next' -o -name '@img' \) -print0 2>/dev/null | while IFS= read -r -d '' d; do
  case "$(basename "$d")" in
    @next) find "$d" -maxdepth 1 -type d -name 'swc-linux-x64-*' -exec rm -rf {} + 2>/dev/null || true ;;
    @img)  find "$d" -maxdepth 1 -type d -name 'sharp-linux-x64*' -exec rm -rf {} + 2>/dev/null || true ;;
  esac
done
# 兼底：直接按包名删（有的 npm 会把 scoped 包拍平）
rm -rf "$ROOTFS/lib/node_modules/@next/swc-linux-x64-gnu" 2>/dev/null || true
rm -rf "$ROOTFS/lib/node_modules/@img/sharp-linux-x64" 2>/dev/null || true
rm -rf "$ROOTFS/lib/node_modules/sharp" 2>/dev/null || true
# 删掉各包的测试/文档目录
find "$ROOTFS/lib/node_modules" -type d \( -name test -o -name tests -o -name docs -o -name .github \) \
  -prune -exec rm -rf {} + 2>/dev/null || true

echo "=== [5/5] 打包 runtime.tar 到 assets ==="
cd "$ROOTFS"
# 条目为 ./bin/node、./lib/... （无 runtime/ 前缀），APK 端 -C runtime 正好解对。
# 注意：aapt2 会把 assets 里的 *.tar.gz 解压并改名为 *.tar，故产出裸 .tar。
tar --format=ustar -cf "$ASSETS/runtime.tar" .
echo "runtime.tar: $(du -h "$ASSETS/runtime.tar" | cut -f1)"

# 把 node 以 libnode.so 名义放进 jniLibs：安装时系统会解压到 nativeLibraryDir，
# 该目录 SELinux 允许 app exec（filesDir 不允许，会 error=13）。参考 jackwener/DSH-Android 做法。
JNI_LIBS="$HERE/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_LIBS"
cp "$ROOTFS/bin/node" "$JNI_LIBS/libnode.so"
echo "libnode.so 已放入 jniLibs: $(du -h "$JNI_LIBS/libnode.so" | cut -f1)"

# 写版本标记（PiService 用它判断是否需要重新解压）
# DSH 首版
# v8 改装到 next/wasm/@next（走 loadWasm(目录) 按文件路径 import → 成功，Termux 实测跑通），
# 强制重解拿到正确位置的 wasm
echo "1" > "$ASSETS/runtime.version"

echo "=== 完成 ==="
echo "产物: $ASSETS/runtime.tar"
du -sh "$ROOTFS"
