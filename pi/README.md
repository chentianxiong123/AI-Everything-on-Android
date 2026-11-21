# Pi — pi coding agent 的安卓发行

在 Android 上运行 [pi coding agent](https://github.com/earendil-works/pi) `v0.18.0`——统一 LLM API + agent 循环 + 文件/命令/编辑工具的 AI coding agent，配社区成熟的 Web UI [pi-web](https://github.com/agegr/pi-web)（8334⭐）。

## APK（推荐）

**打开直接进 WebView**：无配置页，点开即用。MainActivity（18 行）直接启动后台服务 + 进 WebView。后台服务起 pi 服务监听 `127.0.0.1:30141`，WebView 加载聊天界面。

**固定签名**：`pi.keystore`，覆盖安装保留数据。包名 `com.example.pi`，默认端口 30141。

### 内部打法（内嵌 Node.js 运行时）

pi 是 TypeScript/Node 栈，Android 上跑需要 Node.js。解法：

1. **内嵌 Termux arm64 版 Node.js**（`libnode.so`，放 `jniLibs`，走 nativeLibraryDir 执行）。
2. npm 装 pi + pi-web 完整运行时（`node_modules`），打包进 APK `assets/runtime.tar`。
3. 首次启动：Service 从 assets 解压 runtime 到 `files/runtime/`（~971MB），写 `runtime.version` 标记版本。
4. **覆盖安装保留**：若 `runtime.version` 未变，跳过重解压（保留已解压 runtime + 用户数据）。
5. 启动 pi 服务（`node lib/cli.js` 或 pi-web），WebView ↔ `bridge.js` ↔ pi 的 RPC/HTTP 接口通信。

WebView 加载 pi-web 前端（浏览器里聊天、跑 agent、配模型、浏览项目文件）。**加载前先等后端端口就绪**（Node 启动需数秒），否则直接发请求会得到 ERR_CONNECTION_REFUSED 白屏。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`Pi-v0.18.0.apk`）
- **CI 手动触发**：Actions → "Pi Build" → Run workflow（版本默认 `v0.18.0`）
  ```bash
  gh workflow run pi.yml -f version=v0.18.0
  ```
- CI 缓存：npm 包（`actions/cache ~/.npm`）+ gradle。build-apk.sh 下载 Termux node + npm 装 pi + 打包 assets + gradle assembleRelease（固定 keystore 签名）。
  > 注：仓库里没有 `package-lock.json`（用 `npm install --prefix <runtime>` 直接装包），因此 **不能用 `setup-node` 的 `cache: npm`**（找不到 lock 文件会直接报错），改用自建的 `~/.npm` 目录缓存。

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/pi/termux/install.sh | sh
```
Termux 里直接 npm 装 pi + pi-web，命令行跑（Termux 是现成的 Node 环境）。
