#!/usr/bin/env bash
# Build CPA APK: NDK-compiled cli-proxy-api (Bionic) + Java shell.
#
# CPA upstream needs CGO + Android NDK (Bionic), so DNS is resolved natively
# via Android netd -- no LD_PRELOAD dns_hook needed (unlike OneAPI/NewAPI APKs).
# Upstream source is cloned to a temp dir and never committed.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"          # cpa/apk
UPSTREAM_REPO="https://github.com/router-for-me/CLIProxyAPI.git"
UPSTREAM_TAG="${CPA_TAG:-v8.0.79}"
WORK="${CPA_WORKDIR:-/tmp/cpa-upstream-$$}"
# NDK clang (API 24) -- override via ANDROID_NDK_HOME / ANDROID_CC
NDK_HOME="${ANDROID_NDK_HOME:-/usr/local/android-sdk/ndk/28.2.13676358}"
CC_BIN="${ANDROID_CC:-$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang}"
GOTMP="${GOTMPDIR:-/tmp}"

echo "=== 1/4 Fetch upstream $UPSTREAM_TAG ==="
rm -rf "$WORK"
git clone --quiet --depth 1 --branch "$UPSTREAM_TAG" "$UPSTREAM_REPO" "$WORK"

echo "=== 2/4 NDK cross-compile (CGO_ENABLED=1, GOOS=android, Bionic) ==="
mkdir -p "$HERE/app/src/main/jniLibs/arm64-v8a"
(cd "$WORK" && env GOTMPDIR="$GOTMP" GOCACHE="${GOCACHE:-/tmp/go-build-cpa}" \
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_BIN" \
    go build -buildvcs=false -trimpath -ldflags="-s -w -buildid= -checklinkname=0" \
    -o "$HERE/app/src/main/jniLibs/arm64-v8a/libcpa.so" ./cmd/server/)

echo "=== 3/4 Build APK ==="
(cd "$HERE" && gradle assembleDebug --no-daemon)

echo "=== 4/4 Done ==="
cp "$HERE/app/build/outputs/apk/debug/app-debug.apk" "${OUT_APK:-$HERE/CPA-${UPSTREAM_TAG}.apk}"
ls -lh "${OUT_APK:-$HERE/CPA-${UPSTREAM_TAG}.apk}"
rm -rf "$WORK"
echo "Upstream source removed. No source committed."
