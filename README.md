# Android AI Gateway 聚合仓库

在 Android 上运行 **One API / New API / CPA** 的发行仓库。顶层只做聚合，每个子目录是独立的一套：有自己的 README、构建脚本、CI workflow。

| 目录 | 项目 | 打法 | 包名 / 命令 |
|---|---|---|---|
| [`oneapi/`](oneapi/) | One API v0.6.10 | APK（DNS 劫持）+ Termux 裸二进制 | `com.example.oneapi` / `oneapi` |
| [`newapi/`](newapi/) | New API v1.0.0-rc.15 | APK（DNS 劫持）+ Termux 裸二进制 | `com.example.newapi` / `newapi` |
| [`cpa/`](cpa/) | CLIProxyAPI v8.0.79 | APK（NDK/Bionic 原生编译）+ Termux 裸二进制 | `com.example.cpa` / `cpa` |

## 两种打法的区别

- **APK**：Java 壳 + `libdns_hook.so` 用 LD_PRELOAD 劫持 `/etc/resolv.conf`，配合 `GODEBUG=netdns=go=1`。适合普通手机直接安装。
- **Termux 裸二进制**：官方静态 arm64 二进制，不 patch（CPA 用 `no-plugin` 静态包），靠 Termux 的 `/etc/resolv.conf`。安装即 `curl ...install.sh | sh`。

## Release

三个子目录的 CI（`.github/workflows/` 下各自独立）构建产物并上传 artifact。手动发版时把以下文件传到同一个 Release：

- `OneAPI-v0.6.10.apk` / `oneapi-termux-arm64.tar.gz`
- `NewAPI-v1.0.0-rc.15.apk` / `newapi-termux-arm64.tar.gz`
- `CPA-v8.0.79.apk` / `cpa-termux-arm64.tar.gz`

仓库不含任何上游源码和二进制（构建时下载官方 release），所有 patch/压测档案仅作历史参考。
