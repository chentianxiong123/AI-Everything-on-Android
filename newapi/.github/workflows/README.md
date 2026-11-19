# Android APK 通用构建（快速模式）

本目录的 CI 走**快速模式**：直接下载上游官方 release 的 arm64 二进制，改名 `liboneapi.so` 塞进 APK——不编译源码、不需要 Go 工具链。

- 源码构建模式（含自定义 DNS、限流关闭等 patch）：见 `../scripts/build-from-upstream.sh` 和 `../patches/UPSTREAM.md`。
- 想切换 DNS：App UI 里填；快速模式的二进制不含自定义 dialer patch，靠 `dns_hook.c` + `GODEBUG=netdns=go=1` 工作。

构建触发：`workflow_dispatch` 或 push 到 `main` 且改动了 `newapi/**`。
