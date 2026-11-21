# NewAPI — New API 的安卓发行

在 Android 上运行 [New API](https://github.com/QuantumNous/new-api) `v1.0.0-rc.15`。

## APK（推荐）

- **打开直接进 WebView**：无配置页，点开即用（后台启动服务 + WebView 加载 `127.0.0.1:3000`）。
- **固定签名**：`newapi.keystore`，覆盖安装保留数据。
- 包名 `com.example.newapi`（与 OneAPI 可共存），默认端口 3000。
- 内部打法：Java 壳 + DNS 劫持 + 上游静态 arm64 二进制（CGO_ENABLED=0 编译，真实 SQLite + 完整前端）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`NewAPI-v1.0.0-rc.15.apk`）
- **CI 手动触发**：Actions → "NewAPI Build" → Run workflow（版本默认 `v1.0.0-rc.15`）
  ```bash
  gh workflow run newapi.yml -f version=v1.0.0-rc.15
  ```

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/newapi/termux/install.sh | sh
newapi start
```
