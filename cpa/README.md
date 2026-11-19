# CPA (CLIProxyAPI) Android 发行

在 Android Termux 上运行 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)。

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
CI：`.github/workflows/build.yml`（`cpa/**` 改动自动构建）。
