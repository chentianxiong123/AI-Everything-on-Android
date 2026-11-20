# Pi Runner — DeepSeek Harness / pi-coding-agent 安卓版

在安卓手机上运行 [pi-coding-agent](https://github.com/earendil-works/pi)（pi，AI coding agent，12万⭐）。
两种方式：**APK**（免 Termux，装个 App 就用）+ **Termux 裸二进制**。

> pi 官方仓库：`earendil-works/pi`（TypeScript/Node）。本目录把它打包成安卓可用的形态。

---

## 它是什么

pi 是一个"agent harness"——统一的 LLM API + agent 循环 + 工具（读文件/跑命令/编辑代码），
可装扩展、技能、MCP。Pi Runner 把它塞进安卓：

- **APK**：内嵌 Termux arm64 版 Node.js + pi 完整运行时（node_modules + dist），
  WebView 聊天界面 ↔ 本地 bridge.js ↔ pi RPC 引擎。免 Termux、免 root。
- **Termux**：装个 Node + pi，用包装命令起停。

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
   npm 装 pi（`--ignore-scripts`，pi 无需 native），裁剪后打成 `assets/runtime.tar.gz`（约 57MB）。
   **不提交任何二进制到 git**——构建时现下。
2. gradle 编译 Java 壳（WebView + 前台服务）。

### 运行原理

```
APK 启动
  └─ PiService（前台服务）
       ├─ 首次：解压 assets/runtime.tar.gz → filesDir/runtime
       │         （bin/node + lib/*.so + lib/node_modules/pi）
       ├─ 设 LD_LIBRARY_PATH=runtime/lib
       ├─ spawn: runtime/bin/node bridge.js
       │    └─ bridge.js: HTTP :8765 + spawn pi --mode rpc
       └─ WebView 加载 http://127.0.0.1:8765 → 聊天界面
```

- **不用 pi-tui**：pi 的 TUI native 模块在 android 上自动跳过加载（`process.platform==='android'`），
  桥接走 RPC（stdin/stdout JSONL），绕开 TUI。
- 配置 Provider / API Key / 模型，在 App 界面填，启动即用。

---

## 方式二：Termux

```bash
# 安装（Node + pi + 包装命令）
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/oneapi-android-apk/master/pi/termux/install.sh | bash

# 启动（配 API key）
PI_APIKEY=sk-xxx PI_PROVIDER=anthropic PI_MODEL=claude-sonnet-4-5 pi-start

pi-status    # 状态
pi-logs      # 日志
pi-stop      # 停止
```

聊天页：`http://127.0.0.1:8765`（手机浏览器打开）。

### Termux 离线包

```bash
bash pi/termux/build-release.sh
# 产物：~/pi-termux-arm64.tar.gz（内含完整 node_modules，离线可用）
```

---

## 配置

| 环境变量 / UI 字段 | 说明 | 默认 |
|---|---|---|
| `PI_PORT` / 端口 | bridge 监听端口 | 8765 |
| `PI_PROVIDER` / Provider | `anthropic` `openai` `deepseek` `openrouter` `groq` 等 | anthropic |
| `PI_APIKEY` / API Key | 对应 provider 的 key | 空 |
| `PI_MODEL` / 模型 | 模型 ID | claude-sonnet-4-5 |

API Key 只传给 pi（运行时 `--api-key` 或对应 `*_API_KEY` 环境变量），不落盘明文（仅 App 私有目录）。

---

## 体积

- APK 下载：约 60-70MB（runtime.tar.gz 57MB 压缩 + Java 壳）
- 装机后：约 250MB（node 49MB + 依赖 .so 48MB + pi node_modules 150MB）

---

## CI

见 `.github/workflows/pi.yml`（自动构建 APK + Termux 包）。
Release 见 tag 触发的 `release.yml`。

---

## 已知限制

- 首次启动解压 runtime 较慢（57MB），后续秒起。
- pi 部分工具（剪贴板 native、X11）在安卓上自动降级，不影响核心 agent 循环。
- WebView 聊天界面是精简版，复杂多步 agent 任务建议连桌面端用。
