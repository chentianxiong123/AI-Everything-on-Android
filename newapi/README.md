# NewAPI — New API 的安卓发行

在 Android 上运行 [New API](https://github.com/QuantumNous/new-api) `v1.0.0-rc.15`——One API 的增强 fork，更多渠道类型、更丰富的计费/用户管理。

## APK（推荐）

**打开直接进 WebView**：无配置页，点开即用。后台服务监听 `127.0.0.1:3000`，WebView 加载本地页面。

**固定签名**：`newapi.keystore`，覆盖安装保留数据。包名 `com.example.newapi`（与 OneAPI 可共存同一台手机），默认端口 3000。默认账号 `root` / 密码 `123456`。

### 内部打法（DNS 劫持 + 静态编译）

New API 官方 release 二进制是**动态链接 glibc**，Android/Termux 跑不了。解法：

1. CI 从上游 tag 克隆源码，**静态编译**（`CGO_ENABLED=0 GOOS=linux GOARCH=arm64`），产出静态 arm64 二进制。
2. 二进制打包进 `jniLibs/libnewapi.so`。
3. DNS 劫持同 OneAPI：`libdns_hook.so`（LD_PRELOAD）重定向 `/etc/resolv.conf` + `GODEBUG=netdns=go=1`。

真实 SQLite（数据持久化）+ 完整前端（React）。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`NewAPI-v1.0.0-rc.15.apk`）
- **CI 手动触发**：Actions → "NewAPI Build" → Run workflow（版本默认 `v1.0.0-rc.15`）
  ```bash
  gh workflow run newapi.yml -f version=v1.0.0-rc.15
  ```
- CI 缓存：go modules（`setup-go cache`）+ gradle。

## Termux 裸命令

```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/newapi/termux/install.sh | sh
```
命令：`newapi start` / `newapi stop` / `newapi status`。
