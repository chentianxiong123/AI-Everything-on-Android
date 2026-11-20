# DSH Runner — DeepSeek Harness 的安卓化

在安卓上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
（dsh，DeepSeek AI 的开源 agent harness，284549⭐），用它的**官方内置 Web UI**。

> 对标 [pi](../pi/)（pi coding agent 的安卓化）。dsh 结构类似（CLI + web UI），
> 但有原生模块依赖，Android 上需额外编译。

---

## 当前状态

| 阶段 | 状态 |
|---|---|
| Termux 跑通 dsh web | ✅ 完成（编译原生模块 + web 出页面） |
| APK 打包 | ⏳ 待续（复用 pi 壳层） |

---

## 它是什么

- **dsh CLI**：`@deepseek-ai/dsh`（npm），DeepSeek 的 agent harness
- **官方 Web UI**：`dsh web` → `http://127.0.0.1:3080`（内置，不像 pi 依赖社区 pi-web）
- **原生模块**：`node-addon-require-builtin` 等，Android 上无官方预编译，需自行编译

---

## 方式一：Termux（已验证跑通）

### 1. 装 dsh
```bash
npm install -g @deepseek-ai/dsh
```
注：原生模块的 install 脚本会被 Termux npm 跳过，需手动编译（下一步）。

### 2. 编译 node-addon-require-builtin（关键）
```bash
# 下载源码
curl -sL https://github.com/deepseek-ai/dsh-node-addon-require-builtin/archive/refs/heads/master.tar.gz -o narbt.tar.gz
mkdir narbt && tar -xzf narbt.tar.gz -C narbt --strip-components=1 && cd narbt/packages/native

# 装编译依赖
npm install node-addon-api --no-save

# 编译（必须传 require-builtin 产品，默认是 internal-loader 会错）
npx node-gyp rebuild -- -Dnarb_product=require-builtin

# 放到 dsh 期望位置
DSH=$(dirname $(dirname $(npm root -g)/@deepseek-ai/dsh))  # 或 npm root -g 的上级
DEST=$(npm root -g)/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64
mkdir -p $DEST && cp build/Release/require_builtin.node $DEST/

# 清 loader 缓存（避免用旧的错误版本）
rm -rf $TMPDIR/node-addon-native-custom-loader-*
```

### 3. 启动
```bash
dsh web --no-open
# http://127.0.0.1:3080/?token=<TOKEN>
```
浏览器打开带 token 的 URL（token 认证靠 cookie）。验证：
```bash
TOKEN=$(grep -oE "token=[A-Za-z0-9_-]+" ~/dw.log | head -1 | cut -d= -f2)
curl -sL -c cj.txt -b cj.txt "http://127.0.0.1:3080/?token=$TOKEN" | grep -oE "<title>[^<]*</title>"
# <title>DeepSeek Harness</title>
```

---

## 方式二：APK（待续）

复用 [pi 的壳层](../pi/apk/)（PiService/WebViewActivity），差异：
- 端口 3080（pi 是 30141）
- 启动命令 `dsh web`（pi 是 pi-web.js）
- **多一步编译 node-addon-require-builtin**（把上面 Termux 编译搬进 build-runtime.sh）
- web 认证靠 cookie（WebView 自动处理，无需手动）

详见踩坑记录。

---

## 文档

- [全程踩坑记录](docs/DEBUG-LOG.md) — 调研 → Termux 跑通的全过程，含原生模块编译、product 选择、pkill 误杀 sshd 等坑

---

## 已知限制

- **原生模块**：`node-addon-require-builtin` 需自行编译（官方不支持 android 预编译）。
  `koffi`/`node-pty`/`dsh-subprocess-local` 等 dsh web 核心不依赖，但终端/子进程等
  可选功能可能需要，按需编译。
- **pkill 注意**：清理 dsh 进程**不要用 `pkill -f "dsh"`**（会误杀 sshd，sshd 含 "dsh"），
  用精确匹配 `pgrep -f "bin/dsh web"`。
