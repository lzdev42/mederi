# Mederi UI 对接计划

> 状态：**已确认，进入实施**  
> 目标：在 `mederi/ui/shared` 模块与 `mederi/core` 模块之间建立清晰的桥接层，让 UI 层通过既有契约 `AiCore` 对接真实的 Mederi 能力。审批（Permission/Question）等高级功能本次**不做**，保持 UI 契约但返回空值。

---

## 1. 总体设计

### 1.1 分层边界

```
┌─────────────────────────────────────────┐
│  UI Layer (Compose KMP)                  │
│  ViewModel / AppState / Screens          │
│  只依赖 xyz.mederi.core.contract.*       │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  Bridge Layer（新增）                     │
│  MederiAiCore                            │
│  MederiModelMapper / MederiInputMapper   │
│  MederiEventAggregator                   │
│  负责：生命周期、DTO 转换、事件聚合、错误包装 │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  Core Layer                              │
│  xyz.mederi.Mederi                       │
│  Provider/Agent/Project/Session/ModelApi │
└─────────────────────────────────────────┘
```

### 1.2 核心原则

- **UI 不直接 import core**：ViewModel 只使用 `AiCore` 及 `contract` 包里的模型。
- **Bridge 是单向依赖**：bridge 可以依赖 core 和 contract，core 不感知 bridge。
- **缺失功能先 stub 或空值，不阻塞主链路**：权限、问题、Todo、Compaction 等本次不做的特性，在 bridge 中返回空结果；Diff 和 Subagent 已在 core 实现，bridge 直接转调。
- **生命周期集中**：`MederiAiCore` 负责 `Mederi` 实例的创建、初始化、全局事件订阅、状态刷新。

---

## 2. 命名与文件结构

### 2.1 命名确认

- 桥接实现类：**`MederiAiCore`**，实现 `xyz.mederi.core.contract.AiCore`。
- 映射器：
  - `MederiModelMapper`：core 领域模型 → UI contract 模型
  - `MederiInputMapper`：UI DTO → core DTO
  - `MederiEventAggregator`：把 `MederiEvent` + `Message` 历史聚合成 `ConversationSnapshot`
- 错误处理：`MederiErrorMapper`（可选，若简单可直接内联 `runCatching`）

### 2.2 新增文件

```
mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/bridge/
├── MederiAiCore.kt              # 实现 AiCore，生命周期 + API 调用
├── MederiModelMapper.kt         # core → contract 模型转换
├── MederiInputMapper.kt         # contract DTO → core DTO 转换
└── MederiEventAggregator.kt     # 事件流聚合为 ConversationSnapshot
```

### 2.3 修改文件

```
mederi/ui/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCoreProvider.kt
```

将 `default()` 从 `MockAiCore()` 改为返回 `MederiAiCore(...)`。

### 2.4 UI 模块结构调整（已完成）

`mederi/mederi/ui/` 不再是单一 library，而是采用 WorkMuleK 式的结构：

```
mederi/mederi/ui/
├── shared/           # KMP shared library（原 :ui 模块）
├── androidApp/       # Android application
├── desktopApp/       # Desktop application
├── iosApp/           # Xcode iOS application
└── webApp/           # Web application
```

- `:core`、`:server`、`:inkcompose` 是可发布的 library。
- `:ui:shared` 是 UI 共享库，bridge 层位于其中。
- 各 platform app 只负责入口和平台 actual。

---

## 3. UI 契约调整

### 3.1 Agent 概念对齐

UI 当前有 5 个 Agent（`build / explore / plan / task / general`），但 core 只有 3 个内置 `AgentMode`：`EXPLORE / PLAN / EXECUTE`。

**方案**：UI 缩减为 3 个 Agent，与 core 一一对应：

| UI Agent（新） | core AgentMode | 用途 |
|---|---|---|
| Explore | `EXPLORE` | 只读探索 |
| Plan | `PLAN` | 只读 + 规划 |
| Execute | `EXECUTE` | 读写执行 |

UI `AgentOption.id` 应理解为 **agent mode/name**，不再是一个独立生成的 ID。映射规则：

- `AgentOption.id == core.AgentMode.name`（"EXPLORE" / "PLAN" / "EXECUTE"）
- `AgentOption.name` 用于展示（"Explore" / "Plan" / "Execute"）
- `AgentOption.mode`（Primary/Subagent/Hidden）可保留用于 UI 展示控制，但 core 不感知

### 3.2 Conversation 模型精简

UI `Conversation` 当前包含 `parentConversationId` 和 `directory`，这两个字段与 core 当前能力不匹配：

| 字段 | 处理方式 | 理由 |
|---|---|---|
| `parentConversationId` | **暂不实现**，后续作为 core 子会话能力的一部分补充 | core 当前无父子 Session，子 agent 对话需要单独设计 |
| `directory` | **删除** | 不需要适配 OpenCode 的目录分组逻辑；Mederi 天然以 Project 为父容器 |

精简后的 `Conversation` 保留：

```kotlin
data class Conversation(
    val id: String,                    // 对应 core Session.id
    val projectId: String?,            // 对应 core Session.projectId
    val title: String,                 // 对应 core Session.title
    val status: ConversationStatus,    // 对应 core SessionStatus
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,              // 对应 Session.aiModel.id
    val modelProvider: String?,        // 对应 Session.aiModel 所属 providerId（需桥接层维护映射）
    val thinkingLevel: String?,        // 对应 Session.aiModel.reasoningLevel / Session.reasoningLevel
    val agent: String?,                // 对应 Session.mode.name
)
```

---

## 4. MederiAiCore 设计

### 4.1 构造与生命周期

```kotlin
class MederiAiCore(
    private val configDir: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AiCore {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var mederi: xyz.mederi.Mederi

    override val isReady = MutableStateFlow(false)
    override val projects = MutableStateFlow<List<Project>>(emptyList())
    override val providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    override val availableModels = MutableStateFlow<List<ModelOption>>(emptyList())
    override val availableAgents = MutableStateFlow<List<AgentOption>>(emptyList())

    override suspend fun initialize(): Result<Unit> = runCatching {
        mederi = Mederi.local(configDir)
        refreshGlobalState()
        subscribeGlobalEvents()
        isReady.value = true
    }
}
```

### 4.2 configDir 配置策略

`configDir` **不是写死的**，应由调用方传入：

- **桌面端（desktopApp）**：通过 `expect/actual` 提供默认目录，如 `~/.mederi`。
- **Android / iOS / wasmJs**：各平台通过 actual 提供自己的目录。
- **测试**：传入临时目录，避免污染用户数据。

`AiCoreProvider.default()` 在 commonMain 中不直接写死路径，而是委托给 `ui:shared` 各 target 的 actual：

```kotlin
// ui/shared/src/commonMain/kotlin/xyz/mederi/core/contract/AiCoreProvider.kt
expect object AiCoreProviderPlatform {
    fun defaultConfigDir(): String?
}

object AiCoreProvider {
    fun default(): AiCore {
        val configDir = AiCoreProviderPlatform.defaultConfigDir()
        return if (configDir != null) {
            MederiAiCore(configDir = configDir)
        } else {
            MockAiCore() // wasm/android/ios 暂 fallback
        }
    }
}
```

```kotlin
// ui/shared/src/jvmMain/kotlin/xyz/mederi/core/contract/AiCoreProviderPlatform.jvm.kt
actual object AiCoreProviderPlatform {
    actual fun defaultConfigDir(): String? = "~/.mederi"
}
```

- **jvmMain（desktopApp）**：默认 `~/.mederi`，Mederi.local 持久化。
- **androidMain / iosMain / wasmJsMain**：暂返回 `null`，继续使用 `MockAiCore`。后续各平台实现 actual 后再切到真实 core。
- **测试**：传入临时目录，避免污染用户数据。

---

## 5. 核心映射关系

### 5.1 Project

| UI | core | 转换 |
|---|---|---|
| `CreateProjectInput(name, directory)` | `CreateProjectRequest(name, listOf(directory))` | 单目录包装为列表 |
| `Project.id` | `Project.id` | 直接 |
| `Project.name` | `Project.name` | 直接 |
| `Project.directories` | `Project.directories` | 直接 |
| `Project.conversations` | `sessions.list()` 后按 `projectId` 过滤 | 桥接层查询并映射 |

### 5.2 Provider / Model

| UI | core | 转换 |
|---|---|---|
| `ProviderType.Builtin/Custom` | `ProviderType.OPENAI_CHAT/OPENAI_RESPONSES/GOOGLE` | 需要映射表，Builtin 对应常见供应商 |
| `ProviderConfig.isConnected` | `provider.defaultApiKey != null` | 推断 |
| `ProviderConfig.supportsApiKey` | 写死 `true` | 当前所有 core provider 都需要 key |
| `ProviderConfig.supportsBaseUrl` | 写死 `true` | 当前所有 core provider 都支持 baseUrl |
| `ProviderConfig.customModels` | `Provider.models` 中用户自定义部分 | 暂时统一视为 models |
| `ModelOption.provider` | 通过桥接层维护 `modelId → providerId` 映射 | 创建 provider 时缓存 |
| `ModelOption.thinkingLevels` | `ReasoningParameter.levels.keys` 或默认 `NONE/LOW/MEDIUM/HIGH` | 需过滤供应商不支持的级别 |

### 5.3 Agent

| UI | core | 转换 |
|---|---|---|
| `AgentOption.id` | `AgentMode.name` | "EXPLORE"/"PLAN"/"EXECUTE" |
| `AgentOption.name` | `AgentPreset.name` | 展示用 |
| `AgentOption.description` | `AgentPreset.description` | 直接 |
| `AgentOption.mode` | UI 自行决定（Primary/Subagent/Hidden） | core 不感知 |

### 5.4 Conversation / Session

| UI | core | 转换 |
|---|---|---|
| `Conversation` | `Session` | 字段一一映射 |
| `ConversationStatus.Idle/Working/Error` | `SessionStatus.IDLE/RUNNING/ERROR` | 直接 |
| `Conversation.modelId` | `Session.aiModel.id` | 直接 |
| `Conversation.modelProvider` | 桥接层查缓存 | 需要维护 |
| `Conversation.thinkingLevel` | `Session.reasoningLevel?.name` 或 `Session.aiModel.reasoningLevel.name` | 直接 |
| `Conversation.agent` | `Session.mode.name` | 直接 |

### 5.5 Message

| UI ChatBlock | core MessagePart | 转换说明 |
|---|---|---|
| `Text` | `MessagePart.Text` | 直接 |
| `Reasoning(text)` | `MessagePart.Reasoning(content = listOf(text))` | 字符串包装为列表 |
| `ToolCall(id, name, state)` | `MessagePart.ToolCall` + 后续 `MessagePart.ToolResult` | 桥接层需把调用和结果合并为带状态的 `ChatBlock.ToolCall` |
| `File(name, url, mimeType)` | `MessagePart.File(path)` / `MessagePart.Image(url, mimeType)` | UI File 用 url/path；`FileAttachment(ByteArray)` 暂不处理 |
| `Diff` | **core 无对应类型** | 暂不实现，渲染为 `Unknown` 或文本 |
| `Unknown` | 兜底 | 容错 |

### 5.6 发送消息

| UI | core |
|---|---|
| `ChatPromptInput(text, model, agent, thinkingLevel, attachments)` | `SendMessageRequest(AgentConfig(mode, aiModel, reasoningLevel), parts)` |

- `agent` 的 `id` 映射为 `AgentMode.valueOf(id)`。
- `model` 的 `id` 需要查 core `models.get(id)` 得到 `AIModel`。
- `thinkingLevel` 字符串映射为 `ReasoningLevel.valueOf(level.uppercase())`。
- `attachments`（ByteArray）**暂不处理**，先传空列表。

---

## 6. ConversationSnapshot 聚合

`observeConversation(id): Flow<ConversationSnapshot>` 是桥接层最复杂的部分：

1. **初始快照**：
   - 调用 `mederi.sessions.get(id)` 得到 `Session`。
   - 调用 `mederi.sessions.listMessages(id)` 得到历史 `Message` 列表。
   - 合并为初始 `ConversationSnapshot`。

2. **事件订阅**：
   - 订阅 `mederi.sessions.events(id)`。

3. **事件处理**：

| core 事件 | UI 处理 |
|---|---|
| `SESSION_CREATED` | 通常创建时已订阅，可忽略 |
| `SESSION_UPDATED` | 更新 `Conversation.status` |
| `MESSAGE_DELTA(type=text/reasoning)` | 追加到当前 assistant 消息的对应 `ChatBlock` |
| `MESSAGE_COMPLETED` | 标记当前 assistant 消息完成；可选重新拉取 `listMessages` 对齐 |
| `MESSAGE_ERROR` | 设置 `ConversationSnapshot.errorMessage` |

4. **状态机**：
   - 收到第一个 `MESSAGE_DELTA` 时，在 snapshot 中创建一条 `isStreaming = true` 的 Assistant 占位消息。
   - `MESSAGE_COMPLETED` 时把占位消息 `isStreaming` 置 false，并回填 `id`。
   - 每次变化 emit 新的 immutable `ConversationSnapshot`。

---

## 7. 缺失 / 待实现 API 清单

以下功能分两类：
- **本次 bridge 必须补齐**：Token 拆分、`getFileDiffs()` 对接。
- **本次不做，bridge 返回空值**：Permission、Question、Todo、Compaction、CostSummary、文件附件、自动拉取模型。

| UI 功能 | core 现状 | 本次处理 | 后续方向 |
|---|---|---|---|
| `PermissionRequest` / 权限审批 | 未实现 | **不做**。始终返回空列表；`replyPermission` 返回失败 | 见 Codex CLI 审批机制设计，在 core SessionManager 增加 Approval/Policy |
| `QuestionRequest` / 交互式问题 | 未实现 | **不做**。始终返回 null；`replyQuestion` 返回失败 | core 增加交互式问题协议 |
| `TodoItem` / 任务列表 | 未实现 | **不做**。始终返回空列表 | 可由 `update_plan` 事件扩展，或新增 Todo 存储 |
| `CompactionConfig` / 对话压缩 | 未实现 | **不做**。`requestCompaction` 空实现 | core 已支持 `new_context` 工具，可包装为压缩 API |
| `getFileDiffs()` | ✅ 已实现 `SessionApi.getFileDiffs()` | **bridge 直接转调 core** | 已就绪 |
| `ChatBlock.Diff` | core diff 不嵌入 MessagePart | 本次不渲染为独立 block；UI 通过 `getFileDiffs()` 展示 diff 面板 | 后续可把 diff 作为 message metadata 或独立 block |
| 子会话 / `parentConversationId` | ✅ `spawn_agent` 单 turn subagent 已实现，不持久化 | 不暴露该字段 | 见第 9 节 Subagent 方案 |
| Token 拆分（input/output/cacheRead） | core 只保存 `totalTokens` | **bridge 前先在 core 补字段**：`Message.inputTokens/outputTokens/cachedTokens` | 后续补充 reasoning/cacheWrite |
| 费用 `CostSummary` | core 不计算费用 | 返回 0 USD | 由 UI 或 bridge 根据 token 用量 + 模型单价计算 |
| 文件附件 `FileAttachment(ByteArray)` | core 只接受 URL/Path | 发送时忽略附件 | 需要平台层把 ByteArray 写入临时文件 |
| `refreshProviderModels()` 自动拉取 | `fetchModels` 未实现 | 返回当前已配置模型 | 需要 core 实现远端模型列表拉取 |

---

## 8. Diff 实现方案（core 已完成）

Diff 功能单独成文，详见《[diff实现计划.md](./diff实现计划.md)》。

core 已实现：
- `TurnDiffTracker`：Codex 风格，记录每个 turn 的文件变更；
- `DiffStore` + SQLDelight `diffs` 表：持久化；
- `FileSystemTools` / `ToolFactory` / `TurnExecutor`：工具自动上报 + `execute_command` 目录快照兜底；
- `SessionApi.getFileDiffs(sessionId, messageId?)`：API 暴露。

Bridge 工作：
- `MederiAiCore.getFileDiffs()` 直接转调 core API；
- UI diff 面板通过该 API 展示，不依赖 `ChatBlock.Diff`。

---

## 9. Subagent 方案（core 已完成）

### 9.1 需求澄清

- 子 session 不持久化到数据库；subagent 任务结束后销毁。
- 主 agent 无限等待 subagent 结果。
- subagent 的超时、报错、成功、失败，**必须由 subagent 自己主动汇报**给主 agent。

### 9.2 core 已实现的方案

1. **`spawn_agent` 工具**：
   - 输入：子 agent 的 `mode`（EXPLORE/PLAN/EXECUTE）、任务描述、上下文。
   - 行为：
     - 创建一个内存中的子 Session（不写入 SessionStore）。
     - 使用 `TurnExecutor` 的独立逻辑运行子 agent **单 turn**。
     - 子 agent 运行结束后，把结果（成功/失败/超时）作为 `ToolResult` 返回给父 agent。
   - 所有结果、异常都通过 `ToolResult.output` 或 `ToolResult.isError = true` 主动汇报。

2. **错误处理原则**：
   - 子 agent 内部 catch 所有异常，不允许抛到父 agent。
   - 子 agent 任务本身不设超时；网络请求超时由底层 Koog LLM client 抛出，我们 catch 后返回 `isError = true` 的 `ToolResult`。
   - 父 agent 调用 `spawn_agent` 后阻塞等待，收到结果后继续。

### 9.3 UI 侧处理

- 子 session 不在 `Project.conversations` 中显示。
- UI `Conversation.parentConversationId` 和 `childConversations` 暂时不暴露。
- Bridge 无需特殊处理，`spawn_agent` 对 UI 透明，只表现为一次 tool call。

---

## 10. Token / 计费方案

### 10.1 core 当前能提供的用量信息

- `Message.totalTokens: Int?`：LLM 返回的总 token 数。
- `TurnExecutor` 通过 `KoogMessageMapper.fromKoogMessage()` 把 Koog `ResponseMetaInfo.totalTokensCount` 写入 `Message.totalTokens`。

### 10.2 Koog 的 token 拆分能力

Koog `ResponseMetaInfo` 字段：
- `totalTokensCount: Int?`
- `inputTokensCount: Int?`
- `outputTokensCount: Int?`
- `modelId: String?`
- `metadata: JsonObject?`

Koog OpenAI client 内部模型 `OpenAIUsage` 包含：
- `promptTokens`
- `completionTokens`
- `totalTokens`
- `completionTokensDetails`
- `promptTokensDetails`（含 `cachedTokens`）

**结论**：
- Koog 原生支持 **input / output / total** 和 **prompt cache read（cachedTokens）**。
- **没有专门的 reasoningTokens / cacheWriteTokens 字段**。OpenAI 的 `reasoning_tokens` 如果服务端返回，可能埋在 `completionTokensDetails` 或 `metadata` 中，但 Koog 未明确暴露。
- mederi core 目前只保存了 `totalTokens`，input/output 在 `KoogMessageMapper` 中**被丢弃**了。

### 10.3 本次 bridge 必须做的改动

1. **core 侧扩展 `Message`**：
   - 增加 `inputTokens: Int?`、`outputTokens: Int?`、`cachedTokens: Int?`。
   - 修改 `KoogMessageMapper.fromKoogMessage()` 读取 `ResponseMetaInfo` 的对应字段。
   - 对不支持的字段（reasoning / cacheWrite）先留 null。

2. **UI 计费由 UI 层实现**：
   - bridge 把 core 的 token 字段映射到 UI `TokenUsage`。
   - UI 或 bridge 根据 `modelId` + 各供应商价格表计算 `CostSummary`。
   - 价格表可放在 UI 的 Preferences 或一个轻量级配置文件里。

### 10.4 本次 bridge 处理

- `TokenUsage` 填充：
  - `input` ← `Message.inputTokens`
  - `output` ← `Message.outputTokens`
  - `reasoning` ← 暂 0（core 未支持）
  - `cacheRead` ← `Message.cachedTokens`
  - `cacheWrite` ← 暂 0
- `CostSummary` 返回 `0.0 USD`，由 UI 后续按模型单价自行计算。

---

## 11. 实施步骤

### 第一阶段：core 小补（必须先做）

1. **扩展 `Message` token 字段**：
   - 在 `core/src/main/kotlin/xyz/mederi/domain/model/Message.kt` 增加 `inputTokens/outputTokens/cachedTokens`。
   - 修改 `KoogMessageMapper.fromKoogMessage()` 从 `ResponseMetaInfo` 读取对应字段。

### 第二阶段：bridge 层实现

2. **新增 bridge 包与映射器**（`ui/shared/src/commonMain/.../core/bridge/`）：
   - `MederiModelMapper`
   - `MederiInputMapper`
   - `MederiEventAggregator`

3. **实现 `MederiAiCore`**：
   - 生命周期（`initialize`、`isReady`）
   - 全局 StateFlow 刷新（projects / providers / models / agents）
   - Project / Conversation CRUD
   - Provider 配置（create / update，拉取模型暂 stub）
   - `sendMessage` / `abort`
   - `observeConversation` 事件聚合
   - 缺失功能返回空值（permission / question / todo / compaction）

4. **平台配置**：
   - 在 `ui:shared` 增加 `AiCoreProviderPlatform` expect/actual。
   - `jvmMain` 默认返回 `"~/.mederi"`。
   - 其他平台先 fallback 到 `MockAiCore`。

5. **修改 `AiCoreProvider.default()`**：
   - jvm 返回 `MederiAiCore(...)`。
   - 其他平台继续返回 `MockAiCore(...)`。

### 真实 Provider 测试配置

详见《[provider_test_config.md](./provider_test_config.md)》，包含 Agnes OpenAI 兼容端点的模型列表、API Key、curl 示例及 Mederi CLI 配置方式。

### 第三阶段：验证

6. **测试**：
   - 新增 `MederiAiCoreFunctionalTest`（jvmTest），使用临时目录验证：
     - 创建 project → 创建 session → 发送消息 → 事件聚合 → 历史消息可读取。
   - 验证 diff 集成测试仍通过。
   - 确保 `MigratedUiFunctionalTest` 不崩溃（可能需要调整）。

7. **文档更新**：
   - 按实际实现更新 `mederi/AGENTS.md` 和 `doc/api.md` 中关于 UI 集成、diff、subagent 的部分。

### 已完成的步骤

- ✅ UI 模块结构调整：`ui/shared` + `androidApp/desktopApp/iosApp/webApp`
- ✅ Diff 实现：`TurnDiffTracker`、`DiffStore`、`SessionApi.getFileDiffs()`
- ✅ Subagent 实现：`spawn_agent` 单 turn 阻塞子 agent

---

## 12. 已确认事项

1. ✅ UI 模块结构：`ui/shared` + `androidApp/desktopApp/iosApp/webApp`。
2. ✅ `MederiAiCore` 命名与文件结构（位于 `ui/shared`）。
3. ✅ UI Agent 缩减为 3 个（Explore / Plan / Execute）。
4. ✅ `Conversation.parentConversationId` 和 `directory` 删除。
5. ✅ Diff 采用 Codex 的 `TurnDiffTracker` 方案，已在 core 实现。
6. ✅ Subagent 为单 turn、阻塞等待、网络超时由底层 Koog client 负责，已在 core 实现。
7. ✅ Token 拆分：input/output/cacheRead 由 Koog 提供，reasoning/cacheWrite 暂 0；bridge 前需在 core `Message` 补字段。
8. ✅ 平台 configDir：jvm 默认 `~/.mederi`，wasm/android/ios 用 expect/actual + Mock 兜底。
9. ✅ 审批（Permission/Question）本次不做，bridge 返回空值。
