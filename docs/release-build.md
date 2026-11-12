# 三平台发布

`build_release.sh` 在当前宿主系统/CPU 架构上构建三份独立产物，不做跨系统编译。
Windows 使用 Git Bash。Desktop 构建使用 `JAVA_HOME` 指向 **JBR 25 SDK with JCEF**，macOS 也可自动发现 `~/Library/Java/JavaVirtualMachines/jbrsdk_jcef*`。
同一套 JBR SDK 也用于构建 Server 完整版。Gradle 用 `jlink` 生成排除 JCEF 的 Server 运行时。
首次构建还需要 Gradle、Kotlin/Wasm 工具链及项目依赖可下载。

## GitHub Actions 测试打包

任意分支 push 后，`Test Release Packaging` 在 macOS arm64、macOS x64、Linux x64、Linux arm64 和 Windows x64 上分别运行 JVM 测试。
每组用目标架构的 JBR 跑测试，测试通过后调用 `build_release.sh --skip-sign` 打包。Linux 直接运行 Gradle 测试，并强制验证真实的 bubblewrap 沙盒。
也可在 Actions 手动触发。每组各上传三个 Artifact，每个只含一个发行包，保留 7 天。

macOS ARM64 运行 `./gradlew allTests test --continue --no-configuration-cache`，覆盖 Android 宿主、WasmJS 浏览器、iOS 模拟器和 Android 应用单元测试，随后运行打包脚本模拟测试。
其余原生架构组（Linux x64/arm64、Windows x64）运行 `./gradlew jvmTest test -x :app:androidApp:test --continue --no-configuration-cache`。macOS x64 组跳过转译层下的全量测试矩阵，仅执行发布打包与产物架构校验。
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

打包脚本每次打包时自动拉取并解压最新版标准 Obscura（Stealth 浏览器可执行文件 `obscura` 及配套 worker）：
- Desktop：macOS 部署于 `Contents/Resources/`（带 ad-hoc 签名），Linux/Windows 部署于 `resources/`；
- Server：完整版与 API 版均部署于各自根目录下的 `resources/`。
测试与离线打包可通过 `--skip-obscura` 跳过下载。

运行时解析（零设置面）：发布包固定目录（macOS `.app` Contents/Resources、桌面/服务端 `resources/`）→ PATH 开发兜底；落空即不注册 obscura 后端，browser 工具缺席。运行时永远 `mcp --stealth --allow-private-network`（关闭 SSRF 拦截，兼容本机代理 fake-ip DNS），不可配。

## 启动

Desktop：macOS 打开 `.app`；Linux 执行解压目录的 `bin/` 下启动器；Windows 打开目录内 `.exe`。

桌面端每次启动时从 PATH 和常见系统目录检查 Node.js（含 npm 和 npx）、Python 3 和 Git。
三项均可用后才加载工作台。这是本地 MCP、Git 和 Python 工作任务的基础环境；桌面应用本身使用发布包内的 JBR。有缺项时自动尝试安装，可以最小化窗口，让安装继续运行。安装引导也会安装 PyPI MCP 使用的 `uv` 和 `uvx`，这两项不参加启动检查。下面的终端命令适用于需要手动安装的情况。

- macOS：已有 Homebrew 时由应用直接安装。需要安装 Homebrew 时，在终端运行 [Homebrew 官方安装命令](https://brew.sh/)（已装 Homebrew 可跳过）。按提示输入管理员密码，再按 Homebrew 的 Next steps 设置 PATH。然后运行 `brew install node python git uv`，也可以只指定需要的包。

  ```sh
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  ```

- Linux：在引导页复制命令，或用下面的命令下载安装脚本。脚本优先用发行版仓库；没有 Node.js 或 Python 的包时使用官方发布的二进制或源码。若一键安装遇到 sudo 密码提示，改在终端执行。

  ```sh
  curl -fsSL 'https://cdn.jsdelivr.net/gh/lzdev42/mederi@main/scripts/runtime/install.sh' -o install.sh && sudo /bin/bash install.sh
  ```

- Windows：在引导页复制 PowerShell 命令，或在 PowerShell 运行下面两行。脚本使用 Microsoft App Installer 提供的 [WinGet](https://learn.microsoft.com/windows/package-manager/winget/install)，需要时弹出 UAC，并验证机器 PATH。

  ```powershell
  curl.exe -fsSL 'https://cdn.jsdelivr.net/gh/lzdev42/mederi@main/scripts/runtime/install.ps1' -o install.ps1
  if ($LASTEXITCODE -eq 0) { powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 }
  ```

Linux 和 Windows 的脚本只保存在仓库中。应用内一键安装和引导页的复制命令都从 jsDelivr 取构建所对应的 Git 提交。发布前须先推送该提交。两个脚本都接受 `node`、`python`、`git`、`uv`，省略时安装全部。

两种 Server：Unix 执行根目录 `./run.sh`；Windows 执行 `run.bat`。
完整版启动器使用 `runtime/` 下的 Java 25，API 版使用系统 `JAVA_HOME`/`PATH` 的 Java **25 或更高版本**。
API 版保留全部后端依赖，只移除 JVM 与 `static/` 页面资源，不是单个无依赖 JAR。

完整版默认在 `http://localhost:8081/` 提供页面；API 版没有内置页面，根路径返回 404，REST/SSE 保持可用。
两种版本均支持 `MEDERI_SERVER_PORT`、`MEDERI_CONFIG_DIR`、`MEDERI_SERVER_PASSWORD`；
显式设置 `MEDERI_WEBAPP_DIR` 可额外托管外部页面（见 `server/Application.kt`）。

Linux/Windows 的 Desktop 复制 JBR SDK 以保留 JCEF native 辅助文件。完整 Server 只打包链接后的运行时，CI 核对包内没有 JCEF 模块或 CEF 文件。
macOS Desktop 保留原有 `.app` runtime/CEF 补全和 ad-hoc 签名流程，`--skip-sign` 跳过签名。
自包含 JVM 不包含操作系统库。Linux Desktop 的 JCEF 依赖宿主图形环境。

## Gradle 入口和验证

- `:app:desktopApp:createReleaseDistributable`：仅生成当前系统应用目录，不依赖 MSI/DEB 安装工具。
- `:server:installServerBundled`：`server/build/install/server-bundled`，含 Java 25 和内嵌 Web UI。默认使用当前 Gradle 的 Java，独立打包时可用 `-Pmederi.serverJavaHome` 指定另一套 Java 25。
- `:server:installServerApi`：`server/build/install/server-api`，独立 `apiJar`/`apiStartScripts`，不触发 server 的 wasm 页面资源处理任务。

Gradle 入口生成标准 `bin/` 启动器，发布脚本再增加根目录 `run.sh`/`run.bat`、整理目录和压缩。
API JAR 直接取编译类和源资源并排除 `static/**`，与完整版同一次构建或 `--no-clean` 连续构建不会混入旧页面。

`python3 scripts/test-release-packaging.py` 用模拟 Gradle/JBR 验证三平台装配（含 Linux ARM64）、压缩包、启动脚本和相对输出路径，
不启动 APP/server，也不替代各平台真实构建与运行验收。
