# OneAPI — One API 的安卓发行

在 Android 上运行 [One API](https://github.com/songquanpeng/one-api) `v0.6.10`——主流 AI 网关，统一 OpenAI/Claude/Gemini 等接口，支持多用户、令牌、渠道管理。

## APK（推荐）

**打开直接进 WebView**：无配置页、无端口号输入，点开即用。MainActivity（18 行）直接启动后台服务 + 进 WebView，服务监听 `127.0.0.1:3000`，WebView 加载本地页面。

**固定签名**：`oneapi.keystore`（commit 进仓库），所有 build 签名一致 → 覆盖安装（`adb install -r`）保留数据（用户/渠道/令牌配置），杜绝卸载重装。

- 包名 `com.example.oneapi`
- 默认端口 3000
- 默认账号 `root` / 密码 `123456`（首次登录后请修改）

### 内部打法（DNS 劫持 + 系统 DNS 自动接管）

One API 是 Go 编译的，默认读 `/etc/resolv.conf`（Android 上不存在该文件）做 DNS 解析，会失败。解法：

1. 上游官方静态 arm64 二进制（Go，CGO_ENABLED=0）打包进 `jniLibs/liboneapi.so`。
2. `libdns_hook.so`（LD_PRELOAD）拦截 `open`/`fopen`，把 `/etc/resolv.conf` 重定向到 app 私有的 `resolv.conf`。
3. 配合 `GODEBUG=netdns=go=1` 强制 Go 走纯 DNS 解析（不用 cgo resolver）。

**DNS 列表自动获取（用户零配置）**：用 `ConnectivityManager` 读 netd 为当前网络分配的 DNS（切 WiFi/流量自动跟随）→ 实测 UDP/53 可达性（路由器和部分公共 DNS 会丢 UDP 查询）→ 剔除不可达的并回退公共 DNS → 写入 `files/resolv.conf`。Go 纯解析器每次查询都重读该文件，**切网后免重启进程**。

开启 Android 私人 DNS（DoT）时，netd 下发的 DoT 服务器不响应 UDP/53，探测会判定不可达并自动回退公共 DNS。

**应急出口（高级用户）**：在 app 数据目录放 `dns.conf` 可强制指定 DNS（每行一个或逗号分隔，`#` 注释）。路径 `/data/data/com.example.oneapi/files/dns.conf`（app 私有目录，需 root/adb；普通用户无需使用）。

### 上游启动参数（缺一即启动失败）

- `-log-dir <files>/logs`：上游默认 `./logs`，Android 进程 cwd 为 `/` → `mkdir /logs: read-only file system`。
- `SQLITE_PATH=<files>/one-api.db`：上游默认 `one-api.db` 相对 cwd，同样落到只读根目录。
- `HOME` / `CUSTOM_RESOLV_CONF` / `LD_PRELOAD` / `GODEBUG` 均由 Service 显式注入。

### 下载 / 构建

- **Release 直链**：https://github.com/chentianxiong123/AI-Everything-on-Android/releases（`OneAPI-v0.6.10.apk`）
- **CI 手动触发**：Actions → "OneAPI Build" → Run workflow（版本默认 `v0.6.10`）
  ```bash
  gh workflow run oneapi.yml -f version=v0.6.10
  ```
- CI 缓存上游二进制（按 version hash），不重复下载；gradle 缓存（wrapper + 依赖）。
- **上游升级检查点**：`--port` / `-log-dir` 参数名与 `SQLITE_PATH` 环境变量若变更，需同步修改 `apk/app/src/main/java/com/example/oneapi/OneApiService.java`。失效表现：后端启动即退出。

## Termux 裸命令

适合喜欢终端的用户。一键装：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/AI-Everything-on-Android/master/oneapi/termux/install.sh | sh
```
命令：`oneapi start` / `oneapi stop` / `oneapi status`。官方静态二进制直接跑，靠 Termux 的 `/etc/resolv.conf`（无需 DNS hook）。
