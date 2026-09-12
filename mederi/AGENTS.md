# Mederi AGENTS.md

## 0. 架构速查文档（AI 必读入口）

`docs/architecture/` 是由全量代码结构化提取的架构文档（类图/结构图/流程图/时序图 + 穷尽式字段/签名清单）：

- **排查问题先查这里，不要直接通读源码**：模块总览与平台注入矩阵 → `00-overview.md`；core 模块（DI/模型字段/Manager/Store/Koog 引擎/工具/Plan/Provider/MCP/事件/存储表）→ `01-core.md`；AiCore 契约与三实现、RemoteServer 路由表、AppState/VM/UI → `02-app-shared.md`；inkcompose 渲染管线 → `03-inkcompose.md`；运行时流程与时序（发消息全链路/Turn/事件聚合/Plan Loop/压缩/审批/子代理/回滚/自动改名/遥控启动）→ `04-flows.md`。
- **排查工作流（禁止一上来通读/全量搜索源码）**：先按问题域查对应篇章 → 用文档里的类图/流程图/签名清单定位相关类与 `文件路径` → **只打开这几个文件读相关段落**。文档能回答的问题（结构、字段、谁调谁、事件流向、端点、表结构）直接引用文档答案，不再翻源码确认；只有文档未覆盖或疑似与代码不一致的实现细节，才去读文档标注的那几个源码文件。
- 每个类都标注了 `文件路径`；只有文档未覆盖的实现细节才去读对应源码。
- **维护义务（硬性）：每次改动代码，按需同步更新 `docs/architecture/`**，保持文档与代码一致——过时文档比没有文档更误导。改动完成前自检：这次改动是否触碰下列任一项？是 → 更新对应文档：
  - 新增/删除/重命名 **模块、类、接口、方法签名、DTO 字段、枚举值、工具、端点、事件、数据库表/列** → 更新对应篇章（core→`01-core.md`，契约/UI/VM/server→`02-app-shared.md`，inkcompose→`03-inkcompose.md`，总览/平台注入矩阵→`00-overview.md`）；
  - **运行时行为/调用链/流程变化**（新增交互流程、改 sendMessage/turn/压缩/审批/子代理/回滚等链路、新增人机交互挂起点）→ 更新 `04-flows.md` 的流程图/时序图/状态机；
  - 改 `.sq` schema → 同步 `01-core.md` 存储节；改 RemoteServer/ServerAiCore 路由 → 同步 `02-app-shared.md` 路由表（契约四处同步本来就要做，文档顺带）；
  - 纯实现细节修复（内部算法微调、不改对外结构的 bug fix）→ 可不更新；拿不准就更新对应段落，宁多勿漏。
- 本文档描述"结构"，AGENTS.md 描述"规则"——规则类变更（不变量、真理源、硬性约束）写进 AGENTS.md，不写进该目录。

## 1. 定位

Mederi 是 Koog 的易用化、插件化包装库：让开发者快速构建 AI Agent 应用。
Koog 是执行引擎（给 LLM 发请求、收响应、调工具），Mederi 是应用框架（供应商配置、项目管理、持久化、Session、审批、计划系统）。

**不存在自定义 Agent**。Agent = LLM 驱动的工具集 + 工作流，是运行时实例，通过 AgentMode（APPROVAL/AUTONOMOUS）+ WorkType（WORK/CODE）配置。

**InkCompose** 是本项目的富文本渲染核心：Markdown / LaTeX / 代码高亮 / 图表（Mermaid，KBrowser webview 渲染）/ 竖排文字（蒙古文/满文）。Mederi 计划系统产出的 architecture（Mermaid 代码块）由 InkCompose 渲染。

## 1.5 desktop 与 server 只是两个 interface（硬性架构规则）

**desktop 和 server 共享同一个 core，它们只是两个 interface（宿主入口）而已。**

- `MederiAiCore`（jvmMain 桥）是唯一的 core 实现封装：desktop 进程直调它；server 进程创建它 + Ktor 暴露 REST/SSE；desktop 的内嵌遥控 server 也共享同一 `MederiAiCore` 实例
- **一切业务行为挂在 AiCore 层**（contract `AiCore.kt` → `MederiAiCore` → `ServerAiCore` → server endpoint 四处同步），例如会话自动改名（`SessionTitleService`）挂在 `MederiAiCore.initialize()`——谁初始化谁生效，所有宿主天然一致
- **宿主 main（desktopApp / server / androidApp / webApp / iosApp）只做宿主专属注入**（遥控 hooks、pty 终端、平台窗口），**禁止在宿主 main 里挂任何业务逻辑/事件监听**——挂进宿主 = 其他 interface 全都没有
- 判断标准：新功能先问"挂在 AiCore 是不是所有宿主都能用"，答案几乎总是"是"

## 2. KMP 优先原则（硬性义务）

**所有模块、所有代码都必须按 KMP 跨平台能力规划与开发。**

- 模块一律用 `kotlinMultiplatform`（KMP）插件声明，目标平台 = jvm / android / iosArm64+iosSimulatorArm64 / **wasmJs**（不要 js 目标）
- 平台相关实现一律走 `expect/actual` + 分层 source set（commonMain / jvmMain / androidMain / iosMain / wasmJsMain）
- 通用逻辑必须放 `commonMain`；**只有在某个能力没有 KMP 可替代品时才允许 JVM 专属代码**，且只能放对应平台源集，不得进 commonMain
- 有 KMP 替代品的标准库 API 一律用替代品，例如：
  - `java.time` → `kotlinx-datetime`
  - `java.io.File` → okio（FileSystem）或注入的文件抽象
  - `java.util.UUID` → `kotlin.uuid.Uuid`（或 `kotlinx-uuid`）
  - `java.util.concurrent.*` → `kotlin.concurrent.AtomicInt` / `kotlinx-atomicfu`
  - `java.text.*` → `kotlinx-datetime` 格式化 / `kotlin.text` 工具
- 数据库访问（SQLDelight）本身即 KMP：`.sq` 放 `commonMain/sqldelight`，driver 按平台注入
- 服务器侧（server 模块）是天然 JVM-only 的例外：Ktor 服务端没有 KMP 替代品，
  但仍保持模块边界清晰，所有业务逻辑在 core 的 commonMain 中，server 只做薄转调
- **core 当前仅声明 jvm 目标**：core 的实际实现（Koog / SQLite JDBC / java.io）是 JVM 侧逻辑，
  ios/android/wasm 平台经 app:shared 的 AiCore 契约桥（ServerAiCore REST）访问、不直连 core。
  core 真正 KMP 化后再补其他目标；在此之前不要假设 core 具备跨平台能力
- **判断标准**：写任何新代码先问"这在 iOS/wasm/Android 上跑不跑得了"，
  跑不了就找 KMP 替代品，没有才允许 JVM 专属，并在代码注释里写明理由

### 代码语言与平台规则

- **语言偏好**：能用纯 Kotlin 解决的尽可能用纯 Kotlin；纯 Kotlin 无法满足时，JVM 端可以引入 Java 库来使用
  （Java 库只允许出现在 JVM 相关 source set，不得进入 commonMain）。
- **跨平台要求**：除非是编写平台特有的特性/功能，否则必须写跨平台的通用代码（放 commonMain）。
- **Web 目标**：web 端只支持 **wasmJs**，**不支持 js 目标**——新增模块/代码不得声明或使用 js 目标。

## 3. 仓库结构

```
mederi/
├── app/                    # Compose Multiplatform 应用
│   ├── androidApp/         # Android 入口
│   ├── desktopApp/         # Desktop (JVM) 入口
│   ├── webApp/             # Web 入口（仅 wasmJs，不要 js 目标）
│   ├── iosApp/             # iOS 入口（Xcode 工程）
│   └── shared/             # 跨平台 UI + AiCore 契约桥（MederiAiCore / ServerAiCore / RemoteServer）
├── core/                   # 领域模型 + Manager + API + Koog 适配 + 存储（当前仅 jvm 目标，KMP 化后补全平台）
├── server/                 # Ktor 薄转调层（REST/SSE，每个 endpoint 转调 core API；JVM-only 例外）
├── scripts/                # e2e 冒烟/审批/收敛测试脚本（项目测试资产，纳入版本管理）
└── inkcompose/             # 富文本渲染库（单 KMP 模块，未来独立发布）
```

### 临时工作区约定（硬性）

git 仓库根目录的上一级 `/Users/liuzhe/Projects/mederi/temp/` 是**唯一临时工作区**（已被 `.gitignore` 忽略）。
以下一律在 `temp/` 里做，**禁止散落到仓库任意目录或项目根**：

- 解压缩看第三方源码、reference 代码（如 mermaid/dagre/cose 等）
- 临时 clone / 拉取外部仓库来读代码、比对实现
- 各种一次性实验、验证脚本、探针代码
- 工具产生的 scratch（反编译 class、playwright 截图日志等）——用完即清，或压根别落盘

区分：`mederi/scripts/` 是**项目 e2e 测试脚本**（正式测试资产，纳入 git）；
一次性/无用的脚本进 `temp/`，不进 `scripts/`。判断标准：这条脚本是不是回归验证链路的一部分——是→`scripts/`，否→`temp/`。

### 分层架构（core）

```
API 层（DTO 转换 + 异常包装）  ProviderApi / ProjectApi / SessionApi / ModelApi
        ↓
Manager 层（唯一真理源）       ProviderManager / ProjectManager / SessionManager
        ↓
Store 层（纯持久化，可注入）   ProviderStore / ProjectStore / SessionStore / HistoryStore / ApiKeyStore
```

依赖方向无循环：SessionManager → ProjectManager / ProviderManager；Manager 方法签名只用领域类型。

### inkcompose 内部结构（单模块，目录即边界）

```
inkcompose/
├── common/<domain>/kotlin/   # commonMain，按领域分目录：entry, latex-*, syntax-*,
│                             # diagram, markdown-*, vtext
├── common/composeResources/font/   # KaTeX×20 + NotoSansMongolian（customDirectory 注册）
├── jvm/ android/ ios/ wasmJs/      # 平台 actual 平铺合并（各领域无同名文件）
├── common-test/<domain>/     # commonTest 按领域分目录
└── jvm-test/                 # JVM 测试 + commonmark/gfm spec 资源
```

- 目标平台：jvm / android / iosArm64+Simulator / **wasmJs**（不要 js 目标）
- 领域依赖只有一条链：markdown-renderer → latex/syntax/diagram（嵌入渲染），其余领域零依赖
- 结构图渲染策略（域 = `diagram`，包 `xyz.emuci.diagram`）：
  - 全平台 = 官方 mermaid.js Web 渲染（原生 Kotlin 渲染管线已整体删除，含 PlantUML/DOT 支持）：
    - jvm = KBrowser JCEF 离屏 Worker（全局单例串行渲染 2x PNG，`MermaidDiskCache` 落盘 `~/.mederi/mermaid/`）
    - android / ios = KBrowser 桥接系统 WebView / WKWebView
    - wasmJs = 同文档 DOM（同源 `./mermaid.min.js` 离屏 Canvas 超采样为 PNG）
  - 主题：`DiagramTheme`（app 侧颜色 Token）+ Mermaid 内置主题（isDark → 'default'/'dark'），
    **严禁往 themeVariables 塞 primaryColor 等调色板变量或 themeCSS**（会干扰内置主题派生，历史上出过渲染事故）
  - 非 mermaid 语法 / 渲染失败 → `DiagramCodeFallback` 源码展示
- 公共 API 只有 `xyz.emuci.inkcompose` 包（MarkdownView / RenderType / MermaidCacheConfig），内部领域包不要对外引用
- 命名：语法高亮域 = `syntax`（包 `xyz.emuci.syntax`）；生成资源类统一为 `xyz.emuci.inkcompose.resources`

## 4. 构建与测试

```bash
./gradlew :inkcompose:jvmTest            # 主安全网（2600+ 测试）
./gradlew :inkcompose:assemble           # 全平台编译打包
./gradlew :app:shared:jvmTest            # shared 模块 jvm 测试
./gradlew :core:jvmTest                  # core 测试
```

### 运行方式（硬性义务）

- **测试代码**：可以通过 bash 直接运行（`./gradlew ...Test` 等），不受限。
- **APP / server（UI/server 模块）**：**必须通过 idea-mcp 工具来运行/调试**（用 IDE 的 run configuration），**禁止直接用 bash 启动**。
- 如果 idea-mcp 未配置或未启动（例如 IDE 没开、工具调用失败、当前会话没有 idea-mcp 可用），**不要自行用 bash 硬跑**，而是明确提醒用户"需要先启动/配置 idea-mcp 才能运行 APP/server"，让用户处理环境后再继续。

### 开发与 UI 测试工作流（Compose Hot Reload + MCP）

桌面端 UI 开发与测试基于 **Compose Multiplatform Hot Reload 的 MCP server**（CMP 1.12.0+ 内置，配置见仓库根 `opencode.json` 的 `compose-hot-reload` 项）：

1. **启动 server**：通过 idea-mcp 运行 `ApplicationKt` run configuration，启动独立 server（内建 core，监听 8081）。可在环境变量中设 `MEDERI_SERVER_PORT` 指定端口。
2. **启动桌面应用**：通过 bash 运行 `./gradlew :app:desktopApp:hotRun`（必须用 `hotRun` 而非 `run`，否则 MCP 会报 disconnected）。

**MCP UI 测试工具**（AI 自主测试循环：`get_semantic_tree` 理解 UI → `click`/`type_text` 操作 → 验证 → 可配合 `reload`）：

`status` / `get_semantic_tree` / `click` / `long_click` / `type_text` / `scroll` / `scroll_to_index` / `take_screenshot`（需窗口前台，仅桌面）/ `get_logs` / `get_ui_error` / `list_windows` / `reload`

> **注意**：运行前请确认 `app:desktopApp` 的 `build.gradle.kts` 已配置 `compose.desktop.application` 且 `mainClass` 正确。

### 传统 unit / common test

- Kotlin/Native 没有 `kotlin.concurrent.Lock`——native 锁用 `kotlin.concurrent.AtomicInt` CAS 或 pthread
- `kotlinx.coroutines.runBlocking` 不可用于 js/wasmJs——此类测试放 `jvm-test/`
- commonTest 里用 Compose UI test（`runComposeUiTest`）需要 `compose.uiTest` 依赖
- 依赖统一走 `gradle/libs.versions.toml`，**只用最新版**，用前查最新官方文档确认 API

## 5. 编码规范

- 注释写清楚，写明白（中文注释 OK）
- 领域模型不依赖任何框架（纯 Kotlin）
- Koog 适配器集中在 infrastructure 层；接口定义在 core，实现可注入
- 不提前引入不需要的依赖
- 错误操作不崩溃，能报错给用户
- **不兜底原则（数据层面）**：没有数据就是没有，不能偷偷塞默认值掩盖问题
- **安全兜底（程序层面）**：没数据不能崩，要安全地把错误信息反馈给用户
- **Manager = 唯一真理源**；**ApiImpl = DTO 转换 + 异常转换**；**Store = 纯持久化**
- 统一异常体系：对外 API 只抛 `MederiException` 子类；core 层不得静默 no-op 伪装成功（调用方会基于错误的"成功"继续走，造成更深的错误）
- 命名具体化：避免 `context` 这类过度抽象的命名
- 结构化数据（事件 payload、持久化列、跨端 DTO）一律 `@Serializable` DTO + kotlinx.serialization 编解码；
  **禁止手拼/手解 JSON**（joinToString / 字符串模板 / buildString 拼 JSON、正则或字符串手术解析全禁止）；
  解码失败安全降级，不用字符串手术挽救

## 5.5 执行沙盒与审批模式（2026-09 定稿，详见 `docs/sandbox-plan.md`）

**安全模型 = 分诊判断 + 沙箱**（无逐操作询问，见 §1 开头）：主代理按 §5.6 分诊流程自主判断执行路径，
沙盒（路径/命令强制）兜底。不变量 = 文件工具只能写项目目录、execute_command 走 OS 级写沙箱（macOS Seatbelt / Linux bwrap 检测 / Windows 降级警告）、读全盘放行。全局白名单在设置页配置。

**不变量（代码强制，全平台）**：
- 文件工具（write_file/edit_file/apply_patch）只能写项目 directories + `.mederi/` + 全局白名单；读全盘放行
- execute_command：macOS Seatbelt（sandbox-exec）/ Linux bwrap（只检测不代装）/ Windows 降级警告；shell 探测链 bash→sh（Windows bash.exe→cmd）
- **进程回收**：每条命令是独立进程组，execute_command 启动即登记 `ProcessRegistry`；`list_processes`/`stop_process` 宿主侧（沙箱外）按注册表整组回收——macOS 沙箱内信号不可用（profile 无 process-signal，实测 unbound），只能靠宿主侧。**只杀 mederi 自己启动的进程**，注册表查不到 pid 即拒绝，沙箱内命令无法写注册表
- AgentMode 两模式工具集完全一致，**唯一区别 = 计划批准者**：APPROVAL 等用户批准，AUTONOMOUS 自动批准立即执行
- `spawn_agent` 硬校验 planId/subtaskIndex/spec 存在性（spec 不存在即拒）

**项目目录模型**：`directories.first()` = 主目录（承载 `.mederi/` 工作区、shell cwd、相对路径解析首选），
其余目录平等读写。多目录 containment 白名单有效。

**计划/Spec 分层（2026-09 定稿）**：create_plan = WHAT（中层技术方案，用户批准的对象）；批准后 generate_spec 逐子任务派生 HOW（行级实现规范，写入 Subtask.spec，brief 永不覆盖）；spawn_agent(planId, subtaskIndex) 硬绑定执行存储的 spec；
verify 三分支：PASS / 执行错→converge_plan / **spec 错→重新 generate_spec 覆盖→重执行**。

**系统环境注入**：每条用户消息尾部 `<<<NOT_FOR_UI>>>` 隐藏块注入时间、OS/版本/arch、
shell 与沙箱状态、项目目录、Java 版本——AI 需要知道但不该让用户重复输入的环境事实。

**提示词拼装**：`core/prompt/SystemPrompts.kt` 是骨架（身份/原则/工具清单/工作流拼接），
`core/prompt/PromptGuides.kt` 是教程素材库（SANDBOX_USAGE 等）——新主题在此加常量往骨架拼。

## 5.6 分诊流程 Triage Flow（2026-09 定稿）

**一句话**：主代理是唯一分诊台，对每个用户请求自主判断走哪条路径——信任 AI 判断，无代码门禁，沙盒兜底。

### 三条分诊路径

```
用户请求 → 主代理判断：
├─【纯读】问答/讨论/分析 → 主代理直接读文件回答；
│    读不够深（跨多文件/链路长/根因未知）→ spawn_researcher → 完整报告 → 据此回答
├─【小改动】已知根因/简单逻辑/几行代码 → 主代理直接 edit_file/write_file/apply_patch
│    （不建 plan、不 spawn）
└─【复杂改动】多文件/逻辑变化/需用户决策 → Plan Loop：
     （理解不足先 spawn_researcher）→ create_plan → 批准 → generate_spec
     → spawn_agent(planId, subtaskIndex) → verify_subtask
       ├─ PASS → 下一个子任务
       ├─ 执行错 → converge_plan 追加补救 → 重执行
       └─ spec 错 → 重新 generate_spec 覆盖→重执行
     → 全部 PASS → 归档
```

小改动 vs 复杂改动的判断由 AI 自主做出；拿不准时**先调研再决定**。

### 三角色

| 角色 | 职责 | 工具 | 生命周期 |
|---|---|---|---|
| 主代理 | 唯一决策者：分诊、调研结论、计划、验证、收敛；小改动亲自动手 | 全套（含写） | 长期存活 |
| Researcher | 眼睛：只读深度调研，输出完整报告，不做决定、不写文件 | read_file + list_directory | 一次任务即死 |
| Executor | 手：照 spec 清单自顶向下执行，不问用户，SPEC_FEEDBACK 回报 spec 与现实的矛盾 | 全套（无 plan/spawn/verify/ask_user） | 一次任务即死 |

### 拆小可验证（硬性要求）

create_plan 必须把需求拆成**多个小的、可独立验证的子任务**——每个子任务 = 一个 spec + 一个 verification
（具体命令 + 预期结果 + 通过标准）。绝不把多个改动塞进一个含混的子任务；无法独立验证就继续拆。
验证标准在 plan 批准时即对用户可见（PlanStore Markdown 每个子任务带 `#### Verification` 段）。

### 上下文挂载（防失忆）

- **主代理**：每轮 turn 从 PlanStore 现读活跃计划，把每个子任务的 status/targetFiles/brief（planDetail）+ verification/验证结果拼进系统提示词的 `# Active Plan` 段——**spec 只挂活跃子任务**（IN_PROGRESS 优先，否则 nextPending），历史 spec 留在磁盘（spawn_agent 自取）；挂 `Current: Subtask N` 指针行给模型 todo 式焦点
- **轻量 todo（update_todo，2026-09）**：无 Plan 任务的进度跟踪，真理源 = sessions.todos 列，每轮挂 `# Current Todo` 段（turn 边界刷新）；**有活跃 Plan（执行期）时代码级硬门禁禁用**（AgentTools 校验）——todo 面板显示 Plan 子任务投影（`PLAN_PROGRESS` payload `todos`，投影函数 `Plan.toTodoProjection()` 唯一），防止两份进度真理源
- **Executor**：spec（Subtask.spec）注入其唯一一条用户消息（brief 拼进 briefing 作意图上下文），系统提示词要求自顶向下执行——一次性任务无需持续挂载
- **Researcher**：只挂 task + briefing，无计划上下文

### 安全网

1. **沙盒（代码强制）**：文件工具只能写项目目录 + `.mederi/` + 全局白名单；命令走 OS 级写沙箱
2. **分诊判断（提示词强制）**：先调研再动手、小改直接改、复杂走 Plan Loop
3. plan 从"写文件通行证"降级为"复杂工作的审批流程"——用户批准点保留（APPROVAL 模式），不再锁所有写

### 审计备忘（2026-09，暂不修，记录以免重复分析）

下列几项经审计确认为"真实代码特性但符合当前哲学/暂不修"，遇到真问题再说：

1. **小改路径无 spec 落盘**：triage "小改直改"路径不建 plan、无 `Subtask.spec`，spec 只在聊天里。
   按哲学（AI 判断 = AI 责任）可接受。kvstore 事件**未确认**是否为其实例（不知 AI 当时判小改还是判复杂跳过）。
   要修须先定：是否强制所有写都走 plan——目前否。
2. **spec 自动更新只到提示词级**：`verify_subtask` 的 spec-冲突分流（CONTRADICTS→re-generate_spec）是
   提示词引导，无代码强制 re-generate。符合"信任 AI"哲学。要升级为代码强制再动。
3. **spec 每轮挂载范围**：主代理每轮只挂**活跃子任务**的 spec（非全部），executor 在 spawn 时拿一次。
   已满足"todo 开始时加载一次"。删 per-turn spec 挂载是可选优化，暂不做。
4. **triage 声明 gate（update_todo 当小改许可证）**：曾提议让"判了复杂"可观测以强制写 plan。
   kvstore 未证实该失败模式（真根因是 ask_user 缺支，已修，见下）。先搁置。
5. **动态上下文只挂状态、不挂指令**（已落地）：`TurnExecutor` 的 `# Active Plan` 段已砍掉
   "WARNING: ... call converge_plan" 这行每轮说教——它重复静态 Plan Loop 规则且是 gap-D 实例。
   **不要再往动态注入里加指令/警告行**；指令归静态系统提示词，动态段只挂状态事实（status/result/evidence）。
6. **已修的真缺陷（2026-09）**：① `VerifyTools` 按 gapType 分流（CONTRADICTS→re-generate_spec，
   其余→converge_plan），不再对所有 FAIL 笼统说 converge；② spec 内部自相矛盾（不可满足，非"跟代码对不上"）
   时禁止自行修改，强制 `ask_user`——加进 Core Principles 第 7 条 + Plan Loop 第 6 步第 4 分支 +
   executor SPEC_FEEDBACK 区分 `vs-reality` / `unsatisfiable`。
7. **Markdown 警示块语言**（已修）：`PromptGuides` Alerts 段教模型用 GitHub 警示块，但原规则没说内容用什么
   语言，模型在中文回复里抄了英文 `[!WARNING]`，红框看起来像系统报错。已补：警示块正文必须用用户输入语言
   （Core Principles 第 6 条）。UI 红色撞车问题后续单独处理。

## 5.7 存储架构（2026-09 双库定稿）

**双库 + 设备本地偏好三分法**（根目录 `~/.mederi/`）：

| 存储 | 内容 | 特性 |
|---|---|---|
| `config.db` | providers、api_keys、projects、mcp_servers | 极小、低频写、事务一致（provider 行 + key 行同库）；一个文件 = 全部配置，可单独备份迁移 |
| `data/data.db` | sessions、message_history、diffs | 高频 append、体积随使用增长 |
| `preferences.json` | theme、上次打开等 UI 偏好（app:shared 层管理） | **设备级语义**——不进库：桌面端与遥控端连同一 server 时，主题不该跟着 server 走 |

- 各 store 统一模式：payload 列存领域对象完整 JSON（存储即真相），常用查询字段提升为独立列
- `message_history` 诊断列（model_id/duration_ms/finish_reason/status）写入时抽取，原始消息查看器可直接 SQL 筛选
- driver 由 Mederi 装配层创建共享；sqldelight 拆两个 database（`MederiConfigDatabase` / `MederiDataDatabase`）
- 原始消息调试：`HistoryStore.listRaw()` → `GET /v1/sessions/{id}/messages/raw` 返回 payload JSON 原文（不解析不映射）

### 删库规矩（硬性义务）

**产品未发布，开发期允许破坏性数据结构变更**——改 schema 可以直接删 `data/data.db` 重建，
**但是删除任何用户数据文件（data.db、config.db、旧版 JSON）之前，必须先通知用户、经用户批准才能执行，
不可以问都不问擅自主张。** 启动检测到旧版文件时走 `ConfigMigrationRequester` 审批流程；
AI 会话中做此类操作同样适用此规矩。

## 6. AiCore 契约（app/shared ⇆ server）

对外契约 = `app/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCore.kt`（KMP commonMain 接口）。
两个实现各走一侧：`MederiAiCore`（jvmMain，进程内直调 core）+ `ServerAiCore`（commonMain，经 REST + SSE 遥控 server）。

**契约同步规则（硬性义务）**：AiCore.kt（contract）→ MederiAiCore（jvm 桥）→ ServerAiCore（REST 桥）→
server 路由（`RemoteServer.remoteModule`），任何变动四处同步、缺一即编译失败或运行期 404。
新增 DTO 放 `contract/dto` / `contract/models`（kotlinx-serialization，commonMain）。

事件流：core `eventBus` → `MederiAiCore.events()`（进程内直收）与 `GET /v1/events` (SSE)（遥控端），客户端用 commonMain 的 `SnapshotReducer` 本地聚合快照。**事件消费方（自动改名等 UI 层特性）一律挂 `MederiAiCore.initialize()`，不挂宿主 main**（见 §1.5）。

### 嵌入式遥控 Server（app/shared/jvmMain）

`RemoteServer`（`app/shared/src/jvmMain/kotlin/xyz/mederi/core/remote/RemoteServer.kt`）：

- **启动**：`RemoteServer.start(aiCore, port, password, webappDir)`，幂等；`stop()` 幂等
  - `host = "0.0.0.0"` 监听所有网卡，局域网内手机/浏览器可直接访问
  - **端口策略**：先按请求端口起；被占用 → 改 port=0 让 OS 自动挑空闲端口，经 `engine.resolvedConnectors()` 读回实际端口
  - `webappDir` 非空时同源托管 wasm Web UI；`password` 非空时 `/v1` 路由要求 Bearer 鉴权（`/` 与 `/v1/ready` 豁免）
- **调用方**：desktop（内嵌遥控，观察 `remoteControlEnabled` 自动启停）和 server 模块（独立部署），都依赖 `app:shared`，不相互依赖
- **server 模块**（`server/Application.kt`）：薄启动器，仅创建 `MederiAiCore` + `initialize()` + 调 `remoteModule()`，路由代码不重复

#### 端口记忆（AppState 职责）

遥控端口由 `AppState` 持久化（`remote.port`，默认 8081）：`startRemoteControl()` 用已保存端口起，
成功（含端口回退）后回写实际端口。UI 展示 `remoteServerState`（Idle/Starting/Running(port, portFallback)/Failed）。

#### 密码门（app/shared/wasmJsMain）

`RemoteGate` 在 wasm 遥控端的 `MederiApp` 外层包裹，密码未验证时不创建 AiCore：
- 启动时带 localStorage 密码探测（GET /v1/providers + Bearer）→ 200 直接进，401 弹密码输入
- 用户提交 → 同接口重试（真实服务端判定，不做本地比对）→ 通过写 `localStorage("mederi.remote.password")`
- **禁止 `?password=` URL 参数**（会留浏览器历史）

#### Cloudflare 隧道（desktop jvmMain）

`DesktopRemoteControlHooks`（`app/desktopApp/.../DesktopRemoteControlHooks.kt`，desktop 宿主专属注入）：

- `cloudflared --version` 探测；未装 → UI 显示"请自行安装"，mederi 不代装不教程
- `startTunnel()`：**pty4j**（JVM-only 无 KMP 替代品）以真实 pty 拉起 `cloudflared tunnel run`
- **生命周期**：本进程退出 → OS 关 pty master → SIGHUP → cloudflared 退出（"终端关了进程就死"，无需看门狗）
- **域名**：解析 `~/.cloudflared/config.yml` 第一条 ingress `hostname`；**局域网地址**：`localAddress` 枚举 site-local IPv4

## 7. 推理（Reasoning）机制

核心思想：**不对供应商参数做任何语义解释**。每个 ReasoningLevel 对应用户编写的请求体 JSON 片段，
发送时根级合并进请求体（`{"reasoning_effort":"low"}` 之类）。用户填什么发什么，机制不纠错。

- 内置供应商参数按官方文档预填，UI 不可编辑；仅自定义供应商可配置
- 自建 `MederiOpenAILLMClient` 覆盖 Koog 默认 client（vLLM `reasoning` 别名、Ktor SSE 流式、Responses `text` 字段等兼容性修复）
- 任何路径不允许假流式（禁止"非流式拿全量再转帧"）

### 推理档位唯一真理源（硬性义务）

**推理档位（thinkingLevel / reasoningLevel）的显示与发送必须同源，只允许一个真理源**：

- 唯一推导链 = `ReasoningMenu.resolve(modelMemoryLevel, modelLevels)`（contract/models/ReasoningMenu.kt）：
  模型记忆（AppState 持久化，用户上次为该模型选择）> 模型默认档（MEDIUM 优先，否则首档）
- UI 显示（推理选择器）读 `WorkspaceViewModel.effectiveThinkingLevel`（内部只调 resolve，无副本状态）；
  发送（send / rollbackMessage 的 `ChatPromptInput.thinkingLevel`）**只允许发 `effectiveThinkingLevel`**
- **禁止**另行持有瞬态 thinkingLevel 副本、禁止在选择器/发送处各自回退（`?: levels.first()` 之类）、
  禁止用模型声明档（aiModel.reasoningLevel）冒充会话级别（`Conversation.thinkingLevel` 仅回显 core 会话诊断值）
- 违反后果（真实事故）：瞬态值 null → 发送侧解析成 NONE 关推理 → "界面显示推理高、实际没推理"

### 模型元数据所有权与合并唯一真理源（硬性义务，2026-09）

**AIModel 每个字段必须声明所有权**（`ModelOrigin` 标记模型级权威）：

- **FETCHED 模型**（端点拉取 + models.dev 目录合并）：元数据权威 = 端点/目录，
  唯一写入路径 = `ModelMerge.mergeFetched`（core/provider/ModelMerge.kt，表驱动、穷举测试锁定），
  入口只有 `ProviderManager.applyRemoteMetadata`（refresh 传 endpoint、启动回填传 null）
- **MANUAL 模型**（用户手动添加）：用户权威，同步（refresh/回填）**永不触碰**，UI 元数据可编辑
- **用户覆盖层**：`supportsImagesOverride`（用户权威，同步永不触碰）——目录对长尾/私有模型经常
  缺数据或标错（真实案例：models.dev agnes 条目只收录 3/13 个模型），用户在 UI 显式设置的
  图片能力必须压过目录且在 refresh/回填中存活（真实事故：用户 UI 设的支持图片被目录 false 洗掉且只读改不回）
- 用户可写字段只有 `isEnabled` 和图片覆盖（setModelEnabled / updateUserModel / setImageOverride）；
  `updateUserModel` 对 FETCHED 模型传入其他元数据修改会抛错
- 图片能力消费端门禁：附件按钮显隐 + `tryAttachImage` 粘贴拦截 + `guardImageSupport` 发送守卫
  （send 与 rollbackMessage 共用，唯一实现），禁止绕过直接组装带图 ChatPromptInput
- **能力必须传导到执行引擎**：`KoogModelBuilder.buildCapabilities` 按 `supportsImages` /
  `supportsReasoning` 添加 `LLMCapability.Image` / `Thinking`——缺 Image 时 Koog 会在发送时
  直接拒绝图片消息（历史事故：设置链路全通、唯独引擎能力缺失，用户怎么设都报不支持图片）
- UI 一律叫 **Image**（历史遗留叫 Vision，已废弃）；字段名统一 `supportsImages`

**硬性规则**：
- **系统永不自动写存量模型的元数据**（2026-09 机制翻转，用户裁定）：启动回填已移除、
  refresh 只同步列表（新增模型带元数据落库，已有模型一律跳过）；目录数据进入存量模型的
  **唯一通道 = 用户显式点「自动设置」**（`autoSetupProviderModels` / `ProviderApi.autoSetupModels`）
- 两处同步（refresh 与自动设置）只能喂不同输入（endpoint 有无），**禁止各自手写字段合并逻辑**——
  合并规则改 `ModelMerge` 一处 + `ModelMergeTest` 穷举组合（历史事故：回填/刷新策略打架、
  用户开关被覆盖、跨重启闪烁）
- 新增 AIModel 元数据字段三件套：① 在 ModelMerge 所有权表登记 ② ModelMergeTest 补全组合断言
  ③ 契约四处同步（AiCore.kt → MederiAiCore / ServerAiCore → RemoteServer 路由，Mock 同步）

## 8. 当前状态

- core / server / app UI：Koog 适配 + 供应商管理 + 计划系统（verify-converge 收敛循环）+ SSE + wasm UI 已就绪
- 分诊流程（Triage Flow）：已落地（§5.6）；执行沙盒已落地（§5.5 + `docs/sandbox-plan.md`）
- 存储架构：双库已落地（§5.7）——config.db / data.db + 设备本地 preferences；原始消息 API（listRaw）已就绪
- 会话自动命名：`SessionTitleService` 挂 `MederiAiCore.initialize()`（§1.5），所有宿主生效
- Todo 系统：`update_todo`（无 Plan 任务，sessions.todos 持久化 + `# Current Todo` 回注入 + UI TodoListCard）+ Plan 子任务投影（create/spawn/verify/converge 发 `PLAN_PROGRESS` 带 todos）已落地（详见 `docs/todo-system-plan.md`）
- inkcompose：单 KMP 模块已接入 `app:shared`（jvmTest 2600+ tests 全绿，四平台编译通过）
