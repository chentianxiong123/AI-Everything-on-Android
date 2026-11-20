# Pi Runner — pi coding agent 的安卓 Web UI

在安卓手机上运行 [pi coding agent](https://github.com/earendil-works/pi)（pi，AI coding agent），
用社区成熟的 Web UI [**pi-web**](https://github.com/agegr/pi-web)（8334⭐）。
两种方式：**APK**（免 Termux，装个 App 就用）+ **Termux 裸命令**。

> pi 官方仓库：`earendil-works/pi`（TypeScript/Node）。本目录把它打包成安卓可用的形态。

---

## 它是什么

pi 是一个 AI coding agent（统一 LLM API + agent 循环 + 文件/命令/编辑工具）。
**pi-web** 是它的社区 Web 界面：浏览器里聊天、跑 agent、配模型、浏览项目文件。

Pi Runner 把 **pi-web** 塞进安卓：

- **APK**：内嵌 Termux arm64 版 Node.js + pi-web 完整运行时，WebView 直接加载 pi-web 界面。免 Termux、免 root。
- **Termux**：装个 Node + pi-web，一条命令起服务。

**配置全在 pi-web 的网页里做**（模型 / API Key / 插件 / 技能 / 会话），和官方 pi 完全一致——
它复用 pi 的 `~/.pi/agent` 配置和会话文件，不是阉割版。

---

## 方式一：APK（推荐，免 Termux）

### 构建（需 Android SDK + 宿主 Node/npm）

```bash
cd pi/apk
bash build-apk.sh
# 产物：pi/dist/Pi-latest.apk
```

`build-apk.sh` 会：
1. `scripts/build-runtime.sh` 从 Termux apt 源下载 arm64 bionic 版 Node + 6 个依赖 .so，
   npm 装 `@agegr/pi-web`（自带 pi 全套依赖），裁剪后打成 `assets/runtime.tar`。
   **不提交任何二进制到 git**——构建时现下。
2. gradle 编译 Java 壳（WebView + 前台服务）。

### 运行原理

```
APK 启动
  └─ PiService（前台服务）
       ├─ 首次：解压 assets/runtime.tar → filesDir/runtime
       │         （bin/node + lib/*.so + lib/node_modules/@agegr/pi-web）
       ├─ 设 LD_LIBRARY_PATH=runtime/lib，HOME=filesDir/home
       ├─ spawn: runtime/bin/node .../pi-web/bin/pi-web.js --port 30141
       └─ WebView 加载 http://127.0.0.1:30141 → pi-web 界面
```

- **不需要自建配置页**：pi-web 自带 Web 配置界面，模型 / API Key / 插件全在网页里配。
- **node-pty（web 终端）在安卓不可用**：它是 lazy require + try-catch，加载失败只影响网页终端功能，
  聊天 / 配置 / 文件全部正常。

---

## 方式二：Termux

```bash
# 装 Node + pi-web
pkg install -y nodejs
npm install -g @agegr/pi-web@latest

# 启动（默认 127.0.0.1:30141）
pi-web --no-open
```

手机浏览器打开 `http://127.0.0.1:30141`。首次进 **Models** 面板配 provider / API key。

---

## 配置

配置**全在 pi-web 网页里**（点界面里的 Models / Settings）：
- Provider 登录 / API Key
- 模型选择
- 插件包、技能
- 会话管理

APK 配置页**只需填端口**（默认 30141）。

| 环境变量 | 说明 | 默认 |
|---|---|---|
| `PORT` / 端口 | pi-web 监听端口 | 30141 |
| `PI_WEB_PASSWORD` | 浏览器访问密码（可选） | 无 |

pi-web 默认只绑 `127.0.0.1`（不暴露到局域网），数据存在 app 私有目录 `filesDir/home/.pi/agent`。

---

## 体积

- APK 下载：约 **150MB**（pi-web 带 Next.js/React/xterm 全套，比纯 pi 大）
- 装机后：约 600MB

> 体积偏大是 pi-web 全功能 web UI 的代价。若只要轻量聊天，可后续做裁剪。

---

## CI

见 `.github/workflows/pi.yml`（自动构建 APK）。Release 见 `release.yml`。

---

## 技术文档

- [架构与复用性](docs/ARCHITECTURE.md) — 改了什么、数据流、绕不绕、复用性评估
- [踩坑全记录](docs/DEBUG-LOG.md) — 从零到跑通的 7 版迭代、每版崩在哪、诊断手段

## 已知限制

- 首次启动解压 runtime 较慢（576MB），后续秒起。
- node-pty（网页终端）在安卓 arm64 无预编译二进制，**网页终端功能不可用**；聊天/配置/文件不受影响。
- APK 体积约 150MB（pi-web 全功能 web UI）。
