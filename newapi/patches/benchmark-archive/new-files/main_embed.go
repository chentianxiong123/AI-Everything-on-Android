//go:build !no_web

package main

import "embed"

//go:embed web/default/dist
var buildFS embed.FS

//go:embed web/default/dist/index.html
var indexPage []byte

// classic 主题在本构建中未嵌入（上游 classic 前端依赖冲突，default 主题已完整可用）
var classicBuildFS embed.FS
var classicIndexPage []byte
