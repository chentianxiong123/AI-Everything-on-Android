# AI-Everything-on-Android

在 Android 上运行主流 **AI 网关 / Agent** 的聚合发行仓库。每个子目录是**完全独立**的一套：有自己的 README、构建脚本、CI workflow、Release、keystore。

**目标**：把 One API / New API / CLIProxyAPI / pi coding agent / DeepSeek Harness 这些主流 AI 工具，做成 Android 上一键安装、打开即用的 APK（+ Termux 裸命令），免配置、免终端、覆盖安装保留数据。

---

## 项目一览

| 目录 | 项目 | 上游版本 | APK 包名 | 默认端口 | 内部运行时 |
|---|---|---|---|---|---|
| [`oneapi/`](oneapi/) | [One API](https://github.com/songquanpeng/one-api) | `v0.6.10` | `com.example.oneapi` | 3000 | 上游 Go 静态二进制 |
| [`newapi/`](newapi/) | [New API](https://github.com/QuantumNous/new-api) | `v1.0.0-rc.15` | `com.example.newapi` | 3000 | 上游 Go 静态二进制 |
| [`cpa/`](cpa/) | [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) | `v8.0.79` | `com.example.cpa` | 8317 | NDK/Bionic 原生编译 |
| [`pi/`](pi/) | [pi coding agent](https://github.com/earendil-works/pi) | `v0.18.0` | `com.example.pi` | 30141 | Termux arm64 Node.js |
| [`dsh/`](dsh/) | [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | `v0.2.2-rc.5` | `com.example.dsh` | 3080 | Termux arm64 Node.js |

5 个 APK 是 5 个**独立 app**（包名不同），可共存同一台手机，互不干扰。

---

## APK 架构

每个 APK 都是 **Java/WebView 壳 + 内嵌运行时** 的结构：

```
┌─────────────────────────────────┐
│  WebView UI（打开即见，前端界面）  │
│         ↕ 本地 HTTP              │
│  Java Service（后台守护进程）      │
│    ├── 启动内嵌运行时（node/Go）   │
│    ├── 端口监听 127.0.0.1:port    │
│    └── 前台通知（保活）            │
│         ↕ execve                 │
│  内嵌二进制/运行时                 │
│    （libnode.so / 上游二进制）     │
│    放 nativeLibraryDir（可执行）   │
└─────────────────────────────────┘
```

**打开直接进 WebView**：MainActivity 极简（18 行），`onCreate` 直接 `startForegroundService` 启动后台服务 + `startActivity` 进 WebView，**无配置页、无端口号输入**。后台服务起来监听本地端口，WebView 加载 `http://127.0.0.1:port/`。

### 为什么能执行内嵌二进制（W^X 绕过）

Android 10+ 有 **W^X 政策**：禁止从 `/data/data`（app 私有目录）执行二进制。

**解法**：把二进制改名成 `libxxx.so`（如 `libnode.so`），打包进 APK 的 `jniLibs/` + `extractNativeLibs=true`。Android 安装时自动解压到 `/data/app/.../lib/arm64/`（= `nativeLibraryDir`），这是 Android 唯一允许执行二进制的目录。运行时用 `ProcessBuilder(nativeLibraryDir + "/libnode.so")` 执行。

---

## 各项目内部打法

### One API / New API（Go 网关，DNS 劫持）
- 上游官方静态 arm64 二进制（Go 编译，CGO_ENABLED=0）打包进 `jniLibs`。
- **为什么需要 DNS 劫持**：Go 程序默认读 `/etc/resolv.conf`（Android 上不存在该文件），
  且 CGO_ENABLED=0 的静态包没有 cgo resolver，无法走 Android netd。
- **解法**：`libdns_hook.so`（LD_PRELOAD）拦截 `open`/`fopen`，把 `/etc/resolv.conf`
  重定向到 app 私有的 `resolv.conf`；配合 `GODEBUG=netdns=go=1` 强制 Go 纯 DNS 解析。
- **DNS 列表自动获取（用户零配置）**：
  1. 用 `ConnectivityManager` 读取 netd 为当前网络分配的 DNS（WiFi 切换 / 流量切换自动跟随）；
  2. 实际发一次 UDP/53 查询探测可达性（路由器与部分公共 DNS 会丢弃 UDP 查询）；
  3. 不可达的剔除，并回退公共 DNS（223.5.5.5 / 119.29.29.29 / 180.76.76.76 / 8.8.8.8 / 1.1.1.1）；
  4. 写入 `files/resolv.conf`；Go 纯解析器每次查询都重读该文件，**切网后免重启进程**。
- **开启 Android 私人 DNS（DoT）时**：netd 下发的 DoT 服务器不响应 UDP/53，探测会判定
  不可达并自动回退到公共 DNS，保证解析可用。
- **应急出口（高级用户）**：在 app 数据目录放置 `dns.conf` 可强制指定 DNS，例如
  `1.1.1.1,8.8.8.8`（每行一个或逗号分隔，`#` 开头为注释）。该文件存在时优先于自动探测。
  路径：`/data/data/com.example.oneapi/files/dns.conf`（app 私有目录，需 root 或 adb 才能写入；
  **普通用户无需使用，系统 DNS 已自动接管**）。

### 其它必要的启动参数（one-api / new-api 共性）
- `-log-dir <files>/logs`：上游默认 `./logs`，而 Android 进程 cwd 为 `/`，会导致
  `mkdir /logs: read-only file system` 而启动失败。
- `SQLITE_PATH=<files>/one-api.db`：上游默认 `one-api.db` 相对 cwd，同样落到只读根目录。
- `HOME`、`CUSTOM_RESOLV_CONF`、`LD_PRELOAD`、`GODEBUG` 均由 Service 显式注入。

### CLIProxyAPI（NDK 原生编译）
- **不用 DNS 劫持**：从上游 tag 克隆源码，用 Android NDK 交叉编译（Bionic libc + 系统 DNS netd），原生走 Android DNS。
- 内置 `management.html` 管理前端，Service 释放到 `static/` 并设 `MANAGEMENT_STATIC_PATH`，离线也有管理面板。

### pi / dsh（Node.js 栈 Agent）
- 内嵌 **Termux arm64 版 Node.js**（`libnode.so`）+ 完整 npm 运行时（pi / dsh 的 node_modules）。
- WebView ↔ `bridge.js` ↔ pi/dsh 的 RPC/HTTP 接口通信。
- dsh 有原生模块依赖（node-addon），CI 里用 Android NDK 交叉编译。
- 运行时首次启动从 assets 解压到 `files/runtime/`（~971MB），`runtime.version` 标记版本，覆盖安装时若版本未变则**跳过重解压**（保留数据）。

---

## 两种分发形态

### APK（推荐）
Java/WebView 壳 + 内嵌运行时，**装个 App 就用，免 Termux、免配置**。打开直接进 WebView，后台服务自动启动。

### Termux 裸命令
Termux 里 `curl ...install.sh | sh` 一键装，直接命令行跑。适合喜欢终端的用户。各项目 `termux/install.sh` 提供 start/stop 命令。

---

## CI / CD

全部 workflow 在根目录 `.github/workflows/`，**5 个项目完全独立**：

| Workflow | 触发 | 产物 |
|---|---|---|
| `oneapi.yml` | 手动 `workflow_dispatch` | `OneAPI-<version>.apk` |
| `newapi.yml` | 手动 | `NewAPI-<version>.apk` |
| `cpa.yml` | 手动 | `CPA-<version>.apk` |
| `pi.yml` | 手动 | `Pi-<version>.apk` |
| `dsh.yml` | 手动 | `DSH-<version>.apk` |

### 设计原则

1. **只手动触发**（`workflow_dispatch`），**push 绝不触发 CI**。想跑才跑，不浪费 Actions 额度。
2. **版本号对齐上游**：`version` input 默认值 = 上游当前版本（oneapi `v0.6.10`、pi `v0.18.0` 等），不自立版本号。
3. **build 完直接 `gh release create`**：tag = `项目名-版本`（如 `dsh-v0.2.2-rc.5`），不绕 artifact 中转，Release asset **直链可下载**。需要 `permissions: contents: write`。
4. **处处缓存**：
   - npm 包：`setup-node` 的 `cache: npm`
   - go modules：`setup-go` 的 `cache: true`
   - gradle（wrapper + 依赖）：`actions/cache ~/.gradle`
   - 上游二进制（oneapi `liboneapi.so`）：`actions/cache` 按 version hash
5. **目录独立**：改哪个项目只手动触发哪个 workflow，其它不动。

### 手动触发

GitHub 仓库 → **Actions** → 选 workflow（如 "DSH Build"）→ **Run workflow** → 填版本号（默认上游版本，直接回车即可）。

命令行：
```bash
gh workflow run dsh.yml -f version=v0.2.2-rc.5
```

### 签名机制（覆盖安装保留数据）

每个项目一个**固定 keystore**（commit 进仓库）：
- `oneapi/apk/oneapi.keystore`
- `newapi/apk/newapi.keystore`
- `cpa/apk/cpa.keystore`
- `pi/apk/pi.keystore`
- `dsh/apk/dsh.keystore`

所有 build（CI / 本地）用同一个 keystore 签名 → **覆盖安装（`adb install -r`）永远成功、保留数据**，杜绝卸载重装。这是 Android 覆盖安装的硬要求（同包名 + 同签名）。

5 个 APK 是 5 个独立 app（包名不同），keystore 各自独立即可，无需全局统一。

---

## Release 下载

每个 build 完成后自动发 Release，asset **直链可下载**：
https://github.com/chentianxiong123/AI-Everything-on-Android/releases

- `OneAPI-v0.6.10.apk`
- `NewAPI-v1.0.0-rc.15.apk`
- `CPA-v8.0.79.apk`
- `Pi-v0.18.0.apk`
- `DSH-v0.2.2-rc.5.apk`

国内加速：在直链前加 `https://ghfast.top/`。

---

## 本地构建

每个项目独立构建（以 pi 为例）：
```bash
cd pi/apk
./build-apk.sh          # 下载 Termux node + npm 装 pi + 打包 assets + gradle assembleRelease
```
产物：`app/build/outputs/apk/release/app-release.apk`（用固定 keystore 签名）。

---

## 仓库原则

- **不含任何上游源码和二进制**：CI 构建时下载官方 release / 克隆上游 tag / npm 装。仓库只含壳工程、构建脚本、CI、文档。
- 顶层只做聚合，每个子目录**完全独立**（README / 构建脚本 / CI workflow / keystore / Release）。
- keystore commit 进仓库（固定签名），因是 debug 级别（密码 `android`），非 release 密钥，公开无风险。

---

## 文档索引

- [oneapi/README.md](oneapi/) — One API 打法细节
- [newapi/README.md](newapi/) — New API 打法细节
- [cpa/README.md](cpa/) — CLIProxyAPI NDK 编译
- [pi/README.md](pi/) — pi coding agent Node 运行时
- [dsh/README.md](dsh/) — DeepSeek Harness 原生模块
