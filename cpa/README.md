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

### 两个必填的环境变量

- **`MANAGEMENT_PASSWORD=123456`**：CPA v8+ 的 management 路由是**懒注册**的——只有设了 `MANAGEMENT_PASSWORD`（或 config 里的 secret-key）才会注册路由，否则 `/v8/management/*` 全部返 404。Service 启动时固定注入。
- **`MANAGEMENT_STATIC_PATH=<files>/static`**：指向内置的离线管理面板。

### 配置不覆盖

Service 启动时**不覆盖**已有的 `config.yaml`（只在文件不存在时写默认配置），避免把管理面板或从其他设备同步过来的渠道配置抹掉。渠道配置存于 `config.yaml`，改动由 CPA 自己的 file watcher 热重载（无需重启 App）。

### 版本号注入

CI 用 `-ldflags "-X main.Version=<tag> -X main.Commit=<tag>"` 注入版本，界面上显示的是真实版本号而非 `dev`。上游若改这两个变量名，需同步修改 `apk/build-apk.sh`。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`CPA-v8.0.79.apk`）
- **CI 手动触发**：Actions → "CPA Build" → Run workflow（版本默认 `v8.0.79`）
  ```bash
  gh workflow run cpa.yml -f version=v8.0.79
  ```
- CI 缓存：NDK 交叉编译产物（`jniLibs`）+ gradle。
- **上游升级检查点**：ldflags 注入的 `main.Version` / `main.Commit` 变量名。失效表现：版本显示 `dev`。

## Termux 裸命令

使用上游官方 `linux_aarch64_no-plugin` 静态包（musl 兼容，无 libc 依赖，Go resolver 读 Termux 的 `/etc/resolv.conf`）：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/cpa/termux/install.sh | sh
```
命令：`cpa start` / `cpa stop` / `cpa status`。
