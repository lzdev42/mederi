# 三平台发布

`build_release.sh` 在当前宿主系统/CPU 架构上构建三份独立产物，不做跨系统编译。
Windows 使用 Git Bash（不是 WSL；WSL 会构建 Linux 包）。构建依赖 JBR **25 SDK with JCEF**，
Linux/Windows 必须配置 `JAVA_HOME`；macOS 也可自动发现 `~/Library/Java/JavaVirtualMachines/jbrsdk_jcef*`。
首次构建还需要 Gradle、Kotlin/Wasm 工具链及项目依赖可下载。

## GitHub Actions 测试打包

任意分支 push 后，`Test Release Packaging` 在 macOS arm64、macOS x64、Linux x64、Linux arm64 和 Windows x64 上分别运行 JVM 测试。
每组使用自身目标架构的 JBR，测试通过后才调用 `build_release.sh --skip-sign` 打包。Linux 直接运行 Gradle 测试，并强制验证真实的 bubblewrap 沙盒。
也可在 Actions 手动触发。每组各上传三个 Artifact，每个只含一个发行包，保留 7 天。

macOS ARM64 运行 `./gradlew allTests test --continue --no-configuration-cache`，同时覆盖 Android 宿主、WasmJS 浏览器、iOS 模拟器和 Android 应用单元测试，随后运行打包脚本模拟测试。
其余组运行 `./gradlew jvmTest test -x :app:androidApp:test --continue --no-configuration-cache`，覆盖当前宿主上的 JVM 测试。
Gradle 从各模块的标准测试源集自动发现测试。在现有测试源集添加测试类或用例，无需改 workflow。
Android 真机测试和需要单独启动服务或配置凭据的 E2E 脚本单独运行。
Android 应用和 InkCompose 设备测试最低需要 Android 14（API 34），与 KBrowser Android 的要求一致。
有已连接的设备或模拟器时，可用 `./gradlew :inkcompose:connectedAndroidDeviceTest` 运行 InkCompose 渲染用例。
测试步骤失败时，各平台上传 Gradle XML 和 HTML 测试报告，产物名为 `test-results-<platform>-<arch>`。

- `Mederi-desktop-<platform>-<arch>`：Desktop
- `Mederi-server-with-wasmjs-<platform>-<arch>`：Server 完整版，带 JVM 和 WasmJS 页面
- `Mederi-server-api-without-wasmjs-<platform>-<arch>`：Server API 版，无 JVM 和 WasmJS 页面

macOS 的 `.app` 先封装成 tar.gz，保留可执行权限和符号链接。

两组 macOS 构建都使用 ARM64 runner。x64 组用 Rosetta 运行
`arch -x86_64 /bin/bash ./build_release.sh --skip-sign`，并使用 x64 版 JBR 25 SDK with JCEF。
Linux ARM64 组使用 `ubuntu-26.04-arm` runner 与 aarch64 版 JBR 25 SDK with JCEF，原生生成 `linux-arm64` 发行包。
workflow 会从空目录安装 MCP 运行时并执行实际命令探测，也会校验 Desktop 启动器及 Desktop、Server 完整版内置 JVM 的架构，再上传产物。
JBR 由 `actions/setup-java` 下载，查询版本时使用 GitHub Actions 自动提供的 token，无需手工配置。

## 本地打包

```bash
./build_release.sh
./build_release.sh --no-clean --skip-sign --output ./release
```

| 产物 | macOS | Linux | Windows |
|---|---|---|---|
| Desktop（带 JVM、Web UI） | `.app` | `Mederi-desktop-linux-<arch>.tar.gz` | `Mederi-desktop-windows-<arch>.zip` |
| Server 完整版（带 JVM、Web UI） | `Mederi-server-bundled-macos-<arch>.tar.gz` | `Mederi-server-bundled-linux-<arch>.tar.gz` | `Mederi-server-bundled-windows-<arch>.zip` |
| Server API 版（无 JVM、无 Web UI） | `Mederi-server-api-macos-<arch>.tar.gz` | `Mederi-server-api-linux-<arch>.tar.gz` | `Mederi-server-api-windows-<arch>.zip` |

`<arch>` 为 `x64` 或 `arm64`；JBR 与宿主必须匹配。`--name` 修改压缩包文件名前缀，
不修改 `.app` 的 bundle 名、应用标识或可执行文件名。脚本不生成 MSI、DEB、DMG 安装包。
绿色版表示解压即用，不表示用户数据存进程序目录：默认数据仍在 `~/.mederi`。

## 启动

Desktop：macOS 打开 `.app`；Linux 执行解压目录的 `bin/` 下启动器；Windows 打开目录内 `.exe`。

桌面端每次启动时检查 Node.js（22+，含 npm 和 npx）、Python（3.10+）和 uv（含 uvx）。缺少组件时点击「一键安装」，
当前用户的运行时装在 `~/.mederi/runtime`（Windows 为 `%USERPROFILE%\.mederi\runtime`）。
Windows 由应用直接调用 PowerShell。三个平台都可以按引导页步骤复制命令，在终端运行应用内置的脚本。
所有组件通过检查前，引导页保持开启，工作台不会加载。
Linux 上脚本需要 Bash、curl 或 wget、tar 和 SHA-256 校验工具。

两种 Server：Unix 执行根目录 `./run.sh`；Windows 执行 `run.bat`。
完整版启动器强制使用包内 JBR，API 版使用系统 `JAVA_HOME`/`PATH` 的 Java **25 或更高版本**。
API 版保留全部后端依赖，只移除 JVM 与 `static/` 页面资源，不是单个无依赖 JAR。
普通 JVM 可运行 API 版，JCEF 浏览器能力需要匹配的 JBR/JCEF 运行环境。

完整版默认在 `http://localhost:8081/` 提供页面；API 版没有内置页面，根路径返回 404，REST/SSE 保持可用。
两种版本均支持 `MEDERI_SERVER_PORT`、`MEDERI_CONFIG_DIR`、`MEDERI_SERVER_PASSWORD`；
显式设置 `MEDERI_WEBAPP_DIR` 可额外托管外部页面（见 `server/Application.kt`）。

Linux/Windows 的 Desktop 与完整 Server 复制完整 JBR SDK，以保留 JCEF native 辅助文件，包体积会比裁剪 JVM 大。
macOS Desktop 保留原有 `.app` runtime/CEF 补全和 ad-hoc 签名流程，`--skip-sign` 跳过签名。
自包含 JVM 不包含操作系统库；Linux 图形桌面/JCEF 仍依赖宿主图形环境，headless 浏览器需 Xvfb。

## Gradle 入口和验证

- `:app:desktopApp:createReleaseDistributable`：仅生成当前系统应用目录，不依赖 MSI/DEB 安装工具。
- `:server:installServerBundled -Pmederi.releaseJavaHome=<JBR SDK home>`：`server/build/install/server-bundled`，含完整 JBR 和内嵌 Web UI。
- `:server:installServerApi`：`server/build/install/server-api`，独立 `apiJar`/`apiStartScripts`，不触发 server 的 wasm 页面资源处理任务。

Gradle 入口生成标准 `bin/` 启动器，发布脚本再增加根目录 `run.sh`/`run.bat`、整理目录和压缩。
API JAR 直接取编译类和源资源并排除 `static/**`，与完整版同一次构建或 `--no-clean` 连续构建不会混入旧页面。

`python3 scripts/test-release-packaging.py` 用模拟 Gradle/JBR 验证三平台装配（含 Linux ARM64）、压缩包、启动脚本和相对输出路径，
不启动 APP/server，也不替代各平台真实构建与运行验收。
