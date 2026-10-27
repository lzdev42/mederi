# UI 开发与测试工作流

> 本文档从 AGENTS.md 拆出，记录 Compose Hot Reload + MCP UI 测试工作流，以及传统 unit/common test 注意事项。
> 只在做桌面端 UI 开发、UI 测试、或写跨平台测试时读。构建命令与运行方式硬性约束仍在 AGENTS.md §4。

## Compose Hot Reload + MCP（桌面端 UI 开发与测试）

桌面端 UI 开发与测试基于 **Compose Multiplatform Hot Reload 的 MCP server**（CMP 1.12.0+ 内置，配置见仓库根 `opencode.json` 的 `compose-hot-reload` 项）：

1. **启动 server**：通过 idea-mcp 运行 `ApplicationKt` run configuration，启动独立 server（内建 core，监听 8081）。可在环境变量中设 `MEDERI_SERVER_PORT` 指定端口。
2. **启动桌面应用**：通过 bash 运行 `./gradlew :app:desktopApp:hotRun`（必须用 `hotRun` 而非 `run`，否则 MCP 会报 disconnected）。

**MCP UI 测试工具**（AI 自主测试循环：`get_semantic_tree` 理解 UI → `click`/`type_text` 操作 → 验证 → 可配合 `reload`）：

`status` / `get_semantic_tree` / `click` / `long_click` / `type_text` / `scroll` / `scroll_to_index` / `take_screenshot`（需窗口前台，仅桌面）/ `get_logs` / `get_ui_error` / `list_windows` / `reload`

> **注意**：运行前请确认 `app:desktopApp` 的 `build.gradle.kts` 已配置 `compose.desktop.application` 且 `mainClass` 正确。

## 传统 unit / common test 注意事项

- Kotlin/Native 没有 `kotlin.concurrent.Lock`——native 锁用 `kotlin.concurrent.AtomicInt` CAS 或 pthread
- `kotlinx.coroutines.runBlocking` 不可用于 js/wasmJs——此类测试放 `jvm-test/`
- commonTest 里用 Compose UI test（`runComposeUiTest`）需要 `compose.uiTest` 依赖
- 依赖统一走 `gradle/libs.versions.toml`，**只用最新版**，用前查最新官方文档确认 API
