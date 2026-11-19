# Android APK 发行（One API / New API）

本项目把 [One API](https://github.com/songquanpeng/one-api) 和 [New API](https://github.com/QuantumNous/new-api) 跑在 Android 上——**无需 Termux**。两套并行维护：

| 目录 | 项目 | 上游关系 |
|---|---|---|
| [`oneapi/`](oneapi/) | One API v0.6.10 | 只下载官方 release 二进制（`download-binary.ps1`），不碰源码 |
| [`newapi/`](newapi/) | New API（基线 commit `12fc0100`） | 官方 release 快速模式 + `patches/` 源码构建模式 |

仓库内容 = 100% 本项目原创：Android 壳、DNS hook、构建脚本、patch、压测文档。**上游源码和编译产物均不入库**（二进制走 Git Release 分发）。

## 结构要点

- 两套 Android 壳各自独立（Java 代码不同），共享同一套技术方案：`lib*.so` 改名复用 + `dns_hook.c`（LD_PRELOAD 重定向 `/etc/resolv.conf`）+ ProcessBuilder 拉起 Go 二进制；
- `newapi/patches/` 记录了 5 处对上游的源码改动，`newapi/scripts/build-from-upstream.sh` 按固定 commit 拉上游→打 patch→编译→丢弃；
- `newapi/mips/` 是全平台压测报告与交叉编译记录（8 台设备横评，含 M5 Note Flyme 7.0）；
- 老设备兼容：`minSdk 16`、`align_fix.py`（ELF TLS 对齐）、Android 4.4 用 `GOOS=linux` 静态 ELF 绕过 bionic `sigfillset` 问题。

## Release

[Releases](https://github.com/chentianxiong123/oneapi-android-apk/releases) 提供 `OneAPI.apk` 和 `NewAPI.apk`，默认面板账号 `root` / `123456`。
