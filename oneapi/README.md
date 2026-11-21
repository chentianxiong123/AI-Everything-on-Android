# OneAPI — One API 的安卓发行

在 Android 上运行 [One API](https://github.com/songquanpeng/one-api) `v0.6.10`。

## APK（推荐）

- **打开直接进 WebView**：无配置页、无端口号输入，点开即用（后台自动启动服务 + WebView 加载 `127.0.0.1:3000`）。
- **固定签名**：`oneapi.keystore`（commit 进仓库），覆盖安装（`adb install -r`）保留数据。
- 包名 `com.example.oneapi`，默认端口 3000。
- 内部打法：Java 壳 + DNS 劫持（`libdns_hook.so` LD_PRELOAD 重定向 `/etc/resolv.conf`）+ 上游静态 arm64 二进制。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`OneAPI-v0.6.10.apk`）
- **CI 手动触发**：Actions → "OneAPI Build" → Run workflow（版本默认 `v0.6.10`）
  ```bash
  gh workflow run oneapi.yml -f version=v0.6.10
  ```

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/oneapi/termux/install.sh | sh
oneapi start
```
