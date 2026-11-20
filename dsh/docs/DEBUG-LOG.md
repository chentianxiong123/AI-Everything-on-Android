# DSH Android 化 — 全程踩坑记录

> 目标：把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh，284549⭐）
> 像_pi 那样打包成 Android APK。本文记录从调研到 **Termux 跑通**的全程（APK 打包待续）。
> 配套参考：[pi 的踩坑记录](../pi/docs/DEBUG-LOG.md)、[pi 的架构](../pi/docs/ARCHITECTURE.md)。

---

## 0. DSH 是什么

**DeepSeek Harness（dsh）** 是 DeepSeek AI 开源的 agent harness，"Everything is a Plugin"
架构（基于 Cordis）。对标 pi coding agent，但更重、更火（284549⭐ vs pi 的量级）。

- npm 包：`@deepseek-ai/dsh`（CLI，bin: `dsh`）
- **官方内置 Web UI**：`dsh web` → `http://127.0.0.1:3080`（不像 pi 依赖社区 pi-web）
- 版本：0.2.2-rc.5（写作时 latest）

### 参考项目
- **jackwener/DSH-Android**：深度定制的原生 App（Kotlin + 无障碍服务操作手机 + 悬浮球 +
  Shizuku + Android 14 虚拟屏幕 + JNI）。**从源码编译定制 Node（dshnode）+ 所有原生模块 for android**。
- **yhm080315-sudo/dsh-android**：自称免 proot，bionic Node。

---

## 1. 调研：dsh 的形态

### 好消息：官方 Web UI
```
npx @deepseek-ai/dsh web
# Web UI at http://127.0.0.1:3080
```
结构跟 pi 一样（CLI + web UI），理论上能套 pi 的壳（Node 跑 `dsh web` + WebView 加载 3080）。

### 坏消息：原生模块依赖
pi-web 几乎纯 JS（唯一原生是 wasm swc，平台无关）。**dsh 有一堆真原生模块**：
- `node-addon-require-builtin`（cordis-plugin-loader 依赖，**必需**）
- `koffi`（FFI）
- `node-pty`（终端）
- `dsh-subprocess-local`、`@google/genai`、`protobufjs`

这些在 Android 上**没有官方预编译**，是 dsh 比 pi 难的核心原因。

---

## 2. Termux 验证（按 pi 教训：先跑通再打包）

### 2.1 装 dsh
```bash
npm install -g @deepseek-ai/dsh
# added 664 packages in 7m
```
**坑 ①**：npm warn install-scripts blocked —— 原生模块（koffi/node-pty/dsh-subprocess-local 等）
的 install 脚本被跳过（Termux npm 默认禁第三方 install 脚本）。

### 2.2 首次启动失败：缺 require_builtin.node
```
dsh web
# Cannot find module '.../node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node'
```

### 2.3 找预编译包 —— 失败
按策略"能找预编译就找"：
- `node-addon-require-builtin` npm 包里**只有 JS**（lib/index.js），无 .node
- GitHub `deepseek-ai/dsh-node-addon-require-builtin` 的 internal-loader 只支持
  darwin/linux-gnu/musl/win32，**没有 android 平台**
- 无 tags、无 android release

**结论：官方不支持 android 预编译，必须自己编译。**

---

## 3. Termux 编译 node-addon-require-builtin

### 3.1 编译环境（Termux 齐全）
```
clang 21.1.8 / make 4.4.1 / python 3.14.10 / node 26.13.1 / npm 12.2.3
```

### 3.2 获取源码
repo 无 tags，默认分支 master。下载 tarball：
```bash
curl -sL https://github.com/deepseek-ai/dsh-node-addon-require-builtin/archive/refs/heads/master.tar.gz
```
源码结构：`packages/native/`（binding.gyp + src/*.cc，含各平台 runtime_context）。

### 3.3 编译踩坑

**坑 ②：缺 node-addon-api**
```
gyp ERR! Error: Cannot find module 'node-addon-api'
```
修复：`npm install node-addon-api --no-save`（装到 workspace 根 node_modules）。

**坑 ③：product 选错（关键）**
首次编译默认产出 `internal-loader` 产品，dsh 加载报：
```
native binding product mismatch: expected require-builtin, got internal-loader
```
根因：`product_config.h` 默认 `NARB_PRODUCT = NARB_PRODUCT_INTERNAL_LOADER`。
binding.gyp 用 gyp 变量选择：
```python
"narb_product=='require-builtin'" → NARB_PRODUCT=1（require-builtin）
else                              → NARB_PRODUCT=2（internal-loader）
```
修复：编译时传 gyp 变量：
```bash
cd packages/native
npx node-gyp rebuild -- -Dnarb_product=require-builtin
# gyp info ok → build/Release/require_builtin.node
```

### 3.4 放置产物
```bash
DEST=$DSH/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64
mkdir -p $DEST && cp build/Release/require_builtin.node $DEST/
# 清 loader 缓存（之前缓存了错误的 internal-loader 版本）
rm -rf /data/data/com.termux/files/usr/tmp/node-addon-native-custom-loader-*
```

---

## 4. 跑通 dsh web

### 4.1 启动
```bash
dsh web --no-open
# dsh web: http://127.0.0.1:3080/?token=<TOKEN>
```

### 4.2 验证页面（坑 ④：认证靠 cookie）
直接 curl 不带 cookie → `HTTP 401` / `dsh web authentication required`（68 字节）。
token 认证靠 **cookie**（?token= 设置 session cookie 后重定向）。curl 验证需带 cookie jar：
```bash
TOKEN=$(grep -oE "token=[A-Za-z0-9_-]+" ~/dw.log | head -1 | cut -d= -f2)
curl -sL -c ~/cj.txt -b ~/cj.txt "http://127.0.0.1:3080/?token=$TOKEN" > page.html
# 页面字节: 41184
# <title>DeepSeek Harness</title>
```

**✅ DSH web 在 Android（Termux）跑通，返回 41KB 的 DeepSeek Harness 真页面。**

---

## 5. 严重失误：pkill 误杀 sshd

**坑 ⑤（最严重）**：清理残留进程时执行 `pkill -9 -f "dsh"`，而 **`sshd` 命令行含 "dsh"**，
被一起杀 → **手机 Termux SSH 服务被误杀，连接断开**（Connection refused）。

- 违反了 AGENTS.md 明确警告的"pkill -f 匹配到自己人"坑
- 恢复：需在手机 Termux 手动执行 `sshd` 重启

**教训**：杀进程**绝不用宽泛的 `-f "dsh"`**，用精确匹配：
```bash
pgrep -af "bin/dsh web"        # 只匹配 node 的 dsh web 进程
# 或 pgrep -f "bin/dsh" 找 PID 再 kill，排除 sshd
```

---

## 6. 结论与下一步

### 结论
**DSH 在 Android 可行。** 核心原生模块（node-addon-require-builtin）靠 Termux 编译解决，
**不需要 jackwener 那套重量级 NDK 交叉编译**（他编译定制 Node + JNI；我们用现成 Termux node +
只编译缺的原生模块）。

### 其它原生模块状态
- `koffi`/`node-pty`/`dsh-subprocess-local`：dsh web 能起来出页面，说明**核心 web UI 不依赖它们**
  （可能只影响终端/子进程等可选功能）。打包 APK 时按需处理。

### 下一步（打包 APK，待续）
1. 复用 pi 的壳层（PiService/WebViewActivity），改：端口 3080、启动命令 `dsh web`
2. build-runtime.sh 改成：npm 装 dsh + **编译 node-addon-require-builtin**（把 Termux 编译那套
   搬进构建，CI 也用 Termux 交叉编译或 arm64 runner）
3. 验证 WebView 加载 3080

### 可复用 pi 的资产
- 壳层（PiService 排空 stdout、nativeLibraryDir 放 node、WebView 加载）
- build-runtime.sh 骨架（下载 node、soname symlink、打 tar）
- 关键差异：dsh 要多一步编译 node-addon-require-builtin；web 端口 3080；认证 cookie

---

*记录时间：2026-11-20。DSH 在 Termux 跑通（编译 node-addon-require-builtin + dsh web 出页面）。
APK 打包待续。*
