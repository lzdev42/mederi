# Mederi AGENTS.md

> 本文件只装**规则、不变量、真理源、指路**。机制实现细节、领域深度规则按需拆到 `docs/` 下各文档，
> AI 在对应场景改动时 `read_file` 取——不需要时刻背着。

## 拆出的懒加载文档（按场景读）

| 场景 | 读哪个 |
|---|---|
| 排查 AI 运行时自身行为（沙盒实现/子代理事件/上下文挂载/审计备忘） | `docs/agent-internals.md` |
| 改遥控功能（嵌入式 server/端口记忆/密码门/Cloudflare 隧道） | `docs/remote-control.md` |
| 改 apply_patch（补丁语法、模型调用格式、路径/写锁/回滚、上游升级） | `docs/apply-patch.md` |
| 改 provider/model 同步、模型元数据字段、ModelMerge、图片能力门禁 | `docs/model-metadata.md` |
| 桌面端 UI 开发/UI 测试/写跨平台测试 | `docs/ui-dev-workflow.md` |
| 对话工作台 UI 整体改造/续做（理念、MVVM 边界、一致性、实施顺序与验收） | `docs/design/conversation-workspace-redesign-plan.md`（主计划）+ `docs/design/conversation-workspace-ui-inventory.md`（部件清单） |
| 查代码结构（类图/流程图/签名/端点/表结构） | `docs/architecture/`（见 §0） |

## 0. 架构速查文档（AI 必读入口）

`docs/architecture/` 是由全量代码结构化提取的架构文档（类图/结构图/流程图/时序图 + 穷尽式字段/签名清单）：

- **排查问题先查这里，不要直接通读源码**：模块总览与平台注入矩阵 → `00-overview.md`；core 模块（DI/模型字段/Manager/Store/Koog 引擎/工具/Plan/Provider/MCP/事件/存储表）→ `01-core.md`；AiCore 契约与三实现、Server 路由表、AppState/VM/UI → `02-app-shared.md`；inkcompose 渲染管线 → `03-inkcompose.md`；运行时流程与时序（发消息全链路/Turn/事件聚合/Plan Loop/压缩/审批/子代理/回滚/自动改名/遥控启动）→ `04-flows.md`。
- **排查工作流（禁止一上来通读/全量搜索源码）**：先按问题域查对应篇章 → 用文档里的类图/流程图/签名清单定位相关类与 `文件路径` → **只打开这几个文件读相关段落**。文档能回答的问题（结构、字段、谁调谁、事件流向、端点、表结构）直接引用文档答案，不再翻源码确认；只有文档未覆盖或疑似与代码不一致的实现细节，才去读文档标注的那几个源码文件。
- 每个类都标注了 `文件路径`；只有文档未覆盖的实现细节才去读对应源码。
- **维护义务（硬性）：每次改动代码，按需同步更新 `docs/architecture/`**，保持文档与代码一致——过时文档比没有文档更误导。改动完成前自检：这次改动是否触碰下列任一项？是 → 更新对应文档：
  - 新增/删除/重命名 **模块、类、接口、方法签名、DTO 字段、枚举值、工具、端点、事件、数据库表/列** → 更新对应篇章（core→`01-core.md`，契约/UI/VM/server→`02-app-shared.md`，inkcompose→`03-inkcompose.md`，总览/平台注入矩阵→`00-overview.md`）；
  - **运行时行为/调用链/流程变化**（新增交互流程、改 sendMessage/turn/压缩/审批/子代理/回滚等链路、新增人机交互挂起点）→ 更新 `04-flows.md` 的流程图/时序图/状态机；
  - 改 `.sq` schema → 同步 `01-core.md` 存储节；改 Server/ServerAiCore 路由 → 同步 `02-app-shared.md` 路由表（契约四处同步本来就要做，文档顺带）；
  - 纯实现细节修复（内部算法微调、不改对外结构的 bug fix）→ 可不更新；拿不准就更新对应段落，宁多勿漏。
- 本文档描述"结构"，AGENTS.md 描述"规则"——规则类变更（不变量、真理源、硬性约束）写进 AGENTS.md，不写进该目录。

## 1. 定位

Mederi 是 Koog 的易用化、插件化包装库：让开发者快速构建 AI Agent 应用。
Koog 是执行引擎（给 LLM 发请求、收响应、调工具），Mederi 是应用框架（供应商配置、项目管理、持久化、Session、审批、计划系统）。

**不存在自定义 Agent**。Agent = LLM 驱动的工具集 + 工作流，是运行时实例，通过 AgentMode（APPROVAL 审批 / AUTONOMOUS 自主）配置行为；不区分编程/通用工作用途。

**InkCompose** 是本项目的富文本渲染核心：Markdown / LaTeX / 代码高亮 / 图表（Mermaid，KBrowser webview 渲染）/ 竖排文字（蒙古文/满文）。Mederi 计划系统产出的 architecture（Mermaid 代码块）由 InkCompose 渲染。

## 1.5 core 的两个 interface：desktop 与 server

core（JVM-only 引擎，见 §2）封装成 `MederiAiCore`，对外只有两个 interface：

- **desktop interface**：进程内直调 `MederiAiCore`。desktop 进程跑它，UI 直接用，不经网络。
- **server interface**：Ktor REST/SSE 把同一个 `MederiAiCore` 暴露到网络（类名 `Server`）。

desktop 可在 desktop interface 之外**再起一个 server interface 实例**（共享同一 `MederiAiCore`），让遥端能遥控进来；desktop UI 自己仍直调 core，不走这个 server。`server/` 模块 = 一个只跑 server interface 的无头进程。遥端（ios / android / wasm）是 **server interface 的消费端**，经 `ServerAiCore`（REST 客户端）连任一端的 server；它们**不跑 core**。

**业务逻辑归属（硬性）**：一切业务行为挂在 AiCore 层（contract `AiCore.kt` → `MederiAiCore` → `ServerAiCore` → server endpoint 四处同步），不挂宿主 main。例：会话自动改名（`SessionTitleService`）挂 `MederiAiCore.initialize()`——谁初始化谁生效，两个 interface 天然一致。宿主 main（desktopApp / server）只做宿主专属注入（遥控 hooks、pty 终端、平台窗口），**禁止在宿主 main 挂业务逻辑/事件监听**——挂进宿主 = 另一个 interface 没有。判断标准：新功能先问"挂在 AiCore 是不是两个 interface 都能用"，答案几乎总是"是"。

## 2. KMP 优先原则（硬性义务）

**跨平台模块（`app:shared`、`inkcompose`）的所有代码必须按 KMP 跨平台能力规划与开发。`core` 与 `server` 是 JVM-only（见下），不在此列。**

- 模块一律用 `kotlinMultiplatform`（KMP）插件声明，目标平台 = jvm / android / iosArm64+iosSimulatorArm64 / **wasmJs**（不要 js 目标）
- 平台相关实现一律走 `expect/actual` + 分层 source set（commonMain / jvmMain / androidMain / iosMain / wasmJsMain）
- 通用逻辑必须放 `commonMain`；**只有在某个能力没有 KMP 替代品时才允许 JVM 专属代码**，且只能放对应平台源集，不得进 commonMain
- 有 KMP 替代品的标准库 API 一律用替代品（仅适用于 `app:shared` / `inkcompose`），例如：
  - `java.time` → `kotlinx-datetime`
  - `java.io.File` → okio（FileSystem）或注入的文件抽象
  - `java.util.UUID` → `kotlin.uuid.Uuid`（或 `kotlinx-uuid`）
  - `java.util.concurrent.*` → `kotlin.concurrent.AtomicInt` / `kotlinx-atomicfu`
  - `java.text.*` → `kotlinx-datetime` 格式化 / `kotlin.text` 工具
- 数据库访问（SQLDelight）本身即 KMP：`.sq` 放 `commonMain/sqldelight`，driver 按平台注入
- **core 是 JVM-only 宿主引擎（by design，非临时）**：core 的实现（Koog / SQLite JDBC / java.io / ProcessBuilder / 信号）是 JVM 侧逻辑，只在 desktop / server 进程里跑；ios/android/wasm 是 server interface 的遥端，经 `ServerAiCore` REST 访问 core，**从不直连、也无需把 core 编译到这些平台**。core 用 KMP 插件 + commonMain/jvmMain 分层仅是源集组织，不代表跨平台能力——commonMain 里出现的 `java.*` 是历史遗留错位（无其他目标编译，故未暴露），新增 core 代码按 JVM 写即可，不必套 KMP 替代品。
- **server 模块是 JVM-only 薄转调层**：Ktor 服务端无 KMP 替代品；每个 endpoint 转调 `MederiAiCore`，不写业务逻辑。
- **判断标准**：写跨平台模块（`app:shared` / `inkcompose`）的新代码先问"这在 iOS/wasm/Android 上跑不跑得了"，
  跑不了就找 KMP 替代品，没有才允许 JVM 专属（放对应平台源集），并在代码注释里写明理由

### 代码语言与平台规则

- **语言偏好**：能用纯 Kotlin 解决的尽可能用纯 Kotlin；纯 Kotlin 无法满足时，JVM 端可以引入 Java 库来使用
  （Java 库只允许出现在 JVM 相关 source set，不得进入 commonMain）。
- **跨平台要求**：除非是编写平台特有的特性/功能，否则必须写跨平台的通用代码（放 commonMain）。
- **Web 目标**：web 端只支持 **wasmJs**，**不支持 js 目标**——新增模块/代码不得声明或使用 js 目标。

## 3. 仓库结构

```
mederi/
├── app/                    # Compose Multiplatform 应用
│   ├── desktopApp/         # Desktop 入口（真宿主：跑 core，UI 直调 MederiAiCore）
│   ├── androidApp/         # Android 遥控 UI 端入口（不跑 core，经 ServerAiCore 连 server）
│   ├── webApp/             # Web 遥控 UI 端入口（仅 wasmJs，不要 js 目标；不跑 core）
│   ├── iosApp/             # iOS 遥控 UI 端入口（Xcode 工程；不跑 core）
│   └── shared/             # 跨平台 UI + AiCore 契约（MederiAiCore 进程内实现 / ServerAiCore REST 客户端 / Server=server interface）
├── core/                   # 领域模型 + Manager + API + Koog 适配 + 存储（JVM-only 宿主引擎，by design）
├── server/                 # Ktor server interface（REST/SSE，每个 endpoint 转调 MederiAiCore；JVM-only，by design）
├── scripts/                # e2e 冒烟/审批/收敛测试脚本（项目测试资产，纳入版本管理）
└── inkcompose/             # 富文本渲染库（单 KMP 模块，未来独立发布）
```

inkcompose 内部结构、渲染管线、主题策略详见 `docs/architecture/03-inkcompose.md`。

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

桌面端 UI 开发与测试工作流（Compose Hot Reload + MCP UI 测试工具）及传统 unit/common test 注意事项详见 `docs/ui-dev-workflow.md`。

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
- **i18n（2026-09 起）**：UI 用户可见文案一律走 composeResources（app/shared/src/commonMain/composeResources/），
  默认 values/（中文）+ values-en/（英文），新增文案必须同时进两份，禁止在 Composable 硬编码用户可见文案
  （内容性数据如 AgentOption.name 除外）；未来语言预留：拉丁化满语 values-mnc-Latn/（暂不启用；传统满文竖排不适合软件 UI，不做）

## 5.5 执行沙盒与审批模式

**安全模型 = 分诊判断 + 沙箱**（无逐操作询问）：主代理按 §5.6 分诊流程自主判断执行路径，
沙盒（路径/命令强制）兜底。不变量 = 文件工具只能写项目目录、bash 走 OS 级写沙箱（macOS Seatbelt / Linux bwrap 检测 / Windows 降级警告）、读全盘放行。全局白名单在设置页配置。

**不变量（代码强制，全平台）**：
- 文件工具（write_file/edit_file/apply_patch）只能写项目 directories + `.mederi/` + 全局白名单；读全盘放行
- **同文件并发写硬拒绝**（`FileWriteRegistry` try-lock，跨 turn/跨子代理/跨 session 生效）
- bash：macOS Seatbelt / Linux bwrap（只检测不代装）/ Windows 降级警告
- 进程回收：`list_processes`/`stop_process` 宿主侧按 `ProcessRegistry` 整组回收；**只杀 mederi 自己启动的进程**
- AgentMode 两模式工具集完全一致，**唯一区别 = 计划批准者**：APPROVAL 等用户批准，AUTONOMOUS 自动批准立即执行
- `subagent`(action=SPAWN) planId/subtaskIndex 可选：携带时硬校验 spec 存在性；不带 planId 走 ad-hoc 执行路径

沙盒实现细节（FileWriteRegistry/ProcessRegistry/并行调度实现/系统环境注入/提示词拼装）及审计备忘详见 `docs/agent-internals.md`。

### 计划/Spec 分层（硬性）

- create_plan = WHAT（用户批准的对象，含每个子任务的 verification 契约：命令 + 预期结果 + 机器校验字面量）
- 批准后 generate_spec 逐子任务派生 HOW（行级实现规范，写入 Subtask.spec，brief 永不覆盖）
 - subagent(SPAWN, planId, subtaskIndex) 执行存储的 spec——但 generate_spec 后**并非每子任务必 spawn**，按「执行路径选择」（§5.6）判据逐子任务选直接执行或 spawn
- verify 两分支（根因轴，**先验实现、实现无误再验计划**）：
  - PASS → 下一个子任务
  - rootCause=IMPLEMENTATION（spec 清楚、执行没做到）→ converge_plan 追加补救 → 重执行
  - rootCause=PLAN（实现照 spec 做到、计划本身错）→ append-only 修订（update_verification 改契约 / generate_spec reason= 改 spec）→ 重执行
- spec 内部自相矛盾（不可满足，非"跟代码对不上"）时禁止自行修改，强制 `ask_user`

**验证机器硬校验**：verify_subtask 无条件执行验证命令；exit 0 + expectStdoutContains 全过 = 机器 PASS；exit 非零 / 缺字面量 / 命中 expectStdoutNotContains = 机器 FAIL（声明 PASS 时拒绝存储）；机器 PASS 而模型坚持 FAIL/PARTIAL → 记非 PASS + machineMismatch=true（异常态，主代理必须告知用户）。

**验证契约撰写自检（rubric）**：
1. **行为改动禁只用编译验收**：改了行为（新增逻辑 / 改分支 / 改输出）的子任务，验证命令必须断言"行为发生了"（grep 实现标记 / assert / 测试输出），不能只 `./gradlew compile`。`expectStdoutContains` 字面量应钉"行为标志"（如新增方法名、关键分支的日志/输出），而非"编译成功"之类的无信息量字面量。
2. **`! grep X` 否定断言前先确认 X 在正确实现里无合法 fallback**：如 `! grep ConversationStatus.Error` 会误伤合法的 else 兜底分支。改用正向行为断言（"能 grep 到读 payload 的代码"而非"代码里零 Error 字面量"）。
3. **grep 路径先 read 实际文件确认**：写 grep 验证命令前，先 `read_file` 目标文件确认路径、包名、类名拼写——不凭记忆或 grep 模糊命中写路径。

**严格 append-only（spec + verification）**：spec 修正（generate_spec 覆盖既有 spec）必须给 reason，完整旧 spec 文本保存进 specChanges；verification 契约修正（update_verification）必须给 reason，完整旧/新契约保存进 verificationChanges。信息零销毁，生效值 = Subtask.spec / Subtask.verification（最新）。

**并行调度硬性约束**：
- `create_plan` 必须单独发一条消息，不得与任何工具同消息批量
- **禁止同消息混发 `generate_spec` 与 `subagent`(SPAWN)**——先为所有独立子任务生成 spec，再同消息一起 subagent(SPAWN)
- 并行批量时杜绝竞争：同文件并发写已由 `FileWriteRegistry` 代码级硬拒绝；不重复执行同一命令靠 AI 自律

## 5.6 分诊流程 Triage Flow

**一句话**：主代理是唯一分诊台，对每个用户请求自主判断走哪条路径——信任 AI 判断，无代码门禁，沙盒兜底。

### 三条分诊路径

```
用户请求 → 主代理判断：
├─【纯读】问答/讨论/分析 → 主代理直接读文件回答；
│    读不够深（跨多文件/链路长/根因未知）→ subagent(SPAWN_RESEARCHER) → 完整报告 → 据此回答
├─【小改动】已知根因/简单逻辑/几行代码 → 主代理直接 apply_patch/edit_file/write_file
│    （不建 plan、不 spawn；可用 apply_patch / edit_file / write_file）
└─【复杂改动】多文件/逻辑变化/需用户决策 → Plan Loop：
     （理解不足先 subagent(SPAWN_RESEARCHER)）→ create_plan → 批准 → generate_spec
     → 执行路径选择（见下「执行路径选择」）：
        ├─【直接执行】主代理已读透目标代码 + 少量工具调用 + 无并行收益
        │    → apply_patch/edit_file/write_file + bash → verify_subtask
        └─【subagent(SPAWN)】需读大量未知文件 / 可与其他子任务并行 / 多轮工具往返需隔离上下文
             → END TURN（子代理后台跑，完成自动唤醒父 turn）→ 唤醒后 verify_subtask
      verify_subtask（机器无条件执行验证命令 + 字面量硬校验）：
       ├─ PASS → 下一个子任务
       ├─ rootCause=IMPLEMENTATION（spec 清楚、执行没做到）→ converge_plan 追加补救 → 重执行
       └─ rootCause=PLAN（实现照 spec 做到、计划本身错）→ append-only 修订
            （update_verification 改契约 / generate_spec reason= 改 spec）→ 重执行
      → 全部 PASS → 归档
```

小改动 vs 复杂改动的判断由 AI 自主做出；拿不准时**先调研再决定**。

### 执行路径选择（硬性判据）

generate_spec 之后、执行之前，主代理逐子任务判断走**直接执行**还是 **subagent(SPAWN)**。判据不是"是不是 plan 子任务"，而是"spawn 有没有净值收益"：

- **直接执行**（apply_patch / edit_file / write_file + bash，然后 verify_subtask）——满足以下**全部**条件：
  1. 主代理在调研 / 写 spec 阶段已读透目标代码，执行所需上下文已在主线
  2. 执行动作是少量工具调用（预计几次写 + 一次编译 / 验证），不会把大段文件内容灌进主线上下文
  3. 无并行收益可图（该子任务依赖前序，或后续子任务都串行）
- **subagent(SPAWN)** ——满足以下**任一**条件：
  1. 执行需要读大量主代理尚未读过的文件 / 探索未知代码区，直接执行会膨胀主线上下文
  2. 与其他独立子任务可并行，spawn 能并行跑省墙钟时间
  3. 执行涉及多轮工具往返，隔离在子代理里更省主线 context budget

**核心原则**：spawn 的唯一正当理由是"隔离上下文膨胀"或"换并行"，不是"plan 子任务就该 spawn"。主代理已读透、已知道怎么改的子任务，直接改——让子代理重新发现主代理已知的东西是纯浪费（零上下文的子代理要重新读文件、重新理解，再加一轮异步往返延迟 + spec 转录损耗）。能并行 / 能隔离上下文膨胀的才 spawn。

### 拆小可验证（硬性要求）

create_plan 必须把需求拆成**多个小的、可独立验证的子任务**——每个子任务 = 一个 spec + 一个 verification
（具体命令 + 预期结果 + 通过标准）。绝不把多个改动塞进一个含混的子任务；无法独立验证就继续拆。
验证标准在 plan 批准时即对用户可见（PlanStore Markdown 每个子任务带 `#### Verification` 段）。

### 破坏性决策先 ask_user，计划里禁止 User Review 段（硬性要求）

计划文档里**不允许出现『需要你确认（User Review Required）』段**——需用户拍板的破坏性变更/重大取舍/高风险操作，一律在 write_plan 之前走 ask_user 解决；计划只反映已拍板定下来的结果。Open Questions（非阻塞默认）保留为计划内的低成本否决通道。

### 减少用户负担：需要拍板时给选项，别抛开放问题（硬性要求）

当确实需要用户拍板时（破坏性变更、重大取舍、方向分歧），**走 ask_user 并给出可选答案让用户选**，不要在正文里抛一个开放问题让用户自己组织回答——用户从选项里点选一个，心智负担远小于从零构思答案。

判据：
- **需要用户拍板** → 走 ask_user，提供 concrete options（A / B / C 各配一句理由），让用户点选；**不要**写成"要我把这条规则落进 AGENTS.md 吗？你确认方向后我来改"这种开放问句
- **有合理默认值、无实质分歧** → 不必走 ask_user，直接取默认 + 走 openQuestions 标注（"选了 X 因为 Y，不同意可在聊天里否决"）
- ask_user 的问题应是"在已给选项里选哪个"，不是"你觉得该怎么办"

### 并行执行

**相互独立（无 dependsOn、不写同一批文件）的子任务用并行 spawn 执行**：先为这些子任务逐个
`generate_spec`，然后同一条消息发多个 `subagent`(SPAWN) 一起跑，结束 turn。子代理完成后
**自动唤醒父 turn**（同会话多个完成通知合并成一条内部消息、一次 turn），唤醒后逐个 `verify_subtask`。
有依赖的子任务保持串行。并行度不设上限，由 AI 自己判断——信任 AI 调度，代码不设闸门（仅沙箱兜底）。

三角色职责、代理继承策略中心化、上下文挂载、工作文件落地表、子代理主动上报机制、安全网、审计备忘详见 `docs/agent-internals.md`。

## 5.7 存储架构（双库 + 设备本地偏好）

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

**data.db 不再允许删除重建**——数据库已有大量真实任务数据，必须用版本化迁移保留：

- **迁移机制**：`Mederi.kt` 的 `migrateDataDb()`（`core/src/commonMain/kotlin/xyz/mederi/Mederi.kt`）用 `PRAGMA user_version` 跟踪 schema 版本，低于当前版本则按顺序跑迁移脚本（每条幂等 ALTER，列已存在时 `runCatching` 吞掉），跑完写新版本号
- **当前版本号** = `DATA_DB_VERSION`（`Mederi.kt` 中的常量）
- **新增 schema 变更流程**：① 改 `.sq`（全新库用）② 在 `migrateDataDb` 的 migrations map 加一条 ALTER 脚本 ③ 递增 `DATA_DB_VERSION`
- **开发期特征**：每次新增迁移脚本时，可以将前一个迁移脚本删掉（因为 dev 自己的数据库已经跑过那些迁移了），只保留最新一条迁移脚本。这不是删数据库，是删迁移代码——迁移是单向的，跑过的不会再跑
- config.db 仍用 `Schema.create`（IF NOT EXISTS），无迁移机制；config.db 无用户任务数据，必要时可删重建（仍需通知用户）
- **删除任何用户数据文件（data.db、config.db、旧版 JSON）之前，仍必须先通知用户、经用户批准才能执行，不可以问都不问擅自主张。** AI 会话中做此类操作同样适用此规矩。

## 6. AiCore 契约（app/shared ⇆ server）

对外契约 = `app/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCore.kt`（KMP commonMain 接口）。
两个实现各走一侧：`MederiAiCore`（jvmMain，进程内直调 core）+ `ServerAiCore`（commonMain，经 REST + SSE 遥控 server）。

**契约同步规则（硬性义务）**：AiCore.kt（contract）→ MederiAiCore（jvm 桥）→ ServerAiCore（REST 桥）→
server 路由（`Server.serverModule`），任何变动四处同步、缺一即编译失败或运行期 404。
新增 DTO 放 `contract/dto` / `contract/models`（kotlinx-serialization，commonMain）。

事件流：core `eventBus` → `MederiAiCore.events()`（进程内直收）与 `GET /v1/events` (SSE)（遥控端），客户端用 commonMain 的 `SnapshotReducer` 本地聚合快照。**事件消费方（自动改名等 UI 层特性）一律挂 `MederiAiCore.initialize()`，不挂宿主 main**（见 §1.5）。

Server interface 实现细节（端口记忆/密码门/Cloudflare 隧道）详见 `docs/remote-control.md`。

### 统一对话生命周期（2026-10）

- UI/API 只通过统一发送入口提交用户输入；不得在 UI 保存待发队列或选择排队/立即发送，Core 决定接纳、持久化、安全边界消费和继续执行。
- 用户输入使用稳定消息 ID，接收确认与 UI 对账只按 ID，不按正文；重复正文与附件消息不得互相确认。
- UI 显示段以用户消息为边界，同一工作 ID 不得跨插话气泡合并后续过程。正常主 turn 的阶段回复（例如已派发子代理、等待结果）由 Core 确认后外显，派发状态条及交互卡保持可见；阶段交付与整项工作完成分别处理，后续回复不得撤销先前交付。
- 工作期间的推理、工具和中间说明归工作过程；只有 Core 明确确认的本轮完整响应正文作为正式交付，必须保留完整响应，不按最后文本块推测。
- 跨供应商/模型/新旧 API 使用同一生命周期，不要求供应商 phase 或模型在正文写类型标签。提问/审批保持独立可操作；异常、取消、截断不冒充最终交付。

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

模型元数据所有权与合并唯一真理源（AIModel 字段所有权 / ModelMerge / 图片能力门禁链路）详见 `docs/model-metadata.md`。

## 8. 当前状态

- core / server / app UI：Koog 适配 + 供应商管理 + 计划系统（verify-converge 收敛循环）+ SSE + wasm UI 已就绪
- 分诊流程（Triage Flow）：已落地（§5.6）；执行沙盒已落地（§5.5 + `docs/sandbox-plan.md`）
- 存储架构：双库已落地（§5.7）——config.db / data.db + 设备本地 preferences；原始消息 API（listRaw）已就绪
- 会话自动命名：`SessionTitleService` 挂 `MederiAiCore.initialize()`（§1.5），两个 interface 都生效
- Todo 系统：`update_todo`（sessions.todos 持久化 + `# Current Todo` 回注入 + UI TodoListCard），与 plan 子任务展示解耦（plan 子任务只走 planApprovals 投影表），已落地（详见 `docs/todo-system-plan.md`）
- inkcompose：单 KMP 模块已接入 `app:shared`（jvmTest 2600+ tests 全绿，四平台编译通过）
