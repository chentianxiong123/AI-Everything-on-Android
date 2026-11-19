# CPA (CLIProxyAPI) Android 发行

在 Android 上运行 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) v8.0.79。两套独立打法：

## APK（`apk/`）
Java 壳 + **NDK/Bionic 原生编译**的 cli-proxy-api（API24+ arm64）。跟 OneAPI/NewAPI 不同，这条路线**不需要 DNS 劫持 hook**——NDK 编译版走 Android 系统 DNS（netd）。包名 `com.example.cpa`，默认端口 8317。
```bash
cd apk && ./build-apk.sh     # 克隆上游 tag -> NDK 编译 -> 打 APK
```
UI 里可编辑 `config.yaml`（管理面板 key 默认 `123456`）。上游源码只克隆到临时目录，不入库。

## Termux 裸二进制（`termux/`）
使用上游官方 `linux_aarch64_no-plugin` 静态包（musl 兼容，无 libc 依赖，Go resolver 读 Termux 的 `/etc/resolv.conf`）。
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/oneapi-android-apk/master/cpa/termux/install.sh | sh
cpa start
cpa status
cpa logs
```
端口 8317，管理面板 `http://127.0.0.1:8317/management.html`，默认 key `123456`。
注意：包含内置管理面板；配置文件 `~/.cpa/config.yaml`。
CI：`.github/workflows/build.yml`（`cpa/**` 改动自动构建 APK + Termux 两种产物）。
