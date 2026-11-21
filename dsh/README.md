# DSH — DeepSeek Harness 的安卓发行

在 Android 上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) `v0.2.2-rc.5`，
用它的官方内置 Web UI（`dsh web`）。

## APK（推荐）

- **打开直接进 WebView**：无配置页，点开即用（后台启动 `dsh web` + WebView 加载，token 自动获取 + 401 自动重试）。
- **固定签名**：`dsh.keystore`，覆盖安装保留数据（已解压的 Node runtime 不重装）。
- 包名 `com.example.dsh`，默认端口 3080。
- 内部打法：Java/WebView 壳 + 内嵌 Termux arm64 Node.js + dsh 完整运行时（含原生模块 NDK 交叉编译）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`DSH-v0.2.2-rc.5.apk`）
- **CI 手动触发**：Actions → "DSH Build" → Run workflow（版本默认 `v0.2.2-rc.5`）
  ```bash
  gh workflow run dsh.yml -f version=v0.2.2-rc.5
  ```

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/dsh/termux/install.sh | sh
dsh web --no-open
```
