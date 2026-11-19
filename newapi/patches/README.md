# 补丁目录

- `benchmark-archive/` — 历史压测版的 patch（sqlite-stub / no_web / DNS dialer 等），**仅用于 mips/ 压测复现，不参与正式 APK 构建**。保留供参考，不要删除。
- 正式 APK 使用干净的上游源码（真实 SQLite + 完整前端），不使用任何 patch。
