#!/usr/bin/env bash
# Fetch upstream New API source at a pinned commit, apply local patches, build liboneapi.so.
# Upstream code is NEVER committed to this repository — it lives in a temp dir and is deleted.
set -euo pipefail

# === Upstream pin ===
UPSTREAM_REPO="https://github.com/QuantumNous/new-api.git"
UPSTREAM_COMMIT="${NEWAPI_COMMIT:-12fc010060232e9cf03e947dace7dc521c61c1d9}"
# The above is the New API commit this project was originally built against (2026-06-30).
# To upgrade: change the pin, run this script, fix any patch conflicts, commit updated patches if needed.

HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${NEWAPI_WORKDIR:-${TMPDIR:-/tmp}/newapi-build-$$}"

echo "=== 1/4 Fetch upstream source (${UPSTREAM_COMMIT:0:12}) ==="
rm -rf "$WORK"
git clone --quiet --filter=blob:none --no-checkout "$UPSTREAM_REPO" "$WORK"
git -C "$WORK" fetch --quiet --depth=1 origin "$UPSTREAM_COMMIT"
git -C "$WORK" checkout --quiet --detach FETCH_HEAD
echo "$UPSTREAM_COMMIT" > "$WORK/.upstream-commit"

echo "=== 2/4 Apply local patches ==="
for p in "$HERE"/patches/0*.patch; do
  echo "  applying $(basename "$p")"
  git -C "$WORK" apply "$p"
done

echo "=== 3/4 Install new files (build-tag embed split + sqlite stub) ==="
mkdir -p "$WORK/sqlite-stub"
cp "$HERE"/patches/new-files/main_embed.go        "$WORK/main_embed.go"
cp "$HERE"/patches/new-files/main_noembed.go      "$WORK/main_noembed.go"
cp "$HERE"/patches/new-files/sqlite-stub_sqlite.go "$WORK/sqlite-stub/sqlite.go"
cp "$HERE"/patches/new-files/sqlite-stub_go.mod    "$WORK/sqlite-stub/go.mod"

TAGS="${NEWAPI_TAGS:-}"                # 默认有网页（嵌入完整前端）；no_web = 无头压测版

echo "=== 3.5/4 Build embedded frontend (headed mode) ==="
if [ -z "$TAGS" ] || ! echo "$TAGS" | grep -q no_web; then
  if [ ! -f "$WORK/web/default/dist/index.html" ]; then
    echo "  building default theme with bun..."
    (cd "$WORK/web/default" && bun install --frozen-lockfile && bun run build)
  fi
fi

echo "=== 4/4 Cross-compile Go -> Android .so ==="
OUT="${1:-$HERE/app/src/main/jniLibs/arm64-v8a/liboneapi.so}"
mkdir -p "$(dirname "$OUT")"
GOARCH="${NEWAPI_GOARCH:-arm64}"
case "$GOARCH" in
  arm64) GOOS_TARGET=android; CLANG="" ;;
  arm)   GOOS_TARGET=linux;  CLANG="" ;;  # Android 4.x: GOOS=linux static ELF avoids bionic sigfillset
esac
echo "GOOS=$GOOS_TARGET GOARCH=$GOARCH CGO_ENABLED=0 tags=$TAGS"
(cd "$WORK" && GOOS="$GOOS_TARGET" GOARCH="$GOARCH" CGO_ENABLED=0 \
   go build -tags "$TAGS" -ldflags="-s -w" -o "$OUT" .)

echo "=== Done ==="
ls -lh "$OUT"
# Optional: fix old-linker TLS alignment on the resulting ELF
if [ "${FIX_TLS_ALIGN:-0}" = "1" ]; then
  python3 "$HERE/align_fix.py" "$OUT"
fi
echo "Upstream working tree kept at: $WORK  (delete manually; no upstream source in this repo)"
