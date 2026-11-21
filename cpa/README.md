# CPA — CLIProxyAPI 的安卓发行

在 Android 上运行 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) `v8.0.79`。

## APK（推荐）

- **打开直接进 WebView**：无配置页，点开即用（后台启动服务 + WebView 加载管理面板）。
- **固定签名**：`cpa.keystore`，覆盖安装保留数据。
- 包名 `com.example.cpa`，默认端口 8317。
- 内部打法：Java 壳 + **NDK/Bionic 原生编译**（走 Android 系统 DNS，无需 hook）。内置 `management.html` 管理前端（离线也有面板）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`CPA-v8.0.79.apk`）
- **CI 手动触发**：Actions → "CPA Build" → Run workflow（版本默认 `v8.0.79`）
  ```bash
  gh workflow run cpa.yml -f version=v8.0.79
  ```

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/cpa/termux/install.sh | sh
cpa start
```
