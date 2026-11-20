# Pi Android APK 踩坑全记录

> 记录从零把 `@agegr/pi-web` 打包成 Android APK 的全过程，以及**每一版到底崩在哪里**。
> 目标平台：Android arm64（实测小米/红米，MIUI，Android 12）。
> 最终状态：**✅ v8 跑通**。pi-web 在 APK 内正常 serve（`✓ Ready` + `✓ Running next.config.ts`，curl 首页 HTTP 200、`<title>Pi Web</title>`）。经 7 版迭代、踩 5 个工程坑 + 绕 1 个架构坑。

---

## 0. 项目目标

把 [pi coding agent](https://github.com/earendil-works/pi)（Node CLI）做成 Android APK：
- 用 [pi-web](https://github.com/agegr/pi-web)（8334⭐）作为 Web UI，它在浏览器里跑 pi 引擎
- APK = Android Java 壳 + 内嵌 Node 运行时（Termux arm64 bionic 版）+ pi-web
- 启动 APK → 起 Node 进程跑 pi-web（`next start`）→ WebView 加载 `http://127.0.0.1:30141`

---

## 1. 崩溃时间线（按版本）

### v1：tar 前缀错误 → "node 不存在"

**现象**：装上 APK，启动报 `[致命] node 不存在`。

**日志**：
```
tar 解压成功 via tar
[致命] node 不存在: /data/data/com.example.pi/files/runtime/bin/node
```
但 `adb shell ls` 能看到 node 文件——**解压路径错了**。

**根因**：`build-runtime.sh` 打 tar 时从 `stage/` 打包，条目带 `runtime/` 前缀：
```
runtime/bin/node
runtime/lib/...
```
APK 端解压命令 `tar -xf runtime.tar -C filesDir/runtime`，结果解成
`filesDir/runtime/runtime/bin/node`（双层 runtime）。

**修复**：改从 `stage/runtime/` 打包，条目为 `./bin/node`（无前缀）。

---

### v2：SELinux 拒绝 exec filesDir 里的 node（error=13）

**现象**：路径对了，但起进程报权限错误。

**日志**：
```
java.io.IOException: Cannot run program ".../files/runtime/bin/node"
  error=13, Permission denied
```
`adb shell` 能手动跑 node（不同 SELinux 上下文），但 app（`untrusted_app`）不行。

**根因**：Android SELinux 禁止 `untrusted_app` 从 `filesDir` 执行二进制。
**这是 Android 硬性安全策略，绕不过。**

**修复**：参考 [jackwener/DSH-Android](https://github.com/jackwener/DSH-Android)，
把 node 以 `libnode.so` 名义放进 `jniLibs/arm64-v8a/`，系统安装时解压到
`nativeLibraryDir`（`.../lib/arm64/`）——该目录 SELinux **允许** app exec。

同时在 `build.gradle` 加：
```groovy
packagingOptions { jniLibs { useLegacyPackaging = true } }
```
确保 `.so` 解压到 `nativeLibraryDir`（否则非 legacy 模式下从 APK 直接 mmap，不可 exec）。

---

### v3：nativeLibraryDir 目录不存在（ENOENT）

**现象**：改从 `nativeLibraryDir/libnode.so` 执行，但复制 node 时报：
```
FileNotFoundException: .../lib/arm64/node: ENOENT (No such file)
```

**根因**：APK 原本没有 jniLibs，系统**没创建** `nativeLibraryDir` 目录。

**修复**：v2 已改成真放 `libnode.so` 进 jniLibs，系统自动创建目录。此问题随 v2 修复消失。

---

### v4：动态库缺 soname symlink（libz.so.1 not found）

**现象**：node 从 nativeLibraryDir 执行了（`pb.start()` 成功），但立刻退出。

**日志**：
```
F linker: CANNOT LINK EXECUTABLE ".../lib/arm64/libnode.so":
  library "libz.so.1" not found: needed by main executable
```

**根因**：Termux node 是动态链接，DT_NEEDED 写的是无 patch 版 soname（如 `libz.so.1`），
但 deb 里放的是带完整版本的实际文件（`libz.so.1.4`）。Android bionic linker 按
DT_NEEDED 确切名 + `LD_LIBRARY_PATH` 查找，缺 symlink 就报 not found。

**修复**：`build-runtime.sh` 解包后，用 `readelf -d` 读 node 的 DT_NEEDED，
为每个 soname 建符号链接：
```
libz.so.1       -> libz.so.1.4
libsqlite3.so   -> libsqlite3.so.3.54.1
libicui18n.so.79 -> libicui18n.so.79.1
libicuuc.so.79  -> libicuuc.so.79.1
```

---

### v5：传递依赖缺 symlink（libicudata.so.79 not found）

**现象**：node 直接依赖补齐了，但又报另一个库缺失。

**日志**：
```
F linker: CANNOT LINK EXECUTABLE ".../libnode.so":
  library "libicudata.so.79" not found: needed by libicuuc.so.79.1
```

**根因**：v4 只扫了 **node 本体**的 DT_NEEDED，漏了**传递依赖**——
`libicuuc.so.79.1` 自己需要 `libicudata.so.79`。

**修复**：递归扫描**所有** `.so`（node + lib/*.so*）的 DT_NEEDED 建 symlink。
修复后 node 本体 12 个依赖全部解析成功。

---

### v6：x64 native 模块混入（架构错误）

**现象**：node 能起，但要进一步验证 pi-web。

**排查**：查 pi-web 的 node_modules，发现两个 native 模块：
- `@next/swc-linux-x64-gnu/next-swc.linux-x64-gnu.node`（**x86_64 glibc**）
- `@img/sharp-linux-x64/lib/sharp-linux-x64-0.35.5.node`（**x86_64 glibc**）

arm64 Android 加载 x64 .node 会报 ELF 架构错误直接崩。

**结论**：这俩是给"开发者现场 `next build`"用的死代码——pi-web 配了
`images:{unoptimized:true}`（不用 sharp），且带预编译 `.next`（`next start` 只 serve
不编译，不用 swc）。删掉零影响。

**修复**：`build-runtime.sh` 裁剪段删掉这俩包。APK 从 171M 降到 139M。

---

### v7：Next 启动时下载 swc → spawn sh ENOENT（**当前卡点**）

**现象**：node 起来、所有库加载成功（`avc: granted execute`）、next-server
`✓ Ready in 493ms` 并 LISTEN 30141，但**首页白屏 hang**。

**关键日志**（`pi.stdout.log`，PiService 后台线程持续读 stdout 写入）：
```
▲ Next.js 16.4.6
✓ Ready in 493ms
  Downloading swc package @next/swc-wasm-nodejs...
  Downloading swc package @next/swc-android-arm64...
Unhandled Rejection: Error: Failed to get registry from "npm".
  [cause]: Error: spawnSync /data/data/com.termux/files/usr/bin/sh ENOENT
```

**根因链**：
1. Next 16 在 **android arm64** 是 wasm-first target（`knownDefaultWasmFallbackTriples`
   含 `aarch64-linux-android`），启动时 `loadBindings()` 第一步 `loadWasm('')` 找
   `@next/swc-wasm-nodejs`。
2. node_modules 里没有 → Next 调 `npm` 现场下载。
3. 下载要 `spawnSync /bin/sh`（Next 硬编码找 Termux 的 sh 路径），APK 环境无 Termux → ENOENT。
4. Unhandled Rejection → swc 加载失败 → 需要 swc 的请求处理 hang → 白屏。

**尝试的修复**：
- 预装 `@next/swc-wasm-nodejs@16.4.6` 到 `pi-web/node_modules/@next/`，让 Next
  `import('@next/swc-wasm-nodejs')` 第一步就命中，不触发下载。
- **结果**：import 成功了，但 transform 调用崩（见下）。

---

### v7 续：wasm swc transform 本身崩（→ v8 解决）

预装 wasm 包到 `node_modules/@next/` 后，Next 不再下载，但 `loadBindings()` 仍失败。

**手机上诊断**（su 用 runtime node 直接调）：
```
[1] wasm import OK, transform type: function
[2] transform FAIL: TypeError: Reflect.get called on non-object
    at __wbg_Error_83742b46f01ce22d (.../swc-wasm-nodejs/wasm.js)
[3] loadBindings FAIL: Failed to load SWC binary for android/arm64
```

**关键转折：在 Termux 里实测 pi-web（不是 APK）**

直接 `ssh` 到手机 Termux，`npm install -g @agegr/pi-web` 并运行，**居然跑通了**（curl 首页 HTTP 200、`<title>Pi Web</title>`）。对比 Termux 与 APK 的差异：

| | Termux（成功） | APK v7（白屏） |
|---|---|---|
| wasm 位置 | `next/wasm/@next/swc-wasm-nodejs/` | `node_modules/@next/swc-wasm-nodejs/` |
| 加载路径 | `loadWasm(目录)` 按**文件路径** import | `loadWasm('')` 按**包名 ESM import** |
| 结果 | 成功 | 崩（Reflect.get） |

**根因定论**：同一个 wasm 包，`loadWasm(目录)` 按文件路径 `import('file://.../wasm.js')` 能跑；`loadWasm('')` 按包名 ESM import 走 node_modules 解析会崩。`download-swc.js` 的 `outputDirectory` 正是 `next/wasm/@next/swc-wasm-nodejs`。

**v8 修复**：`build-runtime.sh` 把 wasm 解压到 `pi-web/node_modules/next/wasm/@next/swc-wasm-nodejs`（strip-components=1，复刻 Termux）。Next 检测该 outputDirectory 已存在就跳过下载，直接走成功的目录加载路径。

**验证**：手机上把 v7 的 wasm 移到 `next/wasm/`，用 runtime node 起 pi-web，curl 首页 **HTTP 200**、`<title>Pi Web</title>`（11478 字节真 HTML）。打 Pi-v8.apk 装机，**页面正常显示**。

---

## 2. 根因总结

| 层 | 问题 | 能否绕过 |
|---|---|---|
| SELinux | filesDir 不可 exec | ✅ 已绕（放 nativeLibraryDir） |
| 动态库 | 缺 soname symlink | ✅ 已修（递归建链） |
| x64 模块 | 架构不兼容 | ✅ 已删 |
| **Next swc** | android 只能 wasm，按包名 ESM import 崩 | ✅ **已绕**（wasm 放 `next/wasm/`，走目录加载） |

**根因定论**：pi-web 用 Next.js，Next 运行时要加载 swc；Android arm64 只有 wasm swc。
wasm 包本身能跑，但**加载路径决定成败**：按包名 ESM import（`loadWasm('')`）崩，
按文件路径 import（`loadWasm(目录)`）成功。把 wasm 放到 `next/wasm/` 即走成功路径。
**不是架构死结，是加载路径问题，已解决。**

---

## 3. 关键诊断手段（可复用）

### 3.1 看 next-server 真实输出
PiService 起进程后用后台线程持续读 stdout 写文件：
```java
new Thread(() -> {
    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    FileWriter out = new FileWriter(new File(workDir, "pi.stdout.log"), true);
    String line;
    while ((line = r.readLine()) != null) { out.write(line + "\n"); out.flush(); }
}).start();
```
**必须持续排空 pipe**，否则缓冲区满会让子进程写日志时 block → hang。

看真实输出：
```bash
adb shell "su -c 'cat /data/data/com.example.pi/files/pi.stdout.log'"
```

### 3.2 在手机上直接用 runtime node 诊断
```bash
NODE=/data/data/com.example.pi/files/runtime/bin/node
PW=/data/data/com.example.pi/files/runtime/lib/node_modules/@agegr/pi-web
adb shell "su -c 'cd $PW && HOME=... $NODE diag-swc.js'"
```
比刷写 APK 快几个数量级，**调试优先用这个**。

### 3.3 检查 node 动态依赖
```bash
# 列 DT_NEEDED
readelf -d node | grep NEEDED
# 递归验证所有 .so 依赖可解析
for so in lib/*.so*; do readelf -d "$so"; done | grep NEEDED
```

---

## 4. 最终架构评估

### 跑通的部分
- ✅ Termux arm64 node 在 Android APK 内执行（nativeLibraryDir 方案）
- ✅ 所有 bionic 动态库加载（递归 soname symlink）
- ✅ node 进程稳定运行、next-server 监听 30141
- ✅ pi-web 的 Next.js swc 加载（wasm 放 `next/wasm/` 走目录加载）
- ✅ **WebView 正常加载 pi-web 界面（curl HTTP 200、页面显示）**

### 已知无害噪音
- `avc: denied { search } for name="tests"`：SELinux 拒 node 搜 tests 目录，
  被拒但 node 不在乎，`permissive=0` 仅记录，**对功能零影响**
- 其余 `avc: denied`（isolated_app/gmscore/vendor_hal）：系统其它进程的 SELinux 噪音，
  所有 Android 手机都有，与 Pi 无关

### 体积优化方向（可选，当前非必需）
pi-web 方案已跑通，但 152MB 偏大（主要是 Next.js 全套依赖 + wasm swc 6.8MB）。若要瘦身：
- wasm swc 只在编译时用，serve 预编译 `.next` 不触发；可探能否不预装、或换更小 variant
- 删更多 node_modules 冗余（当前只删了 test/docs/x64 prebuilds）
- 退路：甩掉 pi-web，用 pi 引擎本体 + 自建轻量 Express 面板（几 MB，无 Next），
  但会失去 pi-web 的完整界面

---

## 5. 产物

| 文件 | 说明 |
|---|---|
| `pi/apk/scripts/build-runtime.sh` | 下载 Termux node + 依赖、建 soname symlink、装 pi-web、删 x64 模块、预装 wasm swc、打 runtime.tar |
| `pi/apk/app/src/main/jniLibs/arm64-v8a/libnode.so` | node 以 .so 名义进 nativeLibraryDir（构建时生成，已 gitignore） |
| `pi/apk/app/src/main/assets/runtime.tar` | 运行时（无前缀，version=7） |
| `pi/apk/app/src/main/java/com/example/pi/PiService.java` | 起 node 跑 pi-web，捕获 stdout 到 pi.stdout.log |
| `Pi-v8.apk` | **✅ 可用产物**（152M），pi-web 正常 serve，WebView 出页面 |

---

*记录时间：2026-11-20。经 7 版迭代，v8 跑通。*
