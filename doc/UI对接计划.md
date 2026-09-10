# UI 对接计划

> **目的**：本文档是 UI 层与 core 层对接的完整地图。新对话只需读本文件 + 被引用的源文件即可开始干活，不需要重新扫描代码库。

---

## 1. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  desktopApp / androidApp / iosApp / webApp                    │
│  main() → AiCoreProvider.default() → AiCore 实例              │
│  → AppState(aiCore) → ViewModels → Compose UI                 │
└──────────────────────┬───────────────────────────────────────┘
                       │ 所有平台共享 commonMain 代码
                       │ 唯一差异：AiCoreProvider 的 actual 实现
                       ▼
┌──────────────────────────────────────────────────────────────┐
│  ui/shared (KMP 模块)                                         │
│                                                               │
│  commonMain (所有平台共享，零 core 依赖)                      │
│    contract/AiCore.kt            ← UI ↔ core 的契约接口       │
│    contract/AiCoreProvider.kt    ← expect：平台选择实现        │
│    contract/dto/*.kt             ← 输入输出 DTO              │
│    contract/models/*.kt          ← UI 数据模型               │
│    contract/preferences/*.kt     ← 偏好存储                  │
│    mock/MockAiCore.kt            ← 非 JVM 平台的占位实现      │
│    ui/AppState.kt                ← 全局状态持有者             │
│    ui/WorkspaceViewModel.kt     ← 会话视图模型               │
│    ui/SidebarViewModel.kt        ← 侧栏视图模型              │
│    ui/...                        ← Compose 组件              │
│                                                               │
│  jvmMain (依赖 :core，仅 JVM 平台)                            │
│    bridge/MederiAiCore.kt        ← AiCore 的真实实现          │
│    bridge/MederiEventAggregator.kt ← 事件流聚合               │
│    bridge/MederiModelMapper.kt   ← core 模型 → UI 模型映射   │
│    bridge/MederiInputMapper.kt   ← UI DTO → core 请求映射   │
│    contract/AiCoreProvider.jvm.kt ← actual: 返回 MederiAiCore│
│    preferences/JsonFilePreferencesStore.kt                   │
│                                                               │
│  androidMain / iosMain / wasmJsMain                           │
│    contract/AiCoreProvider.*.kt  ← actual: 暂返回 MockAiCore │
│    (未来：返回 RemoteAiCore 走 HTTP)                          │
└──────────────────────┬───────────────────────────────────────┘
                       │ jvmMain 依赖
                       ▼
┌──────────────────────────────────────────────────────────────┐
│  core (纯 Kotlin 库)                                          │
│    Mederi.kt                     ← 入口：local() / backend()  │
│    api/SessionApi.kt             ← 会话 API 接口             │
│    api/ProviderApi.kt            ← 供应商 API 接口           │
│    api/ProjectApi.kt             ← 项目 API 接口            │
│    api/AgentApi.kt               ← Agent API 接口          │
│    domain/model/MederiEvent.kt   ← 事件类型定义             │
│    domain/model/Message.kt       ← 消息领域模型             │
│    (详见 doc/mederi文档.md 和 doc/api.md)                    │
└──────────────────────────────────────────────────────────────┘
```

**关键设计**：
- `commonMain` 零依赖 `:core`，只有 `jvmMain` 依赖。这保证所有平台共享同一份 UI 代码。
- `AiCore` 接口是唯一契约。JVM 用 `MederiAiCore` 直连 core，其他平台暂用 `MockAiCore`。
- 未来远程支持：在 `commonMain` 加 `RemoteAiCore`（实现 `AiCore`，走 HTTP），所有平台可用，不改 UI 代码。

---

## 2. 对接点清单

### 2.1 AiCore 接口（契约层）

**文件**：`mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCore.kt`

这是 UI 层与 core 层的唯一契约。所有 UI 代码只引用此接口，不直接引用 core。

```kotlin
interface AiCore {
    // 生命周期
    val isReady: StateFlow<Boolean>
    suspend fun initialize(): Result<Unit>

    // 可观察状态（StateFlow，UI 直接 collect）
    val projects: StateFlow<List<Project>>
    val providers: StateFlow<List<ProviderConfig>>
    val availableModels: StateFlow<List<ModelOption>>
    val availableAgents: StateFlow<List<AgentOption>>

    // Project CRUD
    suspend fun createProject(input: CreateProjectInput): Result<Project>
    suspend fun renameProject(projectId: String, name: String): Result<Unit>
    suspend fun deleteProject(projectId: String): Result<Unit>
    suspend fun addProjectDirectory(projectId: String, directory: String): Result<Unit>
    suspend fun removeProjectDirectory(projectId: String, directory: String): Result<Unit>

    // Conversation / Session
    suspend fun createConversation(projectId: String): Result<Conversation>
    suspend fun deleteConversation(conversationId: String): Result<Unit>
    suspend fun renameConversation(conversationId: String, title: String): Result<Unit>
    fun observeConversation(conversationId: String): Flow<ConversationSnapshot>

    // Provider / Model
    suspend fun configureProvider(providerId: String, input: ProviderUpdateInput): Result<Unit>
    suspend fun refreshProviderModels(providerId: String): Result<List<ModelOption>>
    suspend fun createCustomProvider(input: CreateCustomProviderInput): Result<ProviderConfig>

    // Message
    suspend fun sendMessage(conversationId: String, input: ChatPromptInput): Result<Unit>
    suspend fun abort(conversationId: String): Result<Unit>
    suspend fun resolveApproval(conversationId: String, approvalId: String, approved: Boolean, feedback: String? = null): Result<Unit>
    suspend fun getMessage(conversationId: String, messageId: String): Result<ChatMessage>

    // Agent
    suspend fun updateAgent(agentId: String, input: UpdateAgentInput): Result<Unit>

    // Diff
    suspend fun getFileDiffs(conversationId: String, messageId: String? = null): Result<List<FileDiff>>
}
```

**对接规则**：
- 所有方法返回 `Result<T>`，不抛异常。UI 层检查 `isSuccess` / `isFailure`。
- 状态用 `StateFlow`，事件用 `Flow`。
- `observeConversation()` 返回 `Flow<ConversationSnapshot>`，包含消息列表 + 状态 + 待审批等。

### 2.2 MederiAiCore（JVM 桥接实现）

**文件**：`mederi/ui/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiAiCore.kt`

这是 `AiCore` 在 JVM 平台的实现。它持有 `Mederi` 实例，直接调用 core API。

**对接方式**：
- 构造时传入 `configDir`（如 `"~/.mederi"`），`initialize()` 时调用 `Mederi.local(configDir)` 创建 core 实例。
- 每个 `AiCore` 方法 → 调用 core API + `MederiModelMapper` 转换结果 + 刷新 StateFlow。
- `observeConversation()` → 调 `MederiEventAggregator.observe()`。
- `sendMessage()` → `MederiInputMapper.toMessageParts()` 转换输入 → `mederi.sessions.sendMessage()`。
- `resolveApproval()` → `mederi.sessions.resolveApproval()`。

**core API 对应关系**（详见 `doc/api.md` 和 `doc/mederi文档.md`）：

| AiCore 方法 | core API | 说明 |
|---|---|---|
| `initialize()` | `Mederi.local(configDir)` | 创建 core 实例 |
| `createProject()` | `mederi.projects.create()` | 创建项目 |
| `renameProject()` | `mederi.projects.rename()` | 重命名 |
| `deleteProject()` | `mederi.projects.delete()` | 删除（级联删 Session + History） |
| `addProjectDirectory()` | `mederi.projects.addDirectory()` | 添加目录 |
| `removeProjectDirectory()` | `mederi.projects.removeDirectory()` | 移除目录 |
| `createConversation()` | `mederi.sessions.create()` | 创建 Session |
| `deleteConversation()` | `mederi.sessions.delete()` | 删除 Session |
| `renameConversation()` | `mederi.sessions.rename()` | 重命名 |
| `observeConversation()` | `mederi.sessions.events()` + `mederi.sessions.listMessages()` | 事件流聚合 |
| `configureProvider()` | `mederi.providers.update()` + `mederi.providers.addKey()` | 更新供应商 + Key |
| `createCustomProvider()` | `mederi.providers.create()` | 创建供应商 |
| `refreshProviderModels()` | （暂未实现，返回当前已知模型） | 拉取远端模型列表 |
| `sendMessage()` | `mederi.sessions.sendMessage()` | 发送消息 |
| `abort()` | `mederi.sessions.abort()` | 中止当前 turn |
| `resolveApproval()` | `mederi.sessions.resolveApproval()` | 回复审批 |
| `getMessage()` | `mederi.sessions.getMessage()` | 获取单条消息 |
| `updateAgent()` | `mederi.agents.update()` | 更新 Agent 配置 |
| `getFileDiffs()` | `mederi.sessions.getFileDiffs()` | 获取文件差异 |

### 2.3 MederiEventAggregator（事件聚合）

**文件**：`mederi/ui/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiEventAggregator.kt`

把 core 的 `Flow<MederiEvent>` 聚合成 UI 需要的 `Flow<ConversationSnapshot>`。

**处理逻辑**：

| 事件类型 | 处理方式 | 状态 |
|---|---|---|
| `SESSION_CREATED` | 忽略 | ✅ |
| `SESSION_UPDATED` | 置 `conversation.status = Working` | ✅ |
| `MESSAGE_DELTA` | 追加 text/reasoning/tool_call delta 到 streaming Assistant 占位消息 | ✅ |
| `MESSAGE_COMPLETED` | 重新拉取历史消息，置 `status = Idle`，清 `errorMessage` | ✅ |
| `MESSAGE_ERROR` | 重新拉取历史消息，置 `status = Error`，填 `errorMessage` | ✅ |
| `TOOL_CALLED` | 忽略（由 MESSAGE_DELTA 和 MESSAGE_COMPLETED 覆盖） | ✅ |
| `TOOL_RESULT` | 忽略（同上） | ✅ |
| `APPROVAL_REQUESTED` | 构建 `PermissionRequest` 追加到 `pendingPermissions` | ✅ |
| `APPROVAL_RESOLVED` | 按 `approvalId` 从 `pendingPermissions` 移除 | ✅ |

**事件 payload 格式**（`MederiEvent.payload: Map<String, String>`）：

```
MESSAGE_DELTA:
  type = "text" | "reasoning" | "tool_call"
  content = 文本内容 / 推理内容 / 工具参数 JSON
  name = 工具名（仅 tool_call）
  state = "completed"（仅 tool_call 完成时）

MESSAGE_ERROR:
  error = 错误信息

TOOL_CALLED:
  tool = 工具名
  toolCallId = 调用 ID
  args = 参数 JSON

TOOL_RESULT:
  tool = 工具名
  toolCallId = 调用 ID
  output = 结果文本
  isError = "true" | "false"

APPROVAL_REQUESTED:
  approvalId = 审批 ID
  tool = 工具名
  description = 描述
  args = 参数 JSON

APPROVAL_RESOLVED:
  approvalId = 审批 ID
  approved = "true" | "false"
  feedback = 反馈（可选）
```

### 2.4 MederiModelMapper（模型映射）

**文件**：`mederi/ui/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiModelMapper.kt`（312 行）

core 领域模型 → UI 契约模型的纯函数映射。

**核心映射**：

| core 类型 | UI 类型 | 说明 |
|---|---|---|
| `Session` | `Conversation` | 含 modelId / modelProvider / thinkingLevel 回填 |
| `Message` | `ChatMessage` | 按 role 映射 |
| `MessagePart.Text` | `ChatBlock.Text` | |
| `MessagePart.Reasoning` | `ChatBlock.Reasoning` | content.joinToString("\n") |
| `MessagePart.ToolCall` | `ChatBlock.ToolCall` | state 从配对的 ToolResult 推导 |
| `MessagePart.ToolResult` | （吸收进对应 ToolCall） | 不单独显示 |
| `MessagePart.File` | `ChatBlock.File` | |
| `MessagePart.Image` | `ChatBlock.File`（带 mimeType） | |
| `Provider` | `ProviderConfig` | apiKeys 脱敏 |
| `AIModel` | `ModelOption` | |
| `AgentPreset` | `AgentOption` | |
| `Project` | `Project` | 含 conversations 子列表 |
| `FileDiff` | `FileDiff` | |

**ToolCallState 推导逻辑**（`MederiModelMapper:168-228`）：
- 有匹配的 ToolResult 且 `isError == false` → `Completed(input, output)`
- 有匹配的 ToolResult 且 `isError == true` → `Failed(input, error=output)`
- 无匹配的 ToolResult → `Pending`
- **已知问题**：ToolResult 的 `id` 为 null/blank 时不参与匹配（第 170-171 行 filter），对应 ToolCall 永远显示 Pending

### 2.5 MederiInputMapper（输入映射）

**文件**：`mederi/ui/shared/src/jvmMain/kotlin/xyz/mederi/core/bridge/MederiInputMapper.kt`（108 行）

UI DTO → core 请求对象的纯函数映射。

| UI DTO | core 请求 | 说明 |
|---|---|---|
| `CreateProjectInput` | `CreateProjectRequest` | |
| `ProviderUpdateInput` | `UpdateProviderRequest` | |
| `CreateCustomProviderInput` | `CreateProviderRequest` | 含 type 字段（OPENAI_CHAT / OPENAI_RESPONSES） |
| `UpdateAgentInput` | （在 MederiAiCore 中组装） | |
| `ChatPromptInput` | `List<MessagePart>` | text → Text, attachments → Image |
| `ChatPromptInput` | `AgentConfig` | model + agent + thinkingLevel |

### 2.6 AppState（全局状态持有者）

**文件**：`mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/ui/appstate/AppState.kt`（136 行）

持有 `AiCore` 实例和 UI 全局状态。

**职责**：
- 转发 `AiCore` 的 StateFlow（`isReady` / `projects` / `providers` / `availableModels` / `availableAgents`）
- 管理 UI 选择状态（`selectedProjectId` / `selectedConversationId` / `selectedModel` / `selectedAgentId` / `selectedThinkingLevel`）
- 持久化偏好到 `PreferencesStore`
- `hydrate()` 启动时加载偏好

### 2.7 WorkspaceViewModel（会话视图模型）

**文件**：`mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/ui/WorkspaceViewModel.kt`（177 行）

会话页面视图模型，消费 `ConversationSnapshot`。

**当前状态**：

| 方法 | 状态 | 说明 |
|---|---|---|
| `attach(id)` | ✅ | 订阅 `aiCore.observeConversation(id)` |
| `send(text)` | ✅ | 调 `aiCore.sendMessage()` |
| `abort()` | ✅ | 调 `aiCore.abort()` |
| `replyPermission(requestId, decision)` | ✅ | 调 `aiCore.resolveApproval()`，映射 AllowOnce/AllowAlways/Deny |
| `replyQuestion(requestId, answers)` | — | core 无问答机制，保留空壳 |
| `rejectQuestion(requestId)` | — | core 无问答机制，保留空壳 |
| `refreshFiles()` | — | `openDiff()` 已覆盖此功能，保留空壳 |
| `openDiff(messageId)` | ✅ | 调 `aiCore.getFileDiffs()` |
| `requestCompaction()` | — | core 无压缩机制，保留空壳 |

### 2.8 SidebarViewModel（侧栏视图模型）

**文件**：`mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/ui/SidebarViewModel.kt`（92 行）

项目/会话 CRUD 视图模型，调 `aiCore.createProject()` / `deleteProject()` / `createConversation()` 等。

状态：✅ 已全部接通。

### 2.9 AiCoreProvider（平台选择器）

**文件**：`mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCoreProvider.kt`（expect）

| 平台 | actual 文件 | 实现 |
|---|---|---|
| JVM | `jvmMain/.../AiCoreProvider.jvm.kt` | `MederiAiCore(configDir = "~/.mederi")` |
| Android | `androidMain/.../AiCoreProvider.android.kt` | `MockAiCore()` ← 占位 |
| iOS | `iosMain/.../AiCoreProvider.ios.kt` | `MockAiCore()` ← 占位 |
| wasmJs | `wasmJsMain/.../AiCoreProvider.wasmJs.kt` | `MockAiCore()` ← 占位 |

**未来**：非 JVM 平台改成 `RemoteAiCore(baseUrl)`，走 HTTP 连 desktop 内嵌 server。

---

## 3. 对接完成状态

### ✅ 已完成

| 组件 | 文件 | 说明 |
|---|---|---|
| AiCore 接口 | `contract/AiCore.kt` | 完整定义了所有方法 |
| MederiAiCore | `bridge/MederiAiCore.kt` | 全部方法已实现，调 core API |
| MederiEventAggregator | `bridge/MederiEventAggregator.kt` | 全部 9 种事件类型已处理 |
| MederiModelMapper | `bridge/MederiModelMapper.kt` | 全部 MessagePart 类型已映射 |
| MederiInputMapper | `bridge/MederiInputMapper.kt` | 全部 DTO 已映射 |
| AppState | `ui/AppState.kt` | 接管 AiCore StateFlow + 偏好持久化 |
| WorkspaceViewModel | `ui/WorkspaceViewModel.kt` | send/abort/approve/openDiff 已接通 |
| SidebarViewModel | `ui/SidebarViewModel.kt` | 全部 CRUD 已接通 |
| AiCoreProvider | `contract/AiCoreProvider.kt` | JVM actual → MederiAiCore |
| 审批事件流 | `MederiEventAggregator:94-114` | APPROVAL_REQUESTED / APPROVAL_RESOLVED |
| 审批决策下发 | `WorkspaceViewModel.replyPermission` | AllowOnce/AllowAlways/Deny → resolveApproval |
| 审批 UI 卡片 | `ChatCards.PermissionCard` | Inline 非模态，三种按钮 |
| 集成测试 | `MederiApprovalIntegrationTest` | 6 个测试，真实 Mederi + 全链路断言 |

### — 当前不需要做的事

| 项 | 原因 |
|---|---|
| `@Serializable` 标注 | 当前仅 JVM 平台，直连 core 不需要序列化。未来 RemoteAiCore 需要时再加 |
| 模型映射测试 | `MederiModelMapper` 已在 `MederiModelMapperTest.kt` 中覆盖 |
| 事件聚合器测试 | `MederiApprovalIntegrationTest` 已覆盖审批事件流 |
| 全量测试同时跑 | 当前测试间存在资源泄漏，全量跑会挂。和审批无关，是已有问题 |

---

## 4. 测试

### 4.1 测试文件索引

| 测试文件 | 位置 | 测试内容 |
|---|---|---|
| `MederiApprovalIntegrationTest.kt` | `jvmTest/.../bridge/` | 审批事件聚合 + ViewModel 决策 + ApprovalRequester（6 个测试） |
| `MederiModelMapperTest.kt` | `jvmTest/.../bridge/` | 模型映射 |
| `MederiInputMapperTest.kt` | `jvmTest/.../bridge/` | 输入映射 |
| `MederiAiCoreLifecycleTest.kt` | `jvmTest/.../bridge/` | AiCore 生命周期 |
| `MederiAiCoreAgentConfigTest.kt` | `jvmTest/.../bridge/` | Agent 配置 |
| `MederiAiCoreSendMessageTest.kt` | `jvmTest/.../bridge/` | 发送消息 |
| `MederiAiCoreFailurePersistenceTest.kt` | `jvmTest/.../bridge/` | 失败持久化 |
| `MederiAiCoreStreamingVerifyTest.kt` | `jvmTest/.../bridge/` | 流式验证 |
| `MederiAiCoreToolCallSmokeTest.kt` | `jvmTest/.../bridge/` | 工具调用冒烟 |
| `MederiAiCoreAdvancedToolCallTest.kt` | `jvmTest/.../bridge/` | 高级工具调用 |
| `MederiAiCoreRealProviderTest.kt` | `jvmTest/.../bridge/` | 真实供应商测试 |
| `MigratedUiFunctionalTest.kt` | `jvmTest/.../` | 迁移功能测试 |

### 4.2 运行测试

```bash
# 运行审批测试
./gradlew :ui:shared:jvmTest --tests "xyz.mederi.core.bridge.MederiApprovalIntegrationTest"

# 运行单个其他测试
./gradlew :ui:shared:jvmTest --tests "xyz.mederi.core.bridge.MederiModelMapperTest"

# 注意：全量测试目前存在资源泄漏问题，不建议全量跑
```

### 4.3 测试原则

1. **禁止掩耳盗铃**：测试必须用真实数据验证真实行为，不能 mock 掉被测逻辑然后断言 mock 返回了 mock 的值。
2. **必须有数据断言**：每个断言必须检查具体的值（字段值、列表大小、状态枚举），不能只检查"不报错"或"返回了对象"。
3. **集成测试用真实 core**：事件聚合、模型映射、审批流必须用 `Mederi` 实例（InMemory 模式）跑全链路，不能用 MockAiCore。

---

## 5. 未来远程支持备注

> **当前不实施，仅记录约束。写代码时不要破坏这些约束。**

### 5.1 架构约束

1. `AiCore` 接口在 `commonMain`，是唯一契约。不要在 UI 代码中直接引用 core 类型。
2. 所有 DTO/models 未来加 `@Serializable` 后可直接用于 JSON 传输，不要再加另一套 HTTP DTO。
3. `MederiEventAggregator` 的聚合逻辑未来需提取到 `commonMain`，供 `RemoteAiCore` 复用。当前在 `jvmMain`，不要把 core 类型泄漏到签名里。
4. `AiCoreProvider` 的非 JVM actual 目前返回 `MockAiCore`，未来改成 `RemoteAiCore(baseUrl)`。
5. server 模块当前是骨架，未来既可独立进程也可当库嵌入 desktopApp。

### 5.2 不要做的事

- 不要在 `commonMain` 引入 `:core` 依赖
- 不要在 UI 组件中直接出现 core 类型（`Session`、`Message`、`MederiEvent` 等）
- 不要在 `AiCore` 接口上加平台特定方法

---

## 6. 关键文件索引

| 文件 | 行数 | 职责 |
|---|---|---|
| `ui/shared/src/commonMain/.../contract/AiCore.kt` | ~50 | UI ↔ core 契约接口 |
| `ui/shared/src/commonMain/.../contract/AiCoreProvider.kt` | ~10 | 平台选择器（expect） |
| `ui/shared/src/commonMain/.../contract/dto/*.kt` | 5 文件 | 输入输出 DTO |
| `ui/shared/src/commonMain/.../contract/models/*.kt` | 9 文件 | UI 数据模型 |
| `ui/shared/src/commonMain/.../mock/MockAiCore.kt` | ~256 | 非 JVM 占位实现 |
| `ui/shared/src/commonMain/.../ui/AppState.kt` | 136 | 全局状态持有者 |
| `ui/shared/src/commonMain/.../ui/WorkspaceViewModel.kt` | 177 | 会话视图模型 |
| `ui/shared/src/commonMain/.../ui/SidebarViewModel.kt` | 92 | 侧栏视图模型 |
| `ui/shared/src/jvmMain/.../bridge/MederiAiCore.kt` | ~230 | AiCore 真实实现 |
| `ui/shared/src/jvmMain/.../bridge/MederiEventAggregator.kt` | 250 | 事件流聚合 |
| `ui/shared/src/jvmMain/.../bridge/MederiModelMapper.kt` | 312 | core → UI 模型映射 |
| `ui/shared/src/jvmMain/.../bridge/MederiInputMapper.kt` | 108 | UI DTO → core 请求映射 |
| `ui/shared/src/jvmMain/.../contract/AiCoreProvider.jvm.kt` | ~5 | actual: MederiAiCore |
| `core/.../Mederi.kt` | 229 | core 入口 |
| `core/.../api/SessionApi.kt` | ~60 | 会话 API 接口 |
| `core/.../domain/model/MederiEvent.kt` | ~30 | 事件类型定义 |

**API 文档**：
- `doc/mederi文档.md`：core API 完整文档
- `doc/api.md`：使用方式快速参考
- `mederi/AGENTS.md`：架构设计与编码规范
- `doc/UI对接计划.md`：本文件，UI ↔ core 对接地图
- `doc/审批功能对接规格.md`：审批功能实现规格（已实施完毕）