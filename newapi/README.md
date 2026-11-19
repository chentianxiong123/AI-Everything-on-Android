# New API Android APK

在 Android 上运行 [New API](https://github.com/QuantumNous/new-api)（Go 后端 + Web 管理面板）——**无需 Termux**。

本仓库只包含**本项目的原创内容**：Android 壳、DNS hook、构建脚本、patch、压测文档。**上游源码不入库**，构建时按固定 commit 拉取、打 patch、编译后丢弃。

## 目录结构

```
newapi/
├── app/                  Android 壳（Java Service + DNS hook + UI）
├── patches/              对上游源码的全部改动（5 个 .patch + new-files/）
├── scripts/
│   ├── build-from-upstream.sh   ☆ 推荐：拉上游精确 commit → 打 patch → 编 .so
│   └── ...                      其他辅助脚本
├── align_fix.py          老设备 ELF PT_TLS 对齐修复
├── build_from_source.ps1 旧版本地构建（依赖 vendored 源码，保留作参考）
├── download-binary.ps1   下载官方 release 二进制（快速模式）
├── mips/                 ★ 全平台压测报告与交叉编译记录（本项目最有价值的产出）
├── .github/workflows/    CI：下载官方 release → 打 APK
└── gradle*               标准 Gradle Android 工程
```

## 快速使用

1. 安装 [Releases](https://github.com/chentianxiong123/oneapi-android-apk/releases) 里的 APK；
2. 打开 **NewAPI Runner**，设置端口（默认 3000）和 DNS（默认 `8.8.8.8,8.8.4.4`）；
3. 点 **启动**，再点 **管理后台** 打开 `http://127.0.0.1:3000`；默认账号 `root` / `123456`。

## 从源码构建（推荐，上游不入库）

```bash
cd newapi
NEWAPI_TAGS=no_web ./scripts/build-from-upstream.sh   # 生成 app/src/main/jniLibs/arm64-v8a/liboneapi.so
./gradlew assembleDebug                                # 打 APK
```

脚本固定上游 commit `12fc0100`（见 `patches/UPSTREAM.md`）。升级上游：改 commit → 重跑 → 若 patch 冲突就手工调整 `patches/` → 提交。

快速模式（等不及编译）：`download-binary.ps1` 下载官方 arm64 release 二进制，或用 CI workflow 的 `workflow_dispatch`。

## 工作原理

| 组件 | 作用 |
|---|---|
| `liboneapi.so` | New API Go 二进制（CGO_ENABLED=0，arm64 或 linux/arm 静态 ELF；借 Android so 加载机制当独立进程执行） |
| `libdns_hook.so` | LD_PRELOAD 劫持 `/etc/resolv.conf`，重定向到 App 内自定义 DNS |
| `OneApiService.java` | `:daemon` 独立进程里 ProcessBuilder 拉起二进制，注入 `GODEBUG=netdns=go=1` 等环境变量 |
| `MainActivity.java` | 配置 UI（端口 / DNS / env.conf） |
| `WebViewActivity.java` | 内置管理后台浏览器 |

DNS 双保险：`LD_PRELOAD` 文件路径重定向 + `service/http_client.go` patch 里的自定义 dialer（直接用 UI 填的 DNS 服务器发 UDP 53 查询）。

## Android 4.x 兼容

- `minSdk 16`；manifest 移除了 API 28+/33+ 权限；
- Android 4.4 上 `GOOS=android` 二进制报 `cannot locate symbol "sigfillset"` → 用 `GOOS=linux GOARCH=arm` 静态编译绕过；
- `align_fix.py` 修老 linker 的 ELF PT_TLS 64 字节对齐。

详见 `mips/BUILD_MIPS.md` 和 `mips/NEWAPI_ALL_BENCHMARK.md`（8 台设备横评）。
