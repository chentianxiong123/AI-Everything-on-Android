# DSH — DeepSeek Harness 的安卓发行

在 Android 上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) `v0.2.2-rc.5`——DeepSeek AI 的开源 agent harness（`dsh`），用它的**官方内置 Web UI**（`dsh web`）。

## APK（推荐）

**打开直接进 WebView**：无配置页，点开即用。后台服务起 `dsh web --no-open`（监听 `127.0.0.1:3080`），WebView 加载 + **token 自动获取 + 401 自动重试**。

**固定签名**：`dsh.keystore`，覆盖安装保留数据（已解压 runtime 不重装）。包名 `com.example.dsh`，默认端口 3080。

### 内部打法（Node.js + 原生模块交叉编译）

dsh 是 TypeScript/Node 栈，且有**原生模块依赖**（node-addon），Android 上需额外编译：

1. **内嵌 Termux arm64 版 Node.js**（`libnode.so`）。
2. npm 装 dsh 完整运行时。dsh 依赖的原生模块（如 node-addon-require-builtin）用 **Android NDK 交叉编译**（CI 里 `build-runtime.sh`）。
3. 运行时打包进 APK `assets/runtime.tar`（`noCompress 'tar'` 避免 AAPT 压缩损坏）。首次启动解压到 `files/runtime/`（~971MB），`runtime.version` 标记版本，覆盖安装时版本未变则跳过重解压。
4. 启动 `dsh web`：Service 从 stdout 提取 access token，WebView 带 token 加载。**token 时序 bug 已修**：dsh web 输出 2 个 token（第 1 个过期、第 2 个有效），WebView 401 时自动重读最新 token 重试（最多 3 次）。token 拿不到时额外探测端口（最多再等 15s）再兜底加载。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`DSH-v0.2.2-rc.5.apk`）
- **CI 手动触发**：Actions → "DSH Build" → Run workflow（版本默认 `v0.2.2-rc.5`）
  ```bash
  gh workflow run dsh.yml -f version=v0.2.2-rc.5
  ```
- CI 缓存：npm 包（`actions/cache ~/.npm`）+ gradle。NDK 路径动态检测（预装 NDK，省下载）。
  > 注：仓库里没有 `package-lock.json`（用 `npm install --prefix <runtime>` 直接装包），因此 **不能用 `setup-node` 的 `cache: npm`**（找不到 lock 文件会直接报错），改用自建的 `~/.npm` 目录缓存。

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/dsh/termux/install.sh | sh
dsh web --no-open
```
Termux 里 npm 装 dsh（原生模块用 Termux 的 node-gyp 编译），`dsh web` 起 Web UI。
