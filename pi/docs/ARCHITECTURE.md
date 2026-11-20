# Pi Android APK — 架构与复用性

> 配套 [DEBUG-LOG.md](./DEBUG-LOG.md)（踩坑记录）。本文讲：改了什么、数据流怎么走、绕不绕、复用性如何。

---

## 1. 改了哪些源码？

**没有改过任何上游源码。** 全程只写"包装层"胶水。

| 组件 | 改动 | 说明 |
|---|---|---|
| pi-web（@agegr/pi-web） | ❌ 未改 | npm 装的完整包，源码一字未动 |
| pi 引擎（pi-coding-agent） | ❌ 未改 | pi-web 的依赖，npm 自动带 |
| Node.js | ❌ 未改 | 用 Termux 预编译的 arm64 bionic 二进制 |
| Next.js / swc | ❌ 未改 | pi-web 的依赖，原样 |

**我们产出的（全为包装/组装）：**

| 文件 | 职责 |
|---|---|
| `apk/scripts/build-runtime.sh` | 组装 runtime：下载 node+依赖、建 soname symlink、npm 装 pi-web、删 x64 模块、放 wasm swc、打 runtime.tar |
| `apk/app/.../PiService.java` | 解压 runtime、从 nativeLibraryDir 起 node 跑 pi-web、后台线程排空 stdout 防 pipe hang |
| `apk/app/.../MainActivity.java` | 界面（启动/停止按钮） |
| `apk/app/.../WebViewActivity.java` | WebView 加载 `http://127.0.0.1:30141` |
| `apk/app/build.gradle` | `useLegacyPackaging`（.so 解压到 nativeLibraryDir） |
| `apk/app/src/main/jniLibs/arm64-v8a/libnode.so` | node 以 .so 名义进 nativeLibraryDir（构建时生成，已 gitignore） |

---

## 2. 数据流

```
【构建时 — build-runtime.sh】
 1. 下载 Termux node arm64 + 依赖 deb → 解包 stage/runtime
 2. readelf 读 node + 所有 .so 的 DT_NEEDED → 建 soname symlink
    libz.so.1 → libz.so.1.4
    libicudata.so.79 → libicudata.so.79.1
    libicui18n.so.79 / libicuuc.so.79 / libsqlite3.so …
 3. npm install pi-web（自带 pi 引擎 + Next.js + 预编译 .next）
 4. 删 x64 native 模块（swc/sharp，arm64 加载会崩）+ 删测试/文档
 5. npm pack @next/swc-wasm-nodejs → 解压到
    pi-web/node_modules/next/wasm/@next/swc-wasm-nodejs   ★关键位置
 6. tar 打 runtime.tar（条目 ./bin/node ./lib/... 无前缀）
 7. node 二进制 → jniLibs/libnode.so（系统装到 nativeLibraryDir，可 exec）

【运行时 — PiService】
 1. 解压 runtime.tar → filesDir/runtime
 2. 从 nativeLibraryDir/libnode.so 起 node，跑 pi-web.js
    env: LD_LIBRARY_PATH=runtime/lib, HOME=filesDir/home, PI_WEB_NO_OPEN=1
 3. 后台线程持续排空子进程 stdout → pi.stdout.log（防 pipe 满 hang）
 4. next-server 监听 127.0.0.1:30141
 5. WebViewActivity 加载 http://127.0.0.1:30141 → 出界面
```

---

## 3. 关键设计点（为什么这么做）

### 3.1 node 放 nativeLibraryDir（不是 filesDir）
Android SELinux 禁止 `untrusted_app` 从 `filesDir` 执行二进制（`error=13`）。
`nativeLibraryDir`（`.../lib/arm64/`）是唯一允许 app exec 的目录。
做法：node 以 `libnode.so` 名义放 `jniLibs/arm64-v8a/`，系统安装时自动解压到 nativeLibraryDir。
需配 `packagingOptions { jniLibs { useLegacyPackaging = true } }`（否则从 APK mmap，不可 exec）。
参考 jackwener/DSH-Android。**标准解法，非 hack。**

### 3.2 递归 soname symlink
Termux node 是**动态链接**（依赖系统 .so），bionic linker 按 DT_NEEDED 确切名查找。
deb 里库是带完整版本号的实际文件（`libz.so.1.4`），缺无版本 symlink（`libz.so.1`）就报 not found。
需**递归**扫 node + 所有 `.so` 的 DT_NEEDED（漏传递依赖如 libicuuc→libicudata 会崩）。

### 3.3 wasm swc 放 `next/wasm/`（最关键的 workaround）
Android arm64 只有 wasm swc（Next 16 无 android native swc）。同一个 wasm 包：
- 放 `node_modules/@next/` → Next `loadWasm('')` 按包名 ESM import → **崩**（`Reflect.get called on non-object`）
- 放 `next/wasm/@next/swc-wasm-nodejs/` → `loadWasm(目录)` 按文件路径 import → **成功**

这是 `download-swc.js` 的 `outputDirectory` 约定。放这里 Next 检测已存在就跳过下载、
直接走目录加载路径。**依赖 Next 内部目录结构，Next 大版本升级需重新确认。**

### 3.4 排空子进程 stdout
`ProcessBuilder` 默认 stdout 是 pipe，若不读取，缓冲区（64KB）满会让子进程写日志时 block → hang。
PiService 起进程后用后台线程持续读 stdout 写到 `pi.stdout.log`。**调试也靠这个文件。**

---

## 4. 复用性评估

### 高复用（壳层通用）
- `PiService` / `MainActivity` / `WebViewActivity`：**任何"Node web 应用 → APK"可直接复用**，
  只需换 runtime 里装的 npm 包
- `build-runtime.sh` 骨架（下载 node、建 symlink、装 npm 包、打 tar）：改 `PI_WEB_SPEC` 换应用

### 强绑定 pi-web/Next 的部分
- wasm 放 `next/wasm/`：**只适用于 Next.js 应用**。非 Next 应用（Vite/纯 Express/静态站）
  不需要这步，反而更简单

### 各 workaround 的脆弱度

| workaround | 脆弱度 | 说明 |
|---|---|---|
| node 放 nativeLibraryDir | 🟢 稳 | Android 标准解法 |
| 递归 soname symlink | 🟡 中 | Termux node 动态链接特性稳定，换 node 版本需重新生成 |
| 删 x64 模块 | 🟢 稳 | pi-web 打包行为可预测 |
| wasm 放 `next/wasm/` | 🟠 较脆 | 依赖 Next 内部目录约定，Next 升级可能变 |

### 换一个 Node web 应用的成本
1. 改 `build-runtime.sh` 的 `PI_WEB_SPEC` 为目标 npm 包
2. 若目标不是 Next.js：删掉 wasm 预装步骤（3.5），更简单
3. 若目标是 Next.js：保留 wasm 预装，确认其 Next 版本的 swc outputDirectory 仍是 `next/wasm/`
4. 壳层 Java 代码几乎不用动（端口/包名按需改）

---

*配套文档：[DEBUG-LOG.md](./DEBUG-LOG.md)*
