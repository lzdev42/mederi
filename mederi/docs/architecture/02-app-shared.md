# 02 · app/shared（契约桥 + UI）与 server 模块

> 路径约定：`…/contract/` = `app/shared/src/commonMain/kotlin/xyz/mederi/core/contract/`；`…/jvm/` = `app/shared/src/jvmMain/kotlin/xyz/mederi/`。

## 1. AiCore 契约（`…/contract/AiCore.kt`）

对外唯一契约接口（KMP commonMain）。三实现：`MederiAiCore`（jvm 进程内直调 core）、`ServerAiCore`（commonMain，REST+SSE 遥控）、`MockAiCore`（UI 开发/预览）。

```kotlin
interface AiCore {
    val isReady: StateFlow<Boolean>
    suspend fun initialize(): Result<Unit>
    val projects: StateFlow<List<Project>>
    val providers: StateFlow<List<ProviderConfig>>
    val availableModels: StateFlow<List<ModelOption>>
    val availableAgents: StateFlow<List<AgentOption>>
    val builtinPresets: List<String>              // 内置供应商预设名（BuiltinProviders.allEntries）
    val configDir: String? get() = null           // 宿主配置/缓存基路径；纯 Web 返回 null

    // Project（单目录模型：一个项目恰好一个 directory）
    suspend fun createProject(input: CreateProjectInput): Result<Project>
    suspend fun renameProject(projectId: String, name: String): Result<Unit>
    suspend fun deleteProject(projectId: String): Result<Unit>       // 遍历会话统一走 deleteConversation

    // Conversation
    suspend fun createConversation(projectId: String, agent: AgentOption? = null): Result<Conversation>
    suspend fun deleteConversation(conversationId: String): Result<Unit>
        // 唯一删除封装入口: abortAndJoin → 删 session/history/diff → 删 .mederi 下 Mermaid png → 清本地缓存
    suspend fun renameConversation(conversationId: String, title: String): Result<Unit>
    fun observeConversation(conversationId: String): Flow<ConversationSnapshot>
    suspend fun getSnapshot(conversationId: String): Result<ConversationSnapshot>

    // Provider / Model / Key
    suspend fun configureProvider(providerId: String, input: ProviderUpdateInput): Result<Unit>
    suspend fun addBuiltinProvider(name: String, apiKey: String): Result<ProviderConfig>
    suspend fun deleteProvider(providerId: String): Result<Unit>
    suspend fun addProviderModel(providerId, providerModelId, name, supportsThinking=false,
        supportsImages=false, contextWindow: Int?=null, maxTokens: Int?=null,
        reasoningLevels: List<String>=emptyList(), isEnabled=true): Result<Unit>
    suspend fun updateProviderModel(providerId, modelId, /*全可空*/ ...): Result<Unit>
    suspend fun deleteProviderModel(providerId, modelId): Result<Unit>
    suspend fun setModelEnabled(providerId, modelId, enabled): Result<Unit>
    suspend fun refreshProviderModels(providerId): Result<List<String>>   // 只新增不碰存量
    suspend fun autoSetupProviderModels(providerId): Result<Int>          // 目录数据进存量模型唯一通道
    suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig>
    suspend fun addProviderApiKey(providerId, name, key, isDefault=false): Result<ApiKeyOption>
    suspend fun deleteProviderApiKey(providerId, keyId): Result<Unit>
    suspend fun setDefaultProviderApiKey(providerId, keyId): Result<Unit>

    // Message
    suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit>
    suspend fun abort(conversationId: String): Result<Unit>
    suspend fun rollbackToMessage(conversationId: String, messageId: String): Result<Unit>
    suspend fun resolveQuestion(conversationId, questionId, answers: List<List<String>>): Result<Unit>
    suspend fun resolvePlanApproval(conversationId, planId, approved: Boolean, model: ModelOption? = null, thinkingLevel: String? = null): Result<Unit>
    % 批准时刻输入框选中的模型/推理档位随批准手势传入（null=不改变，旧客户端兼容）——
    % core 批准时写入 session，同 turn 后续 spawn 的子代理按此模型执行（见 01-core.md SessionManager）
    suspend fun compressHistory(conversationId: String): Result<Unit>
    suspend fun listMessages(conversationId): Result<List<ChatMessage>>
    suspend fun listMessagesPage(conversationId): Result<MessagesPage>   // 消息+token统计(快照对齐落库)
    suspend fun getMessage(conversationId, messageId): Result<ChatMessage>
    suspend fun listRawMessages(conversationId): Result<List<RawMessageDto>>  // 调试: payload 原文
    suspend fun getProcessStats(): Result<ProcessStats>   // 被监控端 = AiCore 所在宿主进程
    suspend fun getFileDiffs(conversationId, messageId: String? = null): Result<List<FileDiff>>

    fun events(): Flow<CoreEvent>

    // Skill 管理（UI 薄触发，文件操作全在 core）
    suspend fun listSkills(): Result<List<SkillItem>>
    suspend fun getSkillsRoot(): Result<String>
    suspend fun setSkillsRoot(path: String): Result<Unit>
    suspend fun installSkill(url: String): Result<SkillItem>
    suspend fun uninstallSkill(name: String): Result<Unit>

    // AGENTS.md 生成（API 形态，暂无命令/UI 入口；读取/注入在 core 代码级自动完成，见 01-core.md §7.4）
    suspend fun generateAgentsFile(projectId, modelId: String? = null): Result<String>  // 返回写入内容；modelId 缺省用项目最近会话模型
}
```

**契约同步硬性规则**：`AiCore.kt → MederiAiCore → ServerAiCore → RemoteServer 路由` 任何变动四处同步，缺一即编译失败或运行期 404；新增 DTO 放 `contract/dto` / `contract/models`（kotlinx-serialization，commonMain）。

**配套契约接口**（与 AiCore 并列的平台能力注入）：
- `Terminal.kt`：`TerminalManager(getOrCreate/find/all)` + `TerminalSession(key/title/isRunning/exitCode/output()/write/resize/kill)` —— 仅本机 jvmMain 实现（PtyTerminalHub），遥控端 null。
- `SandboxHooks.kt`：`setSandboxExtraPaths(paths)` + `sandboxStatus(): SandboxStatusInfo?` —— 仅 MederiAiCore 实现；`SandboxStatusInfo(backend, available, shell, detail)`。
- `AiCoreProvider.kt`：`expect object AiCoreProvider { fun default(): AiCore }`。

## 2. 契约模型（`…/contract/models/` + `dto/`）

### 2.1 ChatModels.kt

- `enum ConversationStatus { Idle, Working, Error }`
- `Conversation(id, projectId?, title, status, createdAt: Long, updatedAt: Long, parentConversationId?=null, modelId?=null, modelProvider?=null, thinkingLevel: String?=null(仅回显 core 诊断值,非 UI 真理源), agent?=null, directory?=null)`
- `enum ChatRole { User, Assistant, System, Summary }`
- `ChatMessage(id, conversationId, role, blocks: List<ChatBlock>, createdAt, completedAt?, parentMessageId?, model?, agent?, isStreaming=false, error?=null, modelName?(assistant footer 模型显示名), agentMode?(APPROVAL/AUTONOMOUS), thinkingLevel?(实际推理档位), durationMs?(LLM 耗时))` —— footer 诊断字段由 `MederiModelMapper.toChatMessage` 从 core Message 诊断字段填充（completedAt ≈ createdAt + durationMs 下界估计）
- `sealed class ChatBlock(id)`（type 判别多态序列化）：`Text(id, text)` / `Reasoning(id, text)` / `ToolCall(id, name, state: ToolCallState)` / `File(id, name, url, mimeType?)` / `Diff(id, filePath, before, after)` / `Unknown(id, type)`
- `sealed class ToolCallState`：`Pending` / `Running(input: Map<String,String>)` / `Completed(input, output)` / `Failed(input, error)`
- `ToolCallUi(id, name, state, target?=null, isFailed=false)` —— VM 预计算展平展示模型；target = 命令原文 / 文件路径（会话目录内→相对路径，目录外→绝对路径）/ apply_patch 提取的文件清单；工具行展开区显示执行结果（Completed.output / Failed.error）
- `enum CoreEventType`（与 core EventType 同名一一对应，当前 23 个；`MederiModelMapper.toCoreEvent` 按 `valueOf(name)` 映射）
- `CoreEvent(type, sessionId, messageId?=null, payload: Map<String,String>, timestamp="")`
- `PastedTextAttachment(id, index, text, lineCount, charCount)`；`ImageAttachment(id, name, mimeType, bytes, base64DataUrl, width=0, height=0)`

### 2.2 其他 models

- **ConfigModels.kt**：`ModelOrigin{FETCHED, MANUAL}`；`ModelOption(id, name, provider, supportsThinking, supportsImages=false, supportsImagesOverride: Boolean?=null(用户覆盖,同步永不覆盖), reasoningLevels=[], providerModelId=id, origin=FETCHED, contextWindow?, maxTokens?, inputPricePerMillion?, outputPricePerMillion?, isEnabled=true)`；`AgentMode{APPROVAL, AUTONOMOUS}`；`AgentOption(id, name, description?, mode, model: ModelOption?=null, reasoningLevel?, systemPrompt?, tools=[])`
- **ProviderModels.kt**：`ProtocolType{OPENAI_CHAT("OpenAI 兼容"), OPENAI_RESPONSES, GOOGLE}`（displayName+placeholderUrl；`CREATABLE=[OPENAI_CHAT, OPENAI_RESPONSES]`）；`ReasoningLevels.SELECTABLE=["LOW","MEDIUM","HIGH","MAX"]`；`ProviderType{Builtin, Custom}`；`CustomModelEntry(id, name, supportsThinking=false, supportsImages=false, contextWindow?, maxTokens?, reasoningLevels=[], isEnabled=true)`；`ApiKeyOption(id, name, maskedValue, isDefault)`；`ProviderConfig(id, name, type, baseUrl?, isConnected, models: List<ModelOption>, customModels=[], supportsApiKey, supportsBaseUrl, protocolType=DEFAULT, apiKeys=[], reasoningLevels: Map<String,String?>=空(级别名→请求体JSON片段/null), responseSanitization=false)`
- **ReasoningMenu.kt（纯函数唯一真理源）**：`derive(providerLevels, modelLevels): List<String>`（推导聊天菜单档位，NONE 恒第一；显示条件=模型勾选了级别或供应商任一档有值）；`resolve(modelMemoryLevel, modelLevels): String?`（**唯一推导链：模型记忆 > 默认档(MEDIUM 优先否则首档)**；菜单空=不支持返回 null）
- **ProjectModels.kt**：`Project(id, name, directory: String, conversations: List<Conversation>)`
- **FilesystemModels.kt**：`FileChangeStatus{Added, Modified, Deleted}`；`FileChange(filePath, status)`；`FileDiff(filePath, before, after, additions, deletions)`
- **PermissionModels.kt**：`QuestionRequest(id, conversationId, questions: List<Question>)`；`Question(id, prompt, options, allowCustom, multiSelect=false)`；`PlanApprovalRequest(id, conversationId, planPath, title, summary="", subtaskCount=0, planContent?=null, status="PENDING")`（审批卡片只展示标题+子任务数，详细内容经 planPath 读取）
- **TodoModels.kt**：`TodoStatus`（wire 小写 pending/in_progress/completed/cancelled/failed）；`TodoItem(id, content, status, priority?=null)`；`internal TodoWireItem(content, status)`（事件 payload wire DTO，与 core encodeTodos 严格对齐，id/priority 不上 wire）
- **SkillModels.kt**：`SkillItem(name, description, location="", license?, compatibility?, allowedTools?)`——由 core `SkillInfo` 映射（UI 列表展示）
- **StatisticsModels.kt**：`TokenUsage(input=0, output=0, reasoning=0, cacheRead=0, cacheWrite=0)`（`total = input+output+reasoning`）；`CostSummary(total=0.0, currency="USD")`
- **CompactionModels.kt**：`CompactionConfig(auto?, tailTurns?, preserveRecentTokens?, reserved?, prune?)`
- **ProcessStatsModels.kt**：`ProcessStats(cpuUsage?, cpuCores=1, heapUsedBytes=0, heapCommittedBytes=0, heapMaxBytes?, rssBytes?, timestampMillis=0)`

### 2.3 dto/

- `ChatPromptInput(text, model: ModelOption?, agent: AgentOption?, thinkingLevel: String?, attachments: List<FileAttachment>, apiKeyId: String?=null)`；`apiKeyId` = 本次发送选定 key 的 ID（null=默认 key，从 AppState.selectedApiKeyIds 读当前模型供应商的选定 key）；`FileAttachment(name, mimeType, bytes)`
- `ConversationSnapshot(conversation, messages, tokenUsage, contextUsedTokens: Long=0(最近一条 Assistant inputTokens,与自动压缩同源), cost, pendingQuestion?, pendingPlanApproval?, planApprovals=[], todos=[], childConversations=[], errorMessage?(错误/警告简报,ErrorBoard 输入框顶部一行展示), errorId?(ErrorCollector 生成的错误 ID,JVM 端可 ErrorCollector.get(id) 取完整 ErrorRecord), errorDiagnostic?(完整分层诊断报告纯文本,点"详细报告"展开;server→wasmJs 的详情唯一载体), statusHint?(仅限流重试过程提示,内存态——不再承载断流警告,警告走 errorMessage/ErrorBoard), errorIsStreamInterrupted=false(failureMode==PREMATURE_CLOSE 时置 true,MESSAGE_DELTA 复位;UI 据此在 ErrorBoard 显示"继续"按钮))`
- `MessagesPage(messages, tokenUsage, contextUsedTokens)` —— MESSAGE_COMPLETED/ERROR 后对齐落库数据用
- `RawMessageDto(seq, messageId?, role, payload(原始 JSON), createdAt)`
- `CreateProjectInput(name, directory)`；`ReasoningConfigInput(levels: Map<String,String?>)`；`ProviderUpdateInput(name?, apiKey?, baseUrl?, enabled?, customModels?, reasoningParameter?)`；`CreateCustomProviderInput(name, baseUrl, apiKey?, customModels, type=DEFAULT, responseSanitization=false, reasoningParameter?)`
- wire 请求 DTO：`CreateConversationInput(projectId, agent)`、`RenameConversationInput(title)`、`RenameProjectInput(name)`、`BuiltinProviderInput(name, apiKey)`、`AddModelInput/UpdateModelInput/SetModelEnabledInput`、`AddApiKeyInput(name, key, isDefault=false)`、`ResolveQuestionInput(answers)`、`ResolvePlanApprovalInput(approved, model: ModelOption?=null, thinkingLevel: String?=null)`（批准时刻选中模型随批准手势传 core，写入 session 供同 turn spawn 动态读）、`ApiError(error)`、`ReadyInfo(ready, configDir)`、`PlanContent(content?)`
- Skill 组 DTO（`dto/SkillInput.kt`）：`SetSkillsRootInput(path)`、`InstallSkillInput(url)`、`SkillsRootResponse(path)`（String 直出 JSON 带引号，包一层类型安全）

## 3. SnapshotReducer（`…/contract/SnapshotReducer.kt`）

契约层 `CoreEvent` → `ConversationSnapshot` 的**纯逻辑**（跨平台，JVM 桥与 wasmJs 共用同一状态机）。

| 事件 | 聚合效果 |
|---|---|
| SESSION_CREATED | 原样返回 |
| SESSION_UPDATED | status=Working、清 errorMessage（新 turn 开始旧错误过时） |
| MESSAGE_DELTA | 在当前 streaming Assistant 占位消息上追加块（text/reasoning 合并到最后同类型块、tool_call 增量、image 新建 File 块），status=Working、清 statusHint |
| MESSAGE_COMPLETED | status=Idle、isStreaming=false、清 statusHint；断流警告以 **ErrorRecord payload**（error/errorId/fullDiagnostic, errorSeverity=WARNING, failureMode=PREMATURE_CLOSE）写入 errorMessage/errorId/errorDiagnostic，**failureMode==PREMATURE_CLOSE → errorIsStreamInterrupted=true** → **ErrorBoard 展示 + 可展开详细报告 + "继续"按钮**（`WorkspaceViewModel.continueAfterInterruption()` 重发 `"Continue"` 走正常 send 流程续写），会话保持 Idle（turn 真实完成）。正常收尾无这些 key → 三者保持原值；MESSAGE_DELTA 复位 errorIsStreamInterrupted |
| MESSAGE_ERROR | status=Error、errorMessage=payload["error"]（简报）、errorId=payload["errorId"]、errorDiagnostic=payload["fullDiagnostic"]、清 statusHint。**侧边栏红点语义**：状态点按 sessionStore 权威状态判定而非事件本身——ERROR→红点，IDLE（transient 可恢复，如限流重试耗尽）→蓝点，映射纯函数 `mapMessageErrorToStatus`（`…/jvm/core/bridge/MederiModelMapper.kt`） |
| STATUS | 仅 scope=provider 且 code=RETRYING 时写 statusHint="attempt/max"（带 `message` 时追加 "|serverMsg"，serverMsg=ErrorCollector.extractServerMessage 提取的供应商真实报错），不碰状态机；UI StatusBar 重试态第二行渲染 serverMsg |
| TOOL_CALLED | 完整 args 更新 ToolCall block(input)，状态 Running |
| TOOL_RESULT | 按 toolCallId 精确匹配（回退：最后 Running/Pending 同名），状态 Completed/Failed 并保留 input |

工具参数解析走 `ToolArgParser.parse`（commonMain，`SnapshotReducer` 与 `MederiModelMapper` 共用）：以 `JsonObject` 宽容解析为 `Map<String,String>`，数字/布尔转字面量、嵌套结构保留 JSON 文本——避免 `read_file.max_lines` / `execute_command.timeout_seconds` 等非字符串参数让整条 args 解析失败导致路径/命令丢失。
| QUESTION_REQUESTED / RESOLVED | 写/清 pendingQuestion |
| PLAN_APPROVAL_REQUESTED / RESOLVED | 写/清 pendingPlanApproval + planApprovals 去重列表 |
| TODO_UPDATED / PLAN_PROGRESS | 解码 payload["todos"] 整体替换快照 todos；解码失败丢弃事件保留先前快照 |
| SUBAGENT_*（STARTED/COMPLETED/ERROR/STOPPED） | **不改变会话快照**——子代理状态由独立的 `SubagentTracker` 聚合（VM 持缓存），会话快照只反映主代理视角 |

入口：`applyWithRefresh(snapshot, event, refreshPage)`（MESSAGE_COMPLETED 先发"完成状态"快照再回查落库对齐发第二个；MESSAGE_ERROR 回查后只发最终一个）；及不含回查的 `apply(snapshot, event)`。

### 3.1 SubagentTracker + 子代理 MVVM 缓存（`…/contract/SubagentTracker.kt` / `…/contract/models/SubagentModels.kt`）

SUBAGENT_* 事件 → 子代理缓存表的纯逻辑（与 SnapshotReducer 同风格，跨平台，无状态；调用方持状态逐事件 apply）：

- `SubagentTracker.apply(states: Map<agentId, SubagentState>, event): Map` —— STARTED 以 agentId 建条目（全量元数据来自 payload），终态覆盖 status；乱序终态（无 STARTED）安全跳过；非子代理事件原样返回
- `SubagentState(agentId, parentSessionId, role, modelId, modelName, reasoningLevel?, task, briefing?, status, startedAt)` —— 每个子代理一个 UI 缓存对象（MVVM Model）；"工作中" = status==RUNNING（真实 job 状态，SSE 空闲 10 分钟超时兜底判死）
- `SubagentToolResult` —— core `SubagentManager.StatusResult` JSON 的契约镜像（宽松解码），wait_agent 落库 tool result 的解析用

**VM 接线**（WorkspaceViewModel）：`allSubagents`（compose state，事件驱动）+ `subagents`（按当前会话过滤的派生 getter）+ `subagent(agentId)`（详情只读数据源）+ `subagentReportMarkdown(toolName, resultJson)`（`core/ui/SubagentReport.kt` 纯转换：wait_agent/agent_status 的 COMPLETED 结果 → 汇报 markdown，null=非汇报普通卡片渲染；UI 折叠卡片点开用 InkCompose 渲染）——汇报全文不进事件/缓存，它在数据库的 tool result 里。

## 4. 三实现架构图

```mermaid
flowchart TB
    subgraph impls["AiCore 的三个实现"]
        MAC["MederiAiCore (jvmMain)<br/>Mederi.local(configDir) 进程内直调<br/>+ SandboxHooks"]
        SAC["ServerAiCore (commonMain)<br/>Ktor Client REST + SSE<br/>Bearer 鉴权 + 断线重连(4次/2s)"]
        MOCK["MockAiCore (commonMain)<br/>内存 + MockScenarios 剧本"]
    end
    CORE["core: Mederi 实例<br/>mederi.projects/sessions/providers/models/modelCatalog"]
    RS["RemoteServer remoteModule()<br/>REST/SSE 路由"]
    subgraph mappers["jvm 桥 mapper（object 纯函数）"]
        MM["MederiModelMapper<br/>core领域→契约(隐藏块截断/ToolResult吸收/ReasoningMenu.derive)"]
        MI["MederiInputMapper<br/>契约DTO→core请求(图片转base64 dataUrl)"]
        EA["MederiEventAggregator<br/>初始快照构建 + refreshPage 落库回查<br/>+ planContent 磁盘读取增强"]
    end
    REDUCER["SnapshotReducer (commonMain)<br/>纯逻辑事件→快照状态机"]
    MAC --> CORE
    MAC --> mappers
    SAC --> RS
    SAC --> REDUCER
    MAC --> REDUCER
```

### 4.1 MederiAiCore（`…/jvm/core/bridge/MederiAiCore.kt`）

`class MederiAiCore(configDir: String, dispatcher = Dispatchers.Default) : AiCore, SandboxHooks`。

**initialize() 流程（顺序）**：
1. `mederi = Mederi.create { configDir; userAgent = AppInfo.userAgent }`（出站 HTTP User-Agent 唯一注入点：所有 Koog 链路请求带 Mederi 身份头）
2. `cleanupLegacyBuiltinProviders()` —— 删"无 API Key 且名字命中内置预设"的历史垃圾 Provider
3. `cleanupStaleRunningSessions()` —— 上次崩溃残留的 RUNNING session 逐个 `abortAndJoin` 复位（**必须走 abortAndJoin 而非 abort**：abort 对"无活跃 turn 的残留 RUNNING"整体 no-op，activeJobs 是内存态新建进程后为空，状态永远卡住；abortAndJoin 对陈旧 RUNNING 兜底复位 IDLE）
4. `syncBuiltinProviders()` —— 内置供应商 baseUrl/reasoningParameter/responseSanitization/modelsDevKey 与代码预设校验、不一致则更新
5. `refreshGlobalState()` —— 填充 projects/providers/availableModels/availableAgents 四个 StateFlow
6. `_isReady = true`
7. `mederi.modelCatalog.start()` —— models.dev 目录启动即拉 + 每小时刷新
8. `autoRefreshBuiltinGoogleModels()` —— 后台刷新已配 Key 的内置 Google 供应商模型（只新增不碰存量）
9. `autotitleService.start()` —— **会话自动命名挂在此处（谁初始化谁生效，desktop/server 天然一致）**

`events()` = `mederi.sessions.events().map { MederiModelMapper.toCoreEvent(it) }`；`observeConversation()` 委托 MederiEventAggregator（初始快照 + SnapshotReducer.applyWithRefresh + refreshPage 回查 + initialTodos hydration：Plan 投影 > session.todos）。

**错误水合机制**：store 重建快照（getSnapshot / observe 初始快照）从 `MederiAiCore` 的 `lastErrorBySessionId` 内存注册表水合 errorMessage/errorDiagnostic/errorId/errorIsStreamInterrupted（MESSAGE_ERROR 写入、MESSAGE_COMPLETED 带断流警告写入、新 turn SESSION_UPDATED 清除），使「切回 Error 会话」能看到失败原因——流式中的错误经事件流实时写入，但 store 重建初始快照时需从内存注册表补回（落库消息不含错误字段）。

**BuiltinProviders**（object，`…/jvm/core/bridge/BuiltinProviders.kt`）：内置供应商预设唯一真理源（Google Gemini / Agnes SG+CN / Hetzner / Empero / OpenCode Zen / OpenRouter / 商汤 SenseNova），`allEntries()` 按端点展开、`isBuiltinName()` 判定；预设含 baseUrl/协议/响应清洗/推理参数(modelsDevKey)。
**BuiltinAgents**（object，commonMain）：内置 Agent 唯一真理源 = AgentMode 双预设（autonomous 自主 / approval 审批），`byId(id)`；不再区分编程/通用工作用途。

### 4.2 ServerAiCore（`…/commonMain/core/bridge/ServerAiCore.kt`）

`class ServerAiCore(baseUrl, passwordProvider: suspend () -> String? = {null}) : AiCore`。
- Ktor client 配置：`defaultRequest` 统一带 `User-Agent: AppInfo.userAgent`（身份头唯一真理源 = `AppInfo.userAgent`，见 §4.3）。
- 鉴权：passwordProvider 非空时所有 REST + SSE 带 `Authorization: Bearer <password>`。
- initialize：校验 baseUrl → 轮询 `GET /v1/ready`（20s 上限、250ms 间隔）→ 拉 presets/agents/models/providers/projects → isReady。
- SSE：官方 SSE 插件，incoming data 段反序列化为 CoreEvent（失败丢弃），心跳注释帧不投递；两个流：`/v1/events`（全局）与 `/v1/sessions/{id}/events`（会话）。
- 错误：非 2xx 抛异常（优先 ApiError.error 文本），方法边界包 Result 失败。

### 4.3 AppInfo（`…/commonMain/AppInfo.kt`，应用身份唯一真理源）

- `VERSION`：应用版本常量，**源码不写版本字面量**。唯一出处 = Gradle 工程根 `version.json`（CI 基于 git tag 写入）；构建脚本（app/shared 的 `generateVersionSource` 任务）读取后生成 `AppVersion.kt`（`MEDERI_APP_VERSION`）注入这里。
- `userAgent`（lazy）：`Mederi/<version> (<os> <os-version>; <arch>)` —— 组合 `VERSION` + `platformInfo()`，所有出站 HTTP 请求（Koog 链路经 `MederiConfig.userAgent` → `MederiHttpClientFactory`；Ktor 客户端经 `defaultRequest`）只从这里取值。
- `platformInfo(): PlatformInfo(osName, osVersion, arch)`：`expect` 声明，jvm/android/ios/wasmJs 各一个 `actual`（见 00-overview 平台注入矩阵）；`normalizeOsName`/`normalizeArch` 为标准 UA 粒度映射（Windows NT / Mac OS X / Linux / Android / iOS；aarch64→arm64、amd64→x86_64）。
- 硬性规则：禁止在调用处自行获取版本号 / os / arch 再拼 UA。

## 5. RemoteServer 路由表（`…/jvm/core/remote/RemoteServer.kt`）

`RemoteServer.start(aiCore: MederiAiCore, requestedPort=8081, password?, webappDir?): RemoteStartResult`（幂等；**端口策略**=先按请求端口 bind，被占 → port=0 OS 挑空闲，`resolvedConnectors()` 读回实际端口并标记 portFallback）；`stop()` 幂等。
- install：ContentNegotiation(json)/SSE/CORS(anyHost + Authorization 等)；password 非空 → Authentication(bearer "mederi-remote")。
- webapp：webappDir 非空 → staticFiles("/")；否则 classpath `staticResources("/","static")`（内置 wasm 产物）；SPA fallback + preCompressed(GZIP) + .wasm Content-Type；`/` 与 `/v1/ready` 豁免鉴权。
- SSE：`respondEventStream(events)` —— 15s 心跳注释帧防代理断连；客户端断开结构化并发取消。

**路由清单**（`v1Routes(aiCore)` 内，可被鉴权包裹）：

| 方法+路径 | 转调 |
|---|---|
| GET `/info`、GET `/v1/ready` | ReadyInfo |
| GET `/v1/presets` / `/v1/agents` / `/v1/models` / `/v1/providers` / `/v1/projects` | 直接 respondData StateFlow 值 |
| GET `/v1/system/stats` | getProcessStats |
| POST `/v1/projects`；PATCH/DELETE `/v1/projects/{id}` | Project 组（单目录，无目录增删端点） |
| POST `/v1/sessions`；DELETE/PATCH `/v1/sessions/{id}` | Conversation 组 |
| GET `/v1/sessions/{id}/snapshot` \| `messages` \| `messages/raw` \| `messages/{messageId}` \| `diffs[?messageId=]` | 查询组 |
| POST `/v1/sessions/{id}/messages` \| `abort` \| `rollback` \| `questions/{questionId}` \| `plans/{planId}/approve`（body=`ResolvePlanApprovalInput(approved, model?, thinkingLevel?)`——批准时刻选中模型随批准传 core） \| `compress` | 动作组 |
| SSE GET `/v1/events`（全局）；GET `/v1/sessions/{id}/events`（按 sessionId filter） | 事件流 |
| POST `/v1/providers`；POST `/v1/providers/builtin`；PATCH/DELETE `/v1/providers/{id}` | Provider 组 |
| POST `/v1/providers/{id}/models`；POST `.../models/refresh`；POST `.../models/auto-setup`；PATCH/DELETE `.../models/{modelId}`；POST `.../models/{modelId}/enabled` | Model 组 |
| POST `/v1/providers/{id}/keys`；DELETE `.../keys/{keyId}`；POST `.../keys/{keyId}/set-default` | Key 组 |
| GET `/v1/mcp/servers`；POST `/v1/mcp/servers`（InstallMcpServerInput）；PATCH/DELETE `/v1/mcp/servers/{name}`；POST `.../enabled`；POST `.../verify`；GET `.../json`（→McpServerJsonResponse） | MCP 组（ServerAiCore 遥控桥，desktop 直调不经过） |
| GET `/v1/skills`；GET `/v1/skills/root`（→SkillsRootResponse）；POST `/v1/skills/root`（SetSkillsRootInput）；POST `/v1/skills/install`（InstallSkillInput → SkillItem）；DELETE `/v1/skills/{name}` | Skill 组（UI 薄触发：列表/根目录/安装/卸载，文件操作全在 core） |
| POST `/v1/projects/{id}/agents-file/generate`（GenerateAgentsFileInput → GenerateAgentsFileResponse） | AGENTS.md 生成：扫描项目生成（已存在则原地改进）项目根 AGENTS.md（暂无 UI 入口，纯 API 形态） |

响应助手：`respondResult(Result)`（Unit 成功返回 `{}`，失败按 MederiException 子类映射 404/400/409/500 + ApiError）、`respondData(裸值)`、`respondError`。

**PtyTerminalHub**（`…/jvm/core/remote/terminal/PtyTerminalHub.kt`）：终端会话注册表（TerminalManager 实现，desktop 与 server 共用）——一个 key=一个常驻 shell（pty4j，`$SHELL`→bash→sh、Windows cmd.exe，登录 shell，TERM=xterm-256color）；服务端 64KB scrollback 环形缓冲（attach 先回放再续流）；会话随宿主进程生命周期（进程退出→OS 关 pty master→SIGHUP）；`PtyTerminalSession` 提供 output 流/write/resize/kill。JVM-only（pty4j 无 KMP 替代品）。

## 6. server 模块（`server/src/main/kotlin/xyz/mederi/Application.kt`）

独立 JVM 进程 headless 部署薄启动器：
1. 读环境变量：`MEDERI_SERVER_PORT`(默认 8081)、`MEDERI_CONFIG_DIR`(默认 ~/.mederi)、`MEDERI_SERVER_PASSWORD`(非空则 /v1 Bearer 鉴权)、`MEDERI_WEBAPP_DIR`(可选外部 wasm UI)、`MEDERI_LLM_RETRY_MAX/MIN_MS/MAX_MS`（写 `LlmRetryConfig`）。
2. `MederiAiCore(configDir)` + `runBlocking { initialize() }`（失败记录 initError 不阻断启动）。
3. `embeddedServer(Netty, port, host="0.0.0.0") { remoteModule(aiCore, ReadyInfo(ready = initError==null, configDir), password, webappDir) }.start(wait = true)` —— 复用 shared 的 remoteModule，**路由代码零重复**。

## 7. AppState 与 ViewModel 层

### 7.1 AppState（`…/commonMain/core/ui/appstate/AppState.kt`）

`class AppState(aiCore, preferences: PreferencesStore, scope)`；宿主注入点 = `remoteControl: RemoteControlHooks?`、`terminalManager: TerminalManager?` 与 `uiBrowserHost: UiBrowserHost?`（内置 JCEF 浏览器宿主，仅 desktop 注入）；`canRenderJcef = uiBrowserHost != null`（遥控端/wasm 恒 false）；`LocalAppState` CompositionLocal。

**持久化字段**：

| 字段 | 持久化 key | 说明 |
|---|---|---|
| theme: AppThemeMode | `app.theme` | DARK 默认 |
| remoteControlEnabled / remoteControlPassword / remotePort | `remote.enabled` / `remote.password` / `remote.port`（默认 8081，成功后回写实际端口） | 遥控 |
| remoteServerState | —（内存） | Idle/Starting/Running(port, portFallback)/Failed |
| tunnelState | —（内存） | Idle/Starting/Running(url)/Failed(notInstalled) |
| selectedProjectId / selectedConversationId | `workspace.lastProjectId` / `workspace.lastConversationId` | 选中态 |
| selectedModel | `workspace.lastModelId` + `workspace.lastModelProviderId` | 带自愈：列表重建后重指向同款新实例 |
| selectedAgentId | `workspace.lastAgentId` | |
| sandboxExtraPaths | `sandbox.extraPaths`(JSON 数组) | 写穿 SandboxHooks |
| modelReasoningLevels: Map\<modelId, level\> | `workspace.reasoningLevel.$modelId` | **模型推理档位记忆**（ReasoningMenu.resolve 的第一优先输入） |
| selectedApiKeyIds: Map\<providerId, apiKeyId\> | `workspace.apiKey.$providerId` | **供应商 API Key 记忆**（跨重启恢复；缺省=用默认 key；会话发送经 `getApiKeyId(provider.id)` 注入 ChatPromptInput.apiKeyId） |

派生：`selectedAgentMode`（selectedAgentId × availableAgents combine）；`processStats`（后台 1s 轮询）。
扩展 Store（唯一真理源，生命周期绑定 AppState，供概览快捷卡片与后续市场双向同步）：
- `skillStore: SkillStore`：管理 `skills: StateFlow<List<SkillItem>>`、`skillsRoot: StateFlow<String>`，提供 `refresh()`、`install(url)`、`uninstall(name)`、`setRootDirectory(path)`。
- `mcpStore: McpStore`：管理 `mcpServers: StateFlow<List<McpServerItem>>`，提供 `refresh()`、`toggleEnabled(name, enabled)`、`install(json)`、`update(name, json)`、`delete(name)`、`verify(name)`、`getJson(name)`。

方法：`persist(key, value)`（异步写、pendingWriteCount 计数）、`flushPreferences()`（退出前同步等待归零，2s 兜底）、`applyConversationDefaults`（补位不覆盖）、`setRemoteControl/startRemoteControl/stopRemoteControl`、`startTunnel/stopTunnel`、`handleProjectDeleted/handleConversationDeleted`（唯一允许偏好与引擎状态对齐的地方）、`hydrate()`（启动恢复，列表就绪后限时 3s 回填）。


### 7.2 ViewModel 类图

```mermaid
classDiagram
    class AppState {
        +aiCore +preferences
        +theme/selectedModel/selectedAgentId/...
        +modelReasoningLevels(模型记忆)
        +startRemoteControl()/startTunnel()
        +handleProjectDeleted()/handleConversationDeleted()
    }
    class WorkspaceViewModel {
        +conversationId +snapshot: ConversationSnapshot?
        -snapshotCache: Map~convId, ConversationSnapshot~  % 按会话缓存快照
        -observeJobs: Map~convId, Job~  % 每会话常驻观察流(切走不取消)
        -pendingUserMessages: Map~convId, ChatMessage~  % 乐观消息
        +inputDraft: TextFieldValue  % 输入草稿唯一真理源
        -activeDockPanel: RightDockPanel?
        +chatItems: List~ChatListItem~  % derivedStateOf 展平
        +effectiveThinkingLevel: StateFlow  % 推理档位唯一真理源(ReasoningMenu.resolve)
        +attach(id) / detach() / send(text) / abort()
        +rollbackMessage(convId, msgId, text)
        +approvePlan(planId, approved) / replyQuestion(...) 
        +tryAttachImage(...) Boolean  % 图片能力门禁
        +openDiff/closeDiff + toggleDockPanel...
        % 全局事件监听：BROWSER_TASK_STARTED(browser=="jcef") 且 canRenderJcef → 自动展开 BROWSER 面板
    }
    class SidebarViewModel {
        +uiState(expandedProjectIds, isBusy, error)
        +filteredProjects: StateFlow  % 全量项目树（不再按工作用途过滤）
        +createProject/renameProject/deleteProject
        +addProjectDirectory/removeProjectDirectory
        +createConversation/deleteConversation/renameConversation
        +toggleProjectExpanded/selectProject/selectConversation/newSession
    }
    class TerminalViewModel {
        % 依赖 AppState.terminalManager, 不依赖 AiCore
        +tabKeys/activeKey/sessions/errors/unread/ended
        +selectTab/closeTab/addTab/restartTab/retryTab
        +bootstrapIfNeeded(project)  % 幂等自动开首个 tab
    }
    class RawMessagesViewModel {
        +rawMessages +isLoading
        +bind(conversationId)  % 拉取+订阅 events 过滤刷新
    }
    AppState --> WorkspaceViewModel
    AppState --> SidebarViewModel
    AppState --> RawMessagesViewModel
    AppState --> TerminalViewModel : terminalManager
    AppState --> UiBrowserHost : uiBrowserHost（desktop 注入 JCEF）
```

- **WorkspaceViewModel 要点**：`attach(id)` = **按会话常驻观察流 + 缓存渲染**：每个被 attach 过的会话有一条 `observeConversation` 观察流持续把最新快照写入 `snapshotCache`（即使 UI 已切到别的会话也不取消），切换会话只改 `conversationId`、立即用缓存渲染（无缓存时先 `getSnapshot` 拉初始再交给观察流）；`send(text)` = 校验就绪/模型/图片门禁 guardImageSupport（send 与 rollbackMessage 共用唯一实现）→ PromptComposer.compose → 乐观更新 → 无会话自动 createConversation → 有待审批先 resolvePlanApproval(false) → sendMessage（thinkingLevel 只发 computeEffectiveThinkingLevel()）；`rollbackMessage` = 先验后切（restoreInputFromMessage 反解主指令/大段文本/图片 → 本地切片 → rollbackToMessage）→ **成功后才把内容粘贴回输入框重建待发态**（inputDraft + pendingPastedTexts + pendingImages，用户切模型/模式/改内容后自行发送），失败走 ErrorBoard；`restoreInputFromMessage(targetMsg?, fallbackText)` 为纯函数（PromptComposer.parse 拆主指令与大段文本、data: File block base64 还原图片）；`chatItems` 预计算 toolSummary/target/headerSummary/isReasoningActive。**会话切换不再闪烁（2026-09）**：历史上切换回 Working 会话时从 store 重建初始快照，而流式中的 assistant 消息尚未落库（`TurnIncrementalPersister.persistAssistant` 只在 LLM 响应结束后写入），导致快照缺 streaming 数据、StatusBar 短暂显示"排队较长"警告。现改为常驻观察流 + 缓存，切回即渲染最新缓存、流式增量不丢。
- **TerminalViewModel key 约定**：`"project:<id>"`、`"project:<id>#<n>"`、`"tmp:<n>"`。

## 8. UI 组合结构（commonMain）

```mermaid
flowchart TD
    MA["MederiApp<br/>创建 AppState + LocalAppState 注入<br/>initialize → hydrate（全平台唯一入口）"]
    APP["App<br/>AppTheme(AppState.theme) 包裹"]
    MS["MainScreen<br/>创建 SidebarViewModel + WorkspaceViewModel<br/>宽 <768dp=全屏 Workspace+Sidebar 抽屉 Overlay<br/>否则 Row: Sidebar + Workspace（Row 底层 surfaceSidebar 防动画露白）<br/>侧边栏开合动画 expandFrom=Start 从左缘展开<br/>外层 InitLoadingOverlay + SettingsDialog"]
    SB["Sidebar<br/>项目/会话树 + 主题切换 + 设置入口"]
    WS["Workspace<br/>中央聊天区"]
    HDR["Workspace Header（标题/模型）"]
    LIST["LazyColumn 消息列表<br/>ChatCards: ThoughtAndActionsBlock/ReasoningBlock/ToolPill<br/>QuestionCard/PlanApprovalCard/SummaryCard/UserPastedTextCard<br/>AssistantMessageFooter（assistant 回复底部: 模型名·审批/自主·推理档·时长·完成时间）"]
    INPUT["ChatInputCard<br/>模型/Agent/推理档位选择器+附件+发送/停止<br/>与欢迎页共用同一 inputDraft<br/>顶部 ErrorBoard（错误/警告唯一出口）"]
    SBAR["StatusBar（原 TurnStatusBar）<br/>deriveTurnStatus(snapshot) 纯函数<br/>只显示 AI 运转状态（思考/生成/工具/重试），永不显示错误"]
    DOCK["RightDock<br/>6 入口图标 rail: OVERVIEW/DIFF/PLAN/SUB_AGENTS/ARTIFACTS/TERMINAL"]
    REP["RightExtensionPanel(360dp，拖拽调宽)<br/>宽度无固定上限：上限 = 工作区宽 - 对话区最小宽(360dp) - Dock 宽<br/>展开动画 expandFrom=End 从右缘展开<br/>按 activePanel 分发面板内容<br/>含 RawMessagesCard/图片与大文本阅读器"]
    SD["SettingsDialog<br/>6 Tab: PROVIDERS/GENERAL/SANDBOX/AGENTS/REMOTE/SYSTEM<br/>ProviderSettingsPanel+ProviderSettingsViewModel<br/>(Master-Detail/移动端下钻自适应)"]
    MA --> APP --> MS
    MS --> SB
    MS --> WS
    WS --> HDR
    WS --> LIST
    WS --> INPUT
    WS --> SBAR
    WS --> DOCK --> REP
    MS --> SD
```

- `ChatLayout.kt` 不是 Composable，是**布局常量对象**（contentMaxWidth=1000dp、userBubbleMaxWidth=680dp、sidebarWidth=260dp、rightPanelWidth=360dp、**conversationMinWidth=360dp（对话区最小宽度=手机宽度）**、**rightDockWidth=46dp**、headerHeight=40dp、turnSpacing=14dp 等）。
- 三个职责分离的条栏：**StatusBar**（消息区，AI 运转状态，`deriveTurnStatus(snapshot)`，永不显示错误；计时锚定"发送请求时刻"`WorkspaceViewModel.turnStartedAt`（send 时记录、turn 结束清除），每秒 `now - turnStartedAt` 重算——切会话回来不重置；**与 footer durationMs 语义不同：StatusBar=从发请求起算，footer=API 有回应起算到回复结束**）、**ErrorBoard**（ChatInputCard 顶部，错误/警告唯一出口：单行简报 + "详细报告"展开 + 关闭；断流时（快照 errorIsStreamInterrupted）额外显示"继续"按钮 → `continueAfterInterruption()` 重发 Continue 续写半截回复，数据源=快照 errorMessage）、**SystemInfoBar**（最底部，纯 CPU/RSS/JVM 资源监控，不接错误）。
- **AssistantMessageFooter**：assistant 消息轮次底部元数据条（模型名 · 审批/自主 · 推理档 · 耗时 · 完成时间），数据来自 core Message 诊断字段（modelName/agentMode/reasoningLevel/durationMs）经契约 ChatMessage 透传，`computeChatItems` 只挂在轮次最后一个文本块（AssistantFooterInfo）。诊断字段由 `TurnIncrementalPersister.persistAssistant` 增量落库时注入（否则 reconcile 时 assistant 消息被"已存在"识别、字段永不补上）；**durationMs = API 有回应（响应创建）→ 落库**（`withAssistantDuration`），非"从发请求起算"。
- 其余组件：`InitLoadingOverlay`（备用全屏遮罩）、`SegmentedControl`（AgentMode 复用）、`SubAgentComponents`（SubAgentCard/SubAgentTabContent）、`TerminalPanelContent`（多 tab 终端渲染，状态机全在 TerminalViewModel）、`TerminalView`（expect：desktop=jediterm+SwingPanel；wasm/移动端=遥控端占位）、`BrowserPanelContent`（右侧 BROWSER 面板：uiBrowserHost 注入则渲染 JCEF 浏览器，未注入显示"当前端不支持内置浏览器"占位——遥控/wasm 端门禁）。
- 渲染 AI 回复使用 `:inkcompose` 的 `MarkdownView`（见 03-inkcompose.md）。

## 9. 平台入口与特性

### 9.1 RemoteGate（`…/wasmJsMain/core/remote/RemoteGate.kt`）

```mermaid
flowchart TD
    A["wasmJs: ComposeViewport { RemoteGate { MederiApp() } }"] --> B{"启动探测<br/>localStorage 密码 GET origin + '/v1/providers' + Bearer"}
    B -- "200" --> C["Ready 直接渲染 content"]
    B -- "401/403" --> D["NeedPassword 弹密码输入"]
    D --> E["用户提交 → 同接口重试<br/>（真实服务端判定，不做本地比对）"]
    E -- 通过 --> F["写 localStorage('mederi.remote.password') → Ready"]
    E -- 失败 --> D
```

**硬性禁止 `?password=` URL 参数**（会留浏览器历史）。密码未验证不创建 AiCore。

### 9.2 SessionTitleService（`…/jvm/core/autotitle/SessionTitleService.kt`）

- 挂载：`MederiAiCore.initialize()` 内 `autotitleService.start()`（lazy 构造，注入 `Mederi`，构造时传 `{ refreshProjects() }`，改名成功后回调刷新侧边栏），订阅 `mederi.sessionManager.events()`。
- 触发：收到 `SESSION_UPDATED` 且该会话**首次**进入处理（内存 processedSessions）时判定；条件 = 标题仍为 "New Session" 且**恰好一条** user 消息（core durable-first 保证回查可见）。
- 选模型（所有层：**当前供应商优先 → 其他已连接供应商**；扫描范围 = 供应商**全部**模型，**不过滤 isEnabled**）：
  1. 免费模型：`mederi.modelCatalog.getFor(providerKey, baseUrl, modelId)` 价格 input==0 && output==0
  2. 小模型：名字含 `flash` / `lite`（大小写不敏感），排除含 `mini` 的
- 请求：走 `OneShotCompletion.execute(provider, model, key, "autotitle", ...)`（**不注入任何推理参数**，服务器默认）→ `mederi.sessionManager.rename(id, title)`；候选逐个试，请求报错推下一个。
- 兜底：候选耗尽 → 直接用用户发信息用的模型（`session.aiModel` 快照 id → provider 现值）再请求一次；仍失败 → `session_yyyy-MM-ddTHH:mm:ss`（本地时间）。
- 旧的 OpenCode Zen 免费模型链 / X-Session-ID / 自建 HttpClient / timestamp 命名已整体删除。

### 9.3 desktopApp（`app/desktopApp/src/main/kotlin/xyz/mederi/`）

- `main.kt` 注入：进程级 `PtyTerminalHub()` 单例 → `appState.terminalManager`；`onAppStateReady` 中若 aiCore 是 MederiAiCore → `appState.remoteControl = DesktopRemoteControlHooks(aiCore, webappDir)` + **创建 `JcefBrowserHost()` → `appState.uiBrowserHost` + `BrowserRegistry.register("jcef", BrowserKind.JCEF) { host.createAiTab() }`**（camoufox 在 MederiAiCore 注册，jcef 在 desktopApp 注册，幂等覆盖）；`LaunchedEffect` 观察 `remoteControlEnabled` 自动启停内嵌 server；`webappDir` = 环境变量 `MEDERI_WEBAPP_DIR` 或探测三个常见 wasm 产物路径；`onCloseRequest` 顺序收尾：`RemoteServer.stop()` → `terminalHub.shutdown()` → `browserHost?.shutdown()` → `stopTunnel()` → `flushPreferences()` → 退出。
- **内置 JCEF 浏览器（desktopApp `browser/`）**：`JcefBrowserHost`（UiBrowserHost 实现，"一个 tab = 一个 KBPage" 轻量标签容器）——`createAiTab()`（desktopApp 专属，**不在接口上**——core 仅有 jvm 目标，commonMain 禁止引用 core 浏览器类型）惰性初始化 KBrowser（JcefChecker 检查 + `initializeConfig(useOsr=true)` + `initializeKBrowser()`，与 inkcompose mermaid worker 共用运行时）→ `KBrowser.newPage()`（viewport-less，挂 KBWebView 渲染）→ 返回 `JCEFBrowserControl`；`BrowserContent` = tab 栏（新建/关闭/切换）+ 激活 tab 的 `KBWebView` + 定时回读 document.title；`closeTab`/`shutdown` 关闭 KBPage。`JCEFBrowserControl`（BrowserControl 实现，绑定单个 KBPage）：**start/close 均幂等空实现**（任务结束 finally 总调 close()，但 tab 保留让用户看结果，手动关或退出才回收）、navigate/click/type（click 聚焦后逐字符输入）/scroll/press/snapshot/screenshot 全落对应 KBPage——AI 切 tab 不影响绑定页。desktopApp 新增 `libs.kbrowser` 依赖（与 inkcompose 同版本同源）。`UiBrowserHost`（commonMain）= `isAvailable` + `@Composable BrowserContent(colors)` + `shutdown()`，不引用 core 类型（wasm/移动端可编译）。
- `DesktopRemoteControlHooks.kt`：`start(port, password) = RemoteServer.start(...)`；`localAddress` = 枚举 site-local IPv4（10/8、172.16/12、192.168/16）；`isCloudflaredInstalled()` = `cloudflared --version` 探测（未装 UI 提示自行安装，不代装）；`startTunnel(port)` = **pty4j** 真实 pty 拉起 `cloudflared tunnel run`（读 `~/.cloudflared/config.yml`），幂等；**生命周期** = 本进程退出 → OS 关 pty master → SIGHUP → cloudflared 退出（无需看门狗）；`parseTunnelDomain()` = 解析 config.yml 第一条 ingress hostname 拼 `https://<host>`。

### 9.4 其他入口

- **androidApp**（`MainActivity.kt`）：`MermaidCacheConfig.setBaseDirectory(cacheDir)` + 注入 `AndroidAppContext.applicationContext`（SharedPrefs 工厂）→ `setContent { App() }`；AiCoreProvider.android = ServerAiCore（100% 遥控端）。
- **webApp**（wasmJs `main.kt`）：`ComposeViewport { RemoteGate { MederiApp() } }`。
- **iosApp**：SwiftUI `WindowGroup → ContentView → ComposeView(MainViewControllerKt.MainViewController())`；shared 的 `MainViewController() = ComposeUIViewController { App() }`；AiCoreProvider.ios = ServerAiCore（暂硬编码 127.0.0.1:8081）。

## 10. preferences 契约（`…/contract/preferences/`）

- `PreferencesStore`：`suspend getString(key): String?` / `putString(key, value)` / `remove(key)`；默认 `getBoolean/putBoolean`（字符串编解码）。
- `PreferencesFactory`：`expect fun defaultPreferencesStore(): PreferencesStore`。
- 实现：`InMemoryPreferencesStore`（测试/兜底）；`JsonFilePreferencesStore(file, fileSystem)`（jvm/android/ios 共用：Mutex 串行化、临时文件+原子 rename、解析失败先备份 `.corrupt-*.bak` 再继续，绝不静默覆盖）；android `SharedPrefsPreferencesStore`；wasmJs `WasmJsPreferencesStore`（localStorage 键前缀 `mederi.pref.`，禁用时降级内存）。

## 11. util/ 与 Mock/

- **PromptComposer**：粘贴大文本判定（`isLargeText` ≥800 字符或 ≥15 行）+ Prompt 组装/解析（compose 主指令在前、粘贴文本以编号 XML `<pasted_text>` 标签追加在后；parse 反向拆分）。
- **GitUtils**：`expect fun getGitBranch(directoryPath): String?`。
- **PlatformUtils**：`expect openUrl/openFile` + 纯函数 `formatContextWindow`(token→"x.x万/K")、`formatBytes`、`formatCpuUsage`。
- **ClipboardHelper**：`expect object PlatformClipboard { getImage()/getText() }`（读系统剪贴板图片/文本）。
- **DirectoryPicker**：`expect suspend fun pickDirectory(): String?`。
- **Mock 体系**（`…/core/mock/`）：`MockAiCore`（实现全部 AiCore；initialize 装 MockSeedData 后 500ms 就绪；sendMessage 起协程跑 MockScenarios 剧本；resolveQuestion 用 CompletableDeferred）+ `MockIdGenerator` + `MockScenarios`（三个可复现剧本：流式 reasoning、tool call Pending→Running→Completed、问询卡片等）+ `MockSeedData`（2 供应商/5 preset/3 项目/若干会话含子会话与错误会话）。用途：未接真实 core 的平台兜底 + UI 开发预览。

## 12. theme/Theme.kt

主题机制：`AppThemeMode`（AppState 持久化 `app.theme`）→ `AppTheme(theme)` Composable 统一注入 Material3 ColorScheme；DiagramTheme/CodeTheme/LatexTheme 由 inkcompose 侧从 colorScheme 派生（`DiagramTheme.material3(colorScheme)`）。
