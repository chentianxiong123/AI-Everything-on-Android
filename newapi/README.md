# New API Android 发行

在 Android 上运行 [New API](https://github.com/QuantumNous/new-api) v1.0.0-rc.15，两套独立打法：

## APK（`apk/`）
Java 壳 + DNS 劫持。包名 `com.example.newapi`（与 OneAPI 可共存）。纯净上游二进制（真实 SQLite + 完整前端）。图标用上游官方 logo。
```bash
cd apk && gradle assembleDebug --no-daemon
```
## Termux 裸二进制（`termux/`）
官方静态 arm64 二进制 + 安装脚本：
```sh
curl -fsSL https://raw.githubusercontent.com/chentianxiong123/oneapi-android-apk/master/newapi/termux/install.sh | sh
newapi start
```
## 压测档案
`mips/` 全平台压测报告；`patches/benchmark-archive/` 历史压测 patch（不参与正式构建）。
CI：根目录 `newapi.yml`（改 `newapi/**` 自动构建 APK + tar.gz）。
