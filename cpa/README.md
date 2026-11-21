# CPA — CLIProxyAPI 的安卓发行

在 Android 上运行 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) `v8.0.79`——把 Claude Code / Codex 等 CLI 工具的订阅账号转成 API 代理。

## APK（推荐）

**打开直接进 WebView**：无配置页，点开即用（WebView 加载管理面板）。**固定签名**：`cpa.keystore`，覆盖安装保留数据。包名 `com.example.cpa`，默认端口 8317。

### 内部打法（NDK 原生编译，无需 DNS 劫持）

跟 OneAPI/NewAPI 不同，CPA 走 **NDK/Bionic 原生编译**路线：

1. CI 从上游 tag 克隆源码到临时目录（不入库）。
2. 用 Android NDK 交叉编译（Bionic libc + Android 系统 DNS netd），API 24+ arm64。
3. 二进制打包进 `jniLibs`。**原生走 Android DNS，不需要 DNS 劫持 hook**。

内置 `management.html` 管理前端：Service 释放到 `static/` 并设 `MANAGEMENT_STATIC_PATH`，离线也有面板。WebView 打开 `/management.html` 可编辑 `config.yaml`（管理面板 key 默认 `123456`）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`CPA-v8.0.79.apk`）
- **CI 手动触发**：Actions → "CPA Build" → Run workflow（版本默认 `v8.0.79`）
  ```bash
  gh workflow run cpa.yml -f version=v8.0.79
  ```
- CI 缓存：NDK 交叉编译产物（`jniLibs`）+ gradle。

## Termux 裸命令

使用上游官方 `linux_aarch64_no-plugin` 静态包（musl 兼容，无 libc 依赖，Go resolver 读 Termux 的 `/etc/resolv.conf`）：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/cpa/termux/install.sh | sh
```
命令：`cpa start` / `cpa stop` / `cpa status`。
