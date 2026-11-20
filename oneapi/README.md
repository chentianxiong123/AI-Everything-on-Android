# One API Android 发行

在 Android 上运行 [One API](https://github.com/songquanpeng/one-api) v0.6.10，两套独立打法：

## APK（`apk/`）
Java 壳 + DNS 劫持（`libdns_hook.so` LD_PRELOAD 重定向 `/etc/resolv.conf`）。包名 `com.example.oneapi`。图标用上游官方 logo。
```bash
cd apk && gradle assembleDebug --no-daemon
```
## Termux 裸二进制（`termux/`）
官方静态 arm64 二进制 + 安装脚本。Termux 里：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/oneapi-android-apk/master/oneapi/termux/install.sh | sh
oneapi start
```
CI：根目录 `oneapi.yml`（改 `oneapi/**` 自动构建 APK + tar.gz）。
