# 上游代码引用说明

本仓库**不包含** New API 的上游源码。所有上游相关的内容只有两类：

## 1. 固定的上游基线

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/QuantumNous/new-api |
| 基线 commit | `12fc010060232e9cf03e947dace7dc521c61c1d9`（2026-06-30, "Bump Electron lockfile dependencies"） |

这个 commit 是 2026-07-01 打包 AP 时 vendored 源码对应的最近上游状态（blob 级比对确认：2167 个文件中仅 7 个文件与基线不同，且这 7 个全属于本项目 patch；差异来自 .agents/skills 等非编译内容）。历史上 README 里写的 "v1.0.0-rc.15" 只对应 release 二进制，不代表源码快照版本。

`scripts/build-from-upstream.sh` 会 clone 这个精确 commit 到临时目录，构建完丢弃。

## 2. 对上游的改动（本仓库的原创内容）

### 补丁文件（`0*.patch`，可用 `git apply` 打到上游基线）

| Patch | 文件 | 目的 |
|---|---|---|
| `001-noembed-embed-tags.patch` | `main.go` | 移除硬编码的 `//go:embed web/dist`，为 build-tag 拆分做准备 |
| `002-sqlite-stub-go-mod.patch` | `go.mod` | `replace github.com/glebarez/sqlite => ./sqlite-stub`，兼容 CGO_ENABLED=0 |
| `003-dns-server-constant.patch` | `common/constants.go` | 新增 `DnsServer` 全局变量 |
| `004-android-defaults-and-dns-env.patch` | `common/init.go` | 从 `DNS_SERVER` 环境变量读取；关闭三个默认限流（压测需要） |
| `005-custom-dns-dialer.patch` | `service/http_client.go` | HTTP transport 用自定义 dialer 走指定 DNS，双保险 |

### 新增文件（`new-files/`，构建时复制到上游目录）

| 文件 | 说明 |
|---|---|
| `main_embed.go` | `//go:build !no_web`：完整前端嵌入 |
| `main_noembed.go` | `//go:build no_web`：无前端（headless，二进制小得多） |
| `sqlite-stub_sqlite.go` / `sqlite-stub_go.mod` | 空 sqlite 驱动（返回 `gorm.ErrInvalidDB`），让纯 Go 交叉编译通过 |

### 其他改动（非 patch 形式）

- `align_fix.py`：对编译产物 ELF 的 PT_TLS 段做 64 字节对齐修复（老 Android linker）；
- `mips/sqlite-cgo/`：MIPS 压测用的 CGo sqlite 适配层，只在 MIPS 场景使用，不参与 APK 构建。

## 升级上游

1. 修改 `scripts/build-from-upstream.sh` 里的 `UPSTREAM_COMMIT`（或传环境变量 `NEWAPI_COMMIT=...`）；
2. 运行脚本；若某个 patch `git apply` 失败，说明上游改了同一处代码——人工看一眼冲突，更新对应 patch；
3. commit 更新后的 patch + 新的 commit pin；
4. 打 APK 跑一次 `mips/` 里的压测端点验证。
