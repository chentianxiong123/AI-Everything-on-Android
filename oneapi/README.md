# OneAPI — One API 的安卓发行

在 Android 上运行 [One API](https://github.com/songquanpeng/one-api) `v0.6.10`——主流 AI 网关，统一 OpenAI/Claude/Gemini 等接口，支持多用户、令牌、渠道管理。

## APK（推荐）

**打开直接进 WebView**：无配置页、无端口号输入，点开即用。MainActivity（18 行）直接启动后台服务 + 进 WebView，服务监听 `127.0.0.1:3000`，WebView 加载本地页面。

**固定签名**：`oneapi.keystore`（commit 进仓库），所有 build 签名一致 → 覆盖安装（`adb install -r`）保留数据（用户/渠道/令牌配置），杜绝卸载重装。

- 包名 `com.example.oneapi`
- 默认端口 3000
- 默认账号 `root` / 密码 `123456`（首次登录后请修改）

### 内部打法（DNS 劫持）

One API 是 Go 编译的，默认读 `/etc/resolv.conf`（Android 上不存在）做 DNS 解析，会失败。解法：

1. 上游官方静态 arm64 二进制（Go，CGO_ENABLED=0）打包进 `jniLibs/liboneapi.so`。
2. `libdns_hook.so`（LD_PRELOAD）拦截 `open`/`read` 系统调用，把 `/etc/resolv.conf` 重定向到 app 私有的 resolv.conf（指向公共 DNS）。
3. 配合 `GODEBUG=netdns=go=1` 强制 Go 走纯 DNS 解析（不用 cgo resolver）。

执行链：`ProcessBuilder(nativeLibraryDir + "/liboneapi.so")` + `LD_PRELOAD=libdns_hook.so` + `GODEBUG` 环境变量。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`OneAPI-v0.6.10.apk`）
- **CI 手动触发**：Actions → "OneAPI Build" → Run workflow（版本默认 `v0.6.10`）
  ```bash
  gh workflow run oneapi.yml -f version=v0.6.10
  ```
- CI 缓存上游二进制（按 version hash），不重复下载；gradle 缓存（wrapper + 依赖）。

## Termux 裸命令

适合喜欢终端的用户。一键装：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/oneapi/termux/install.sh | sh
```
命令：`oneapi start` / `oneapi stop` / `oneapi status`。官方静态二进制直接跑，靠 Termux 的 `/etc/resolv.conf`。
