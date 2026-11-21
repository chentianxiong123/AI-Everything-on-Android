# AI-Everything-on-Android

在 Android 上运行主流 **AI 网关 / Agent** 的聚合发行仓库。每个子目录是**完全独立**的一套：有自己的 README、构建脚本、CI workflow、Release。

## 项目一览

| 目录 | 项目 | 上游版本 | APK 包名 | 默认端口 |
|---|---|---|---|---|
| [`oneapi/`](oneapi/) | [One API](https://github.com/songquanpeng/one-api) | `v0.6.10` | `com.example.oneapi` | 3000 |
| [`newapi/`](newapi/) | [New API](https://github.com/QuantumNous/new-api) | `v1.0.0-rc.15` | `com.example.newapi` | 3000 |
| [`cpa/`](cpa/) | [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) | `v8.0.79` | `com.example.cpa` | 8317 |
| [`pi/`](pi/) | [pi coding agent](https://github.com/earendil-works/pi) | `v0.18.0` | `com.example.pi` | 30141 |
| [`dsh/`](dsh/) | [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | `v0.2.2-rc.5` | `com.example.dsh` | 3080 |

5 个 APK 是 5 个独立 app（包名不同），**可共存一台手机**。

## APK 特性

- **打开直接进 WebView**：无配置页、无端口号输入，点开即用（后台自动启动服务 + WebView 加载本地端口）。
- **名字即项目**：OneAPI / NewAPI / CPA / Pi / DSH（去掉 "Runner" 后缀）。
- **固定签名**：每个项目独立 keystore（commit 进仓库），所有 build 签名一致 → **覆盖安装（`adb install -r`）保留数据**，杜绝卸载重装。
- **版本对齐上游**：APK 内嵌的上游二进制/运行时对齐上表版本，不自立版本号。

## 两种分发形态

### APK（推荐）
Java/WebView 壳 + 内嵌运行时，装个 App 就用，免 Termux。

| 项目 | APK 内部打法 |
|---|---|
| oneapi | DNS 劫持（`libdns_hook.so` LD_PRELOAD）+ 上游静态二进制 |
| newapi | DNS 劫持 + 上游静态二进制（CGO_ENABLED=0 编译）|
| cpa | NDK/Bionic 原生编译（走系统 DNS，无需 hook）|
| pi | 内嵌 Termux arm64 Node.js + pi 完整运行时（WebView ↔ bridge.js ↔ pi RPC）|
| dsh | 内嵌 Termux arm64 Node.js + dsh 完整运行时（含原生模块 NDK 交叉编译）|

### Termux 裸命令
Termux 里 `curl ...install.sh | sh` 一键装，直接命令行跑（各项目 `termux/install.sh`）。

## CI / CD（手动触发，build 完直接发 Release）

全部 workflow 在根目录 `.github/workflows/`，**5 个项目完全独立**：

- `oneapi.yml` / `newapi.yml` / `cpa.yml` / `pi.yml` / `dsh.yml`
- **只手动触发**（`workflow_dispatch`），push 绝不触发 CI。
- **版本号对齐上游**：`version` input 默认值 = 上游当前版本（可手填）。
- **处处缓存**：npm / gradle / 上游二进制 / go modules，不重复下载。
- **build 完直接 `gh release create`**（tag = `项目名-版本`），不绕 artifact 中转，Release asset 直链可下载。

### 手动触发
GitHub 仓库 → **Actions** → 选某个 workflow（如 "DSH Build"）→ **Run workflow** → 填版本号（默认上游版本，可直接回车）。

或命令行：
```bash
gh workflow run dsh.yml -f version=v0.2.2-rc.5
```

### Release 下载
每个 build 完成后自动发 Release，asset 直链（`https://github.com/chentianxiong123/AI-Everything-on-Android/releases`）：
- `OneAPI-v0.6.10.apk`
- `NewAPI-v1.0.0-rc.15.apk`
- `CPA-v8.0.79.apk`
- `Pi-v0.18.0.apk`
- `DSH-v0.2.2-rc.5.apk`

## 仓库原则

- **不含任何上游源码和二进制**（CI 构建时下载官方 release / 克隆上游 tag）。
- 顶层只做聚合，每个子目录独立（README / 构建脚本 / CI / Release）。
- 改哪个项目只手动触发哪个 workflow，其它不动。
