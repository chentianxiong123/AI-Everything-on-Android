# Pi — pi coding agent 的安卓发行

在 Android 上运行 [pi coding agent](https://github.com/earendil-works/pi) `v0.18.0`，
用社区成熟的 Web UI [pi-web](https://github.com/agegr/pi-web)。

## APK（推荐）

- **打开直接进 WebView**：无配置页，点开即用（后台启动 pi 服务 + WebView 加载聊天界面）。
- **固定签名**：`pi.keystore`，覆盖安装保留数据（已解压的 Node runtime 不重装）。
- 包名 `com.example.pi`，默认端口 30141。
- 内部打法：Java/WebView 壳 + 内嵌 Termux arm64 Node.js + pi 完整运行时（WebView ↔ bridge.js ↔ pi RPC）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`Pi-v0.18.0.apk`）
- **CI 手动触发**：Actions → "Pi Build" → Run workflow（版本默认 `v0.18.0`）
  ```bash
  gh workflow run pi.yml -f version=v0.18.0
  ```

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/pi/termux/install.sh | sh
```
