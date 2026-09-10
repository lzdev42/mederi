# mederi文档

> 本文档描述 `mederi-core` 模块的公共 Kotlin API，面向直接使用 core 构建应用（Compose UI、桌面应用、后端服务）的开发者。
>
> 本文档基于源码与自动化测试（`core/src/test/kotlin/xyz/mederi/api/MederiApiSmokeTest.kt`，55 个测试）交叉校验，所有行为描述与返回结构均与实测一致。
>
> `mederi-server` 模块将来会把这些 API 映射为 REST endpoint 和 SSE 流，但 core 本身不依赖任何 HTTP 框架。

---

## 1. 概述

`Mederi` 是 core 的唯一入口。创建实例后，通过 `mederi.providers`、`mederi.agents`、`mederi.projects`、`mederi.sessions`、`mederi.models` 五个子 API 操作。

```kotlin
import xyz.mederi.Mederi

val mederi = Mederi.local(config = "~/.mederi")
```

### 1.1 创建方式

| 方式 | 方法 | 适用场景 |
|---|---|---|
| 本地模式 | `Mederi.local(config: String)` | 桌面/本地应用，自动创建 `config/` 与 `data/` |
| 后端模式 | `Mederi.backend(...)` | 后端服务，显式注入所有 Store |
| 自定义模式 | `Mederi.create { }` | 高级配置，可混合注入 |

### 1.2 Store 装配优先级

显式注入 > `configDir` 默认 > 纯内存

| Store | configDir 默认 | 纯内存 |
|---|---|---|
| ProviderStore | JsonProviderStore | InMemoryProviderStore |
| AgentStore | JsonAgentStore | InMemoryAgentStore |
| ProjectStore | JsonProjectStore | InMemoryProjectStore |
| SessionStore | SqliteSessionStore | InMemorySessionStore |
| HistoryStore | SqliteHistoryStore | InMemoryHistoryStore |
| ApiKeyStore | SqliteApiKeyStore | InMemoryApiKeyStore |

### 1.3 ID 前缀约定

所有资源 ID 由系统生成，带固定前缀，调用方无需自行生成：

| 资源 | ID 前缀 | 示例 |
|---|---|---|
| Provider | `prov_` | `prov_3f8a2b1c` |
| API Key | `key_` | `key_7d9e0f1a` |
| Model | `mdl_` | `mdl_9c4a5b2d` |
| Agent 预设 | `AgentMode` 枚举 | `EXPLORE` / `PLAN` / `EXECUTE` |
| Project | `proj_` | `proj_5e1f2a3b` |
| Session | `sess_` | `sess_8a3b4c5d` |
| Message | `msg_` | `msg_1c2d3e4f` |

---

## 2. 异常处理约定

Mederi core 的所有公共 API（`events()` 除外，它返回 Flow 不抛异常）都把底层异常转换为 `MederiException` 子类。调用方只需捕获统一异常，不需要关心 Kotlin 标准库异常的具体类型。

```kotlin
import xyz.mederi.api.exception.*

try {
    mederi.sessions.sendMessage(sessionId, request)
} catch (e: MederiNotFoundException) {
    // session / provider / model / agent / project 不存在
} catch (e: MederiValidationException) {
    // 参数非法，如 provider type 错误、目录为空、子 Agent 不能直接创建 Session
} catch (e: MederiStateException) {
    // 状态非法，如 session 正在运行
} catch (e: MederiInternalException) {
    // 内部未预期错误（应极少出现）
}
```

### 2.1 异常类型

| 异常 | 来源 | 典型触发场景 |
|---|---|---|
| `MederiNotFoundException` | `NoSuchElementException` 转换，资源不存在 | `get()` / `update()` / `delete()` 一个不存在的 id；`sendMessage` 时模型/Provider 不存在；Session 未选择模型 |
| `MederiValidationException` | `IllegalArgumentException` 转换，参数或业务规则非法 | 非法 `ProviderType`；Project 目录为空；重复添加目录；删除最后一个目录；用 `EXPLORE` 创建 Session |
| `MederiStateException` | `IllegalStateException` 转换，当前状态不允许操作 | 同一 Session 正在运行时再次 `sendMessage` |
| `MederiInternalException` | 其他未预期异常转换 | 极少出现 |

### 2.2 不兜底原则

对任何配置错误都不做静默纠正或默认值。Mederi 的职责是把错误信息清晰反馈出来，而不是掩盖错误。例如：

- 非法 `ProviderType` 会提示 `"Invalid provider type 'xxx'. Expected one of: OPENAI_CHAT, OPENAI_RESPONSES, GOOGLE"`
- 创建 Project 时目录为空会提示 `"Project must have at least one directory"`
- `sendMessage` 时未选择模型会提示 `"No AI model selected for session xxx"`

---

## 3. Provider API

路径：`mederi.providers`

```kotlin
interface ProviderApi {
    suspend fun list(): List<Provider>
    suspend fun create(request: CreateProviderRequest): Provider
    suspend fun get(id: String): Provider
    suspend fun update(id: String, request: UpdateProviderRequest): Provider
    suspend fun delete(id: String)

    suspend fun addKey(providerId: String, request: CreateApiKeyRequest): ProviderApiKey
    suspend fun listKeys(providerId: String): List<ProviderApiKey>
    suspend fun deleteKey(providerId: String, keyId: String)
    suspend fun setDefaultKey(providerId: String, keyId: String)

    suspend fun listModels(providerId: String): List<AIModel>
    suspend fun addModel(providerId: String, request: CreateModelRequest): AIModel
    suspend fun updateModel(providerId: String, modelId: String, request: UpdateModelRequest): AIModel
    suspend fun deleteModel(providerId: String, modelId: String)
}
```

### 3.1 Provider 返回结构

`Provider`（`xyz.mederi.provider.domain.model.Provider`）：

```kotlin
data class Provider(
    val id: String,                                // prov_xxx
    val name: String,
    val type: ProviderType,                        // OPENAI_CHAT / OPENAI_RESPONSES / GOOGLE
    val baseUrl: String,                           // 完整端点，如 https://api.openai.com/v1
    val reasoningParameter: ReasoningParameter?,   // 推理参数配置，null 表示不支持 reasoning
    val models: List<AIModel>,                     // 该供应商下的模型列表（见 3.5）
    val responseSanitization: Boolean,             // 是否需要响应清洗（vLLM 兼容端点）
    @Transient val apiKeys: List<ProviderApiKey>   // API 密钥列表（不序列化，读时合并）
) {
    val defaultApiKey: ProviderApiKey?      // 默认密钥，无默认则取第一个
    val supportsReasoning: Boolean          // reasoningParameter != null && 任一 model 支持 reasoning
    fun getModel(modelId: String): AIModel?
    fun getModelByProviderId(providerModelId: String): AIModel?
}
```

> **重要**：`apiKeys` 是 `@Transient` 字段，不持久化到 JSON，运行时由 `ProviderManager` 从 ApiKeyStore 读时合并。

`ProviderApiKey`（`xyz.mederi.provider.domain.model.ProviderApiKey`）：

```kotlin
data class ProviderApiKey(
    val id: String,            // key_xxx
    val name: String,          // 如 "Primary"、 "Backup"
    val maskedValue: String,   // 脱敏值，如 "sk-...7890"；长度 <=7 时为 "***"
    val isDefault: Boolean
)
```

> 密钥值在返回时**永远脱敏**（保留前 3 后 4 字符，中间用 `...` 替代）。明文只在创建时传入，无法从任何 API 读回。

`ProviderType` 枚举：

| 枚举值 | 对应 API | thinking 参数 |
|---|---|---|
| `OPENAI_CHAT` | OpenAI Chat Completions（兼容 DeepSeek、OpenRouter 等） | `reasoning_effort` |
| `OPENAI_RESPONSES` | OpenAI Responses API（实验性） | `reasoning` |
| `GOOGLE` | Google Gemini | `thinkingConfig` |

### 3.2 请求 DTO

```kotlin
// 创建供应商
data class CreateProviderRequest(
    val name: String,                                   // 必填
    val type: ProviderTypeRequest,                      // 必填，字符串，如 "OPENAI_CHAT"，非法抛 MederiValidationException
    val baseUrl: String,                                // 必填，完整端点
    val apiKeys: List<CreateApiKeyRequest> = emptyList(),   // 可同时创建密钥
    val models: List<CreateModelRequest> = emptyList(),     // 可同时创建模型
    val reasoningParameter: ReasoningParameterRequest? = null,  // null 时按 type 自动生成默认配置
    val responseSanitization: Boolean = false,          // true 使用 MederiOpenAILLMClient 清洗响应
    val fetchModels: Boolean = true                     // 是否自动拉取供应商模型列表（当前实现未使用）
)

// 更新供应商：只更新非 null 字段
data class UpdateProviderRequest(
    val name: String? = null,
    val baseUrl: String? = null,
    val reasoningParameter: ReasoningParameterRequest? = null
)

// 创建 API Key
data class CreateApiKeyRequest(
    val name: String,
    val value: String,          // 明文密钥，仅创建时传入
    val isDefault: Boolean = false
)

// 创建 Model
data class CreateModelRequest(
    val providerModelId: String,                    // 供应商侧模型标识符，如 "gpt-4o"
    val name: String,                               // 显示名称，如 "GPT-4o"
    val supportsReasoning: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.NONE
)

// 更新 Model：只更新非 null 字段
data class UpdateModelRequest(
    val name: String? = null,
    val supportsReasoning: Boolean? = null,
    val reasoningLevel: ReasoningLevel? = null
)

typealias ProviderTypeRequest = String
typealias ReasoningParameterRequest = ReasoningParameter
```

### 3.3 创建供应商

```kotlin
val provider = mederi.providers.create(
    CreateProviderRequest(
        name = "OpenAI Production",
        type = "OPENAI_CHAT",                    // 非法会抛 MederiValidationException
        baseUrl = "https://api.openai.com/v1",
        responseSanitization = false,             // true 使用 MederiOpenAILLMClient 清洗响应
        apiKeys = listOf(
            CreateApiKeyRequest(
                name = "Primary",
                value = "sk-...",
                isDefault = true
            )
        ),
        models = listOf(
            CreateModelRequest(
                providerModelId = "gpt-4o",
                name = "GPT-4o",
                supportsReasoning = false
            )
        )
    )
)
```

> **重要**：`create()` 返回的 `Provider` 对象中 `apiKeys` 和 `models` **都是空的**。传入的 `apiKeys` 与 `models` 是在创建后逐个 `addKey` / `addModel` 写入 Store 的，返回的 Provider 对象不会刷新。
>
> 需要查询实际写入的 Key 和 Model，**必须**分别调用：
> ```kotlin
> val keys = mederi.providers.listKeys(provider.id)       // 创建时传入的 keys
> val models = mederi.providers.listModels(provider.id)   // 创建时传入的 models
> ```
>
> 这一点与 `get()` / `list()` 不同：`get()` / `list()` 返回的 Provider 会自动合并 keys（读时合并），且 models 从 Store 读取（已包含 addModel 写入的结果）。如果创建后紧接着 `get(provider.id)`，则返回的对象里 `apiKeys` 和 `models` 都是完整的。

### 3.4 API Key 管理

```kotlin
// 添加 Key
val key = mederi.providers.addKey(
    providerId = provider.id,
    CreateApiKeyRequest(name = "Backup", value = "sk-...", isDefault = false)
)
// 返回 ProviderApiKey（id 以 key_ 开头，maskedValue 已脱敏）

// 列出该供应商所有 Key
val keys = mederi.providers.listKeys(provider.id)
// 返回 List<ProviderApiKey>，全部脱敏

// 设为默认（该供应商其他 Key 的 isDefault 自动置 false）
mederi.providers.setDefaultKey(provider.id, key.id)

// 删除 Key
mederi.providers.deleteKey(provider.id, key.id)
```

**关联**：
- `addKey` / `listKeys` / `deleteKey` / `setDefaultKey` 都要求 `providerId` 指向存在的 Provider，否则抛 `MederiNotFoundException`。
- 删除 Provider（`delete`）会**级联删除**其下所有 Key。
- `Provider.get(id)` / `Provider.list()` 返回的 `Provider.apiKeys` 就是 `listKeys(id)` 的结果（读时合并）。

### 3.5 Model 管理

```kotlin
val model = mederi.providers.addModel(
    providerId = provider.id,
    CreateModelRequest(
        providerModelId = "o3-mini",
        name = "o3-mini",
        supportsReasoning = true,
        reasoningLevel = ReasoningLevel.MEDIUM
    )
)
// 返回 AIModel（id 以 mdl_ 开头）

// 列出该供应商所有 Model
val models = mederi.providers.listModels(provider.id)

// 更新 Model：只更新非 null 字段
val updated = mederi.providers.updateModel(
    provider.id, model.id,
    UpdateModelRequest(name = "o3-mini-new", reasoningLevel = ReasoningLevel.HIGH)
)

// 删除 Model
mederi.providers.deleteModel(provider.id, model.id)
```

**关联**：
- Model 属于某个 Provider（内嵌在 Provider 的 `models` 列表中）。
- `mederi.models.list()` / `mederi.models.get()` 是**跨供应商**聚合查询，等价于遍历所有 Provider 的 `models`（见第 7 节）。
- 创建 Agent 时必须传入一个已存在的 `AIModel`（通过 `mederi.models.get(id)` 或 `listModels` 拿到），否则创建 Agent 时抛 `MederiNotFoundException`。

`AIModel` 返回结构（`xyz.mederi.domain.model.AIModel`）：

```kotlin
data class AIModel(
    val id: String,                 // mdl_xxx，系统生成，跨供应商唯一
    val providerModelId: String,    // 供应商侧标识符，如 "gpt-4o"
    val name: String,               // 显示名称
    val supportsReasoning: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.NONE
)
```

`ReasoningLevel` 枚举：`NONE, LOW, MEDIUM, HIGH`。注意 GOOGLE 供应商不支持 `MEDIUM`（见 `ReasoningParameter` 默认配置）。

### 3.6 验证与异常

| 调用 | 异常 | 说明 |
|---|---|---|
| `create(type = "XXX")` | `MederiValidationException` | type 非法，错误信息列出合法值 |
| `get("prov_不存在")` | `MederiNotFoundException` | 找不到 Provider |
| `update` / `delete` / `addKey` / `listKeys` / `deleteKey` / `setDefaultKey` / `listModels` / `addModel` / `updateModel` / `deleteModel`（providerId 不存在） | `MederiNotFoundException` | 找不到 Provider |

---

## 4. Project API

路径：`mederi.projects`

```kotlin
interface ProjectApi {
    suspend fun list(): List<Project>
    suspend fun create(request: CreateProjectRequest): Project
    suspend fun get(id: String): Project
    suspend fun delete(id: String)
    suspend fun rename(id: String, request: RenameProjectRequest): Project
    suspend fun addDirectory(id: String, request: AddProjectDirectoryRequest): Project
    suspend fun removeDirectory(id: String, request: RemoveProjectDirectoryRequest): Project
}
```

### 4.1 Project 返回结构

`Project`（`xyz.mederi.domain.model.Project`）：

```kotlin
data class Project(
    val id: String,              // proj_xxx
    val name: String,
    val directories: List<String>,   // 绝对路径列表，至少一个
    val createdAt: String,       // ISO 8601 时间戳
    val updatedAt: String        // ISO 8601 时间戳，rename/addDirectory/removeDirectory 时刷新
)
```

### 4.2 请求 DTO

```kotlin
data class CreateProjectRequest(
    val name: String,
    val directories: List<String>   // 必填，至少一个
)

data class RenameProjectRequest(val name: String)
data class AddProjectDirectoryRequest(val path: String)
data class RemoveProjectDirectoryRequest(val path: String)
```

### 4.3 项目规则

- 一个项目必须至少有一个目录——不存在没有目录的项目
- 所有目录地位平等
- 删除项目会级联删除其下所有 Session 和对话历史
- 目录不能重复
- 删除目录时至少保留一个

### 4.4 示例

```kotlin
val project = mederi.projects.create(
    CreateProjectRequest(
        name = "My Project",
        directories = listOf("/path/to/project")
    )
)
// 返回 Project，id 以 proj_ 开头

mederi.projects.addDirectory(
    project.id,
    AddProjectDirectoryRequest(path = "/another/path")
)
// 返回更新后的 Project，directories 含新旧路径

mederi.projects.removeDirectory(
    project.id,
    RemoveProjectDirectoryRequest(path = "/another/path")
)

mederi.projects.rename(project.id, RenameProjectRequest(name = "Renamed"))

mederi.projects.delete(project.id)
```

### 4.5 验证与异常

| 调用 | 异常 | 说明 |
|---|---|---|
| `create(directories = emptyList())` | `MederiValidationException` | 目录为空 |
| `addDirectory` 重复路径 | `MederiValidationException` | 目录已存在 |
| `removeDirectory` 最后一个目录 | `MederiValidationException` | 必须至少保留一个 |
| `removeDirectory` 不存在的路径 | `MederiValidationException` | 目录不存在 |
| `get` / `rename` / `addDirectory` / `removeDirectory` / `delete`（id 不存在） | `MederiNotFoundException` | 找不到 Project |

**关联**：
- Session 必须绑定一个 Project（`Session.projectId`），创建 Session 时校验 Project 存在。
- 删除 Project 会级联删除其下所有 Session 和对应的对话历史（见第 6 节）。

---

## 5. Agent API

路径：`mederi.agents`

```kotlin
interface AgentApi {
    suspend fun list(): List<AgentPreset>
    suspend fun get(mode: AgentMode): AgentPreset
    suspend fun update(mode: AgentMode, aiModel: AIModel?, reasoningLevel: ReasoningLevel?, systemPrompt: String? = null, tools: List<String>? = null): AgentPreset
}
```

Agent 不是实体，而是一组**预设配置**。Mederi 只内置三种 Agent（Explore / Plan / Execute），用户不能创建自定义 Agent，但可以为每种 Agent 设置默认模型、推理等级、自定义系统提示词和工具列表。

### 5.1 Agent 预设返回结构

`AgentPreset`（`xyz.mederi.domain.model.AgentPreset`）：

```kotlin
data class AgentPreset(
    val mode: AgentMode,                       // EXPLORE / PLAN / EXECUTE，同时作为 key
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",             // 由 mode 决定，用户可覆盖
    val tools: List<String> = emptyList(),     // 由 mode 决定，用户可覆盖
    val aiModel: AIModel? = null,              // 用户为该 Agent 设置的默认模型
    val reasoningLevel: ReasoningLevel? = null // 用户为该 Agent 设置的默认推理等级
)
```

`AgentMode` 枚举（决定工具权限范围和系统提示词）：

| 枚举值 | 权限 | 说明 |
|---|---|---|
| `EXPLORE` | 只读 | 仅允许搜索、读取文件、查看结构 |
| `PLAN` | 只读 + 规划 | 可读取分析、制定计划但不执行 |
| `EXECUTE` | 读写 | 允许所有操作（写文件、执行命令等） |

### 5.2 更新 Agent 预设

可以更新 `aiModel`、`reasoningLevel`、`systemPrompt`、`tools`。`name` / `description` 固定不可改。

```kotlin
val model = mederi.providers.listModels(provider.id).first()

mederi.agents.update(
    mode = AgentMode.EXECUTE,
    aiModel = model,
    reasoningLevel = ReasoningLevel.HIGH
)

// 也可自定义系统提示词和工具列表
mederi.agents.update(
    mode = AgentMode.EXECUTE,
    aiModel = model,
    reasoningLevel = ReasoningLevel.MEDIUM,
    systemPrompt = "You are a specialized coding assistant.",
    tools = listOf("read_file", "write_file", "execute_command")
)
```

> **注意**：`aiModel` 需要传入完整的 `AIModel` 对象（含系统生成的 `id`，如 `mdl_xxx`）。不要通过 `provider.models.first()` 拿模型——`providers.create()` 返回的 Provider 里 `models` 是空的，必须用 `listModels` / `mederi.models.*` 查询。

三种模式都可以作为子 Agent 被主 Agent 调用，但只有 `PLAN` 和 `EXECUTE` 能直接创建 Session。

### 5.3 内置 Agent

Mederi 初始化时会写入三个内置 Agent 预设：

| mode | 名称 | 说明 |
|---|---|---|
| `EXPLORE` | Explore | 只读探索，只能作为子 Agent 被调用，不能直接创建 Session |
| `PLAN` | Plan | 规划，用户可直接选择创建 Session |
| `EXECUTE` | Execute | 执行，用户可直接选择创建 Session |

- `EXPLORE` 不能直接用于创建 Session（抛 `MederiValidationException`）
- 内置 Agent **没有独立 ID**，以 `AgentMode` 为唯一 key
- 内置 Agent **不允许删除**，也**不允许创建新的 Agent 预设**
- 预设的 `aiModel` 默认为 `null`，即未设置默认模型

### 5.4 验证与异常

| 调用 | 异常 | 说明 |
|---|---|---|
| `get(mode 不存在)` | `MederiNotFoundException` | 找不到 Agent 预设 |
| `create` / `delete` | 不存在 | 暂不允许用户创建或删除 Agent |

**关联**：
- `list()` 用于 UI 展示可选的 Agent（Plan / Execute）。
- `Session.create` 通过 `AgentConfig.mode` 选择 Agent，`sendMessage` 时根据 mode 查询 `AgentPreset` 的 `systemPrompt` 和 `tools`。
- 删除 Agent 预设不影响已有 Session（Session 不持有预设引用）。

---

## 6. Session API

路径：`mederi.sessions`

```kotlin
interface SessionApi {
    suspend fun list(): List<Session>
    suspend fun create(request: CreateSessionRequest): Session
    suspend fun get(id: String): Session
    suspend fun rename(id: String, request: RenameSessionRequest): Session
    suspend fun delete(id: String)
    suspend fun abort(id: String)

    suspend fun sendMessage(sessionId: String, request: SendMessageRequest)
    suspend fun resolveApproval(sessionId: String, approvalId: String, approved: Boolean, feedback: String? = null)
    suspend fun listMessages(sessionId: String): List<Message>
    suspend fun getMessage(sessionId: String, messageId: String): Message

    fun events(sessionId: String): Flow<MederiEvent>
    fun events(): Flow<MederiEvent>
}
```

### 6.1 Session 返回结构

`Session`（`xyz.mederi.domain.model.Session`）：

```kotlin
enum class SessionStatus { IDLE, RUNNING, ERROR }

data class Session(
    val id: String,               // sess_xxx
    val projectId: String,        // proj_xxx，所属项目
    val title: String,            // 空时默认 "New Session"
    val status: SessionStatus,    // IDLE / RUNNING / ERROR
    val mode: AgentMode,          // 当前 Agent 模式
    val aiModel: AIModel?,        // 用户最后一次选择的模型，可为 null
    val reasoningLevel: ReasoningLevel?, // 用户最后一次选择的推理等级，可为 null
    val env: Map<String, String> = emptyMap(),
    val createdAt: String,        // ISO 8601
    val updatedAt: String         // ISO 8601
)
```

Session 不再冗余存储 `systemPrompt` 和 `tools`，它们由当前 `mode` 对应的 `AgentPreset` 动态提供。

### 6.2 请求 DTO

```kotlin
// Agent 运行时配置，Session.create 和 sendMessage 都使用
data class AgentConfig(
    val mode: AgentMode,                          // 必填，决定系统提示词和工具
    val aiModel: AIModel? = null,                 // 可选，null 时继承 Session 记住的模型
    val reasoningLevel: ReasoningLevel? = null    // 可选，null 时继承 Session 记住的等级
)

data class CreateSessionRequest(
    val agentConfig: AgentConfig,                  // 必填，mode 不能是 EXPLORE
    val projectId: String,                         // 必填，必须存在
    val title: String = "",                        // 空时默认为 "New Session"
    val env: Map<String, String> = emptyMap()
)

data class RenameSessionRequest(
    val title: String
)

data class SendMessageRequest(
    val agentConfig: AgentConfig,                  // 必填，不能为 null
    val parts: List<MessagePart>                   // 必填，用户消息内容片段
)
```

`Message` 与 `MessagePart` 返回结构（见 6.6）。

### 6.3 创建 Session

Session 必须绑定一个 Project 和一个可直接对话的 Agent 模式（`PLAN` 或 `EXECUTE`）。创建时只记录 `mode`、`aiModel`、`reasoningLevel`，系统提示词和工具在发送消息时根据 mode 动态查询 Agent 预设。

```kotlin
val model = mederi.providers.listModels(provider.id).first()

val session = mederi.sessions.create(
    CreateSessionRequest(
        agentConfig = AgentConfig(AgentMode.EXECUTE, model),
        projectId = project.id,
        title = "Fix login bug"
    )
)
// 返回 Session，status = IDLE
```

创建成功后事件流发出 `SESSION_CREATED` 事件。

### 6.4 发送消息

`sendMessage` 只负责**追加用户消息并触发 Agent 后台运行**，然后立即返回，不等待 Agent 回复，也不返回任何消息对象。

```kotlin
mederi.sessions.sendMessage(
    session.id,
    SendMessageRequest(
        agentConfig = AgentConfig(AgentMode.EXECUTE, model),
        parts = listOf(MessagePart.Text("Fix the login bug"))
    )
)
```

Agent 的回复文本、推理过程通过 `MESSAGE_DELTA` 事件异步流出；回复完成后通过 `MESSAGE_COMPLETED` 事件告知最终消息 ID。调用方可以通过 `listMessages(sessionId)` 读取完整历史。

> 如需同步等待一次完整回复，核心库当前没有阻塞式 API，需要调用方自己基于事件流实现。

模型/推理等级选择优先级：
1. `AgentConfig.aiModel` / `AgentConfig.reasoningLevel`（如果传了）
2. Session 记住的上次值
3. 都没有则 `sendMessage` 前置校验失败

回写行为：`sendMessage` 会把本次实际使用的 `mode`、`aiModel`、`reasoningLevel` 回写到 Session，作为下次默认。

### 6.5 错误反馈

无论是前置校验失败（session 不存在、provider/model 缺失、key 缺失）还是运行期失败（网络、LLM 错误、工具错误），错误都会通过事件流反馈：

- 前置失败：`sendMessage` 抛异常 **同时** 发出 `MESSAGE_ERROR` 事件
- 运行期失败：只通过 `MESSAGE_ERROR` 事件反馈（`sendMessage` 已返回）

### 6.6 消息与消息片段结构

`Message`（`xyz.mederi.domain.model.Message`）：

```kotlin
enum class MessageRole { SYSTEM, USER, ASSISTANT }
enum class MessageStatus { PROCESSING, COMPLETED, ERROR }

data class Message(
    val id: String? = null,          // msg_xxx
    val sessionId: String,           // sess_xxx
    val role: MessageRole,
    val parts: List<MessagePart>,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val createdAt: String,           // ISO 8601
    val finishReason: String? = null,// 仅 Assistant，如 "stop"
    val totalTokens: Int? = null     // 仅 Assistant
)
```

`MessagePart`（sealed class，`xyz.mederi.domain.model.MessagePart`）：

| 子类 | 字段 | 说明 |
|---|---|---|
| `MessagePart.Text` | `text: String` | 文本内容 |
| `MessagePart.Image` | `url: String`, `mimeType: String?` | 图片附件 |
| `MessagePart.File` | `path: String` | 文件附件 |
| `MessagePart.ToolCall` | `id: String?`, `tool: String`, `args: String` | 工具调用（Assistant 消息） |
| `MessagePart.ToolResult` | `id: String?`, `tool: String`, `output: String`, `isError: Boolean` | 工具结果（User 消息） |
| `MessagePart.Reasoning` | `content: List<String>`, `summary: List<String>?`, `encrypted: String?`, `id: String?` | 推理过程（Assistant 消息） |

### 6.7 验证与异常

| 调用 | 异常 | 说明 |
|---|---|---|
| `create(agentConfig.mode = EXPLORE)` | `MederiValidationException` | EXPLORE 不能直接创建 Session |
| `create(projectId 不存在)` | `MederiNotFoundException` | 找不到 Project |
| `sendMessage(sessionId 不存在)` | `MederiNotFoundException` | 找不到 Session（同时发 MESSAGE_ERROR 事件） |
| `sendMessage(未选择模型)` | `MederiNotFoundException` | `agentConfig.aiModel` 和 `session.aiModel` 都为 null |
| `sendMessage(provider/model/key 缺失)` | `MederiNotFoundException` / `MederiStateException` | 前置校验失败，同时发 MESSAGE_ERROR 事件 |
| `sendMessage(同一 Session 正在运行)` | `MederiStateException` | Session 正在运行 |
| `getMessage(messageId 不存在)` | `MederiNotFoundException` | 找不到消息 |
| `rename(title 为空)` | `MederiValidationException` | 标题不能为空 |
| `get` / `rename` / `delete` / `abort` / `listMessages`（sessionId 不存在） | `MederiNotFoundException` | 找不到 Session |

**关联**：
- 创建 Session 依赖 `mederi.projects`（Project 必须存在）和 `AgentConfig.mode`（必须是 `PLAN` 或 `EXECUTE`）。
- `sendMessage` 运行期依赖 Provider 配置：`aiModel` 必须属于某 Provider 且该 Provider 必须有默认 Key，否则前置校验失败。
- 删除 Project 会级联删除其下所有 Session 及对话历史；单独删除 Session 也会删除其对话历史。
- 修改 Agent 预设不影响已有 Session。

---

## 7. Model API

路径：`mederi.models`

```kotlin
interface ModelApi {
    suspend fun list(): List<AIModel>
    suspend fun get(modelId: String): AIModel?
}
```

跨供应商聚合查询。`list()` 等价于遍历所有 Provider 的 `models` 后合并；`get(id)` 在所有 Provider 中查找。

```kotlin
val allModels = mederi.models.list()      // 所有供应商的模型
val model = mederi.models.get("mdl_xxx")  // 找不到返回 null（注意：不是抛异常）
```

**与 Provider API 的关联**：
- 等价于 `mederi.providers.list().flatMap { it.models }`
- `get()` 找不到时返回 `null`，与 `providers.get()`（找不到抛 `MederiNotFoundException`）行为不同。
- 获取 `AIModel` 后可用于 `mederi.agents.update(...)` 设置 Agent 默认模型，或传入 `AgentConfig.aiModel` 创建 Session / 发送消息。

---

## 8. 事件流 API

事件流是 Kotlin `Flow<MederiEvent>`，不是 SSE 协议。SSE 需要 `mederi-server` 模块将来包装。

```kotlin
interface SessionApi {
    fun events(sessionId: String): Flow<MederiEvent>  // 订阅单个 session 的事件
    fun events(): Flow<MederiEvent>                   // 订阅全局所有事件
}
```

`events()` 返回的 Flow 是**热流**（`MutableSharedFlow`，replay = 64）：`sendMessage` 之前要先订阅，否则会错过历史事件（但有 64 条回放缓冲）。

### 8.1 订阅事件

```kotlin
import kotlinx.coroutines.flow.collect
import xyz.mederi.domain.model.EventType

// 订阅单个 session
launch {
    mederi.sessions.events(session.id).collect { event ->
        when (event.type) {
            EventType.MESSAGE_DELTA -> {
                when (event.payload["type"]) {
                    "text" -> print(event.payload["content"])
                    "reasoning" -> print("[reasoning] ${event.payload["content"]}")
                }
            }
            EventType.MESSAGE_COMPLETED -> println("\nDone.")
            EventType.MESSAGE_ERROR -> println("Error: ${event.payload["error"]}")
            EventType.SESSION_UPDATED -> println("Session status updated")
            EventType.SESSION_CREATED -> println("Session created")
        }
    }
}
```

### 8.2 MederiEvent 结构

`MederiEvent`（`xyz.mederi.domain.model.MederiEvent`）：

```kotlin
data class MederiEvent(
    val type: EventType,          // 事件类型
    val sessionId: String,        // sess_xxx
    val messageId: String? = null,// 关联的消息 ID（仅 MESSAGE_COMPLETED 等）
    val payload: Map<String, String> = emptyMap(),   // 附加数据，见下表
    val timestamp: String         // ISO 8601
)
```

### 8.3 事件类型

| 事件 | 触发时机 | payload |
|---|---|---|
| `SESSION_CREATED` | Session 创建成功 | 空 |
| `SESSION_UPDATED` | Session 状态更新（如 RUNNING / IDLE / ERROR） | 空 |
| `MESSAGE_DELTA` | 流式输出片段 | `type` = `text`、`reasoning` 或 `tool_call`；`content` = 片段内容 |
| `MESSAGE_COMPLETED` | Agent 回复完成 | 空（`messageId` 指向 Assistant 消息） |
| `MESSAGE_ERROR` | 前置/运行期出错 | `error` = 错误信息 |
| `TOOL_CALLED` | 工具即将执行 | `tool` = 工具名，`toolCallId` = 调用 ID，`args` = 参数 JSON |
| `TOOL_RESULT` | 工具执行完成（含失败） | `tool` = 工具名，`toolCallId` = 调用 ID，`output` = 结果，`isError` = `true`/`false` |
| `APPROVAL_REQUESTED` | 工具请求用户审批 | `approvalId` = 审批 ID，`tool` = 工具名，`description` = 描述，`args` = 参数 |
| `APPROVAL_RESOLVED` | 用户回复审批 | `approvalId` = 审批 ID，`approved` = `true`/`false`，`feedback` = 反馈 |

### 8.4 推理内容

当 LLM 返回 reasoning 内容时，会发出 `MESSAGE_DELTA` 事件，`payload["type"]` 为 `reasoning`：

```kotlin
MederiEvent(
    type = EventType.MESSAGE_DELTA,
    sessionId = sessionId,
    payload = mapOf("type" to "reasoning", "content" to "...")
)
```

底层 `MederiOpenAILLMClient` 继承 Koog 的 `OpenAILLMClient`，仅覆盖流式和非流式响应解析以支持 `reasoning`/`reasoning_content`。Chat Completions 和 Responses API 双模式均支持。

---

## 9. 完整调用示例

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.api.*
import xyz.mederi.api.exception.*
import xyz.mederi.domain.model.*

fun main() = runBlocking {
    val mederi = Mederi.local(config = "~/.mederi")

    // 1. 创建供应商
    val provider = try {
        mederi.providers.create(
            CreateProviderRequest(
                name = "OpenAI",
                type = "OPENAI_CHAT",
                baseUrl = "https://api.openai.com/v1",
                apiKeys = listOf(
                    CreateApiKeyRequest(name = "default", value = System.getenv("OPENAI_KEY") ?: "", isDefault = true)
                ),
                models = listOf(
                    CreateModelRequest(providerModelId = "gpt-4o", name = "GPT-4o", supportsReasoning = false)
                )
            )
        )
    } catch (e: MederiValidationException) {
        println("配置错误: ${e.message}")
        return@runBlocking
    }

    // 2. 通过 listModels 获取模型（create() 返回的 provider.models 是空的）
    val model = mederi.providers.listModels(provider.id).first()

    // 3. 创建项目
    val project = mederi.projects.create(
        CreateProjectRequest(name = "Demo", directories = listOf("/tmp/demo"))
    )

    // 4. 可选：设置 Execute Agent 的默认模型
    mederi.agents.update(AgentMode.EXECUTE, aiModel = model, reasoningLevel = null)

    // 5. 创建 Session
    val session = mederi.sessions.create(
        CreateSessionRequest(
            agentConfig = AgentConfig(AgentMode.EXECUTE, model),
            projectId = project.id,
            title = "Hello"
        )
    )

    // 6. 订阅事件
    launch {
        mederi.sessions.events(session.id).collect { event ->
            when (event.type) {
                EventType.MESSAGE_DELTA -> {
                    val type = event.payload["type"]
                    val content = event.payload["content"] ?: ""
                    if (type == "reasoning") print("[思考] $content")
                    else print(content)
                }
                EventType.MESSAGE_COMPLETED -> println("\n完成")
                EventType.MESSAGE_ERROR -> println("\n错误: ${event.payload["error"]}")
                else -> {}
            }
        }
    }

    // 7. 发送消息
    try {
        mederi.sessions.sendMessage(
            session.id,
            SendMessageRequest(
                agentConfig = AgentConfig(AgentMode.EXECUTE, model),
                parts = listOf(MessagePart.Text("Say hello"))
            )
        )
    } catch (e: MederiException) {
        println("发送失败: ${e.message}")
    }
}
```

> **关键修正**：第 2 步必须用 `listModels` 获取模型。原文档示例 `provider.models.first().id` 是错的——`providers.create()` 返回的 Provider 中 `models` 为空列表。

---

## 10. API 关联关系速查

| 操作 | 依赖/关联 |
|---|---|
| `providers.create` | 返回对象 `apiKeys` / `models` 为空；用 `listKeys()` / `listModels()` 查询实际写入的数据 |
| `providers.get` / `providers.list` | 返回的 Provider 读时合并 `apiKeys`，`models` 完整 |
| `providers.delete` | 级联删除该供应商所有 Key；不级联删 Session（其 aiModel 快照仍在） |
| `providers.addModel` / `updateModel` / `deleteModel` | 影响 `mederi.models.list()` 聚合结果 |
| `agents.update` | 可改 `aiModel` / `reasoningLevel` / `systemPrompt` / `tools` |
| `agents.get(mode)` | 按 `AgentMode` 查询预设；三种内置预设：EXPLORE / PLAN / EXECUTE |
| `projects.delete` | 级联删除其下所有 Session + 对话历史 |
| `sessions.create` | 依赖 `AgentConfig.mode`（必须是 PLAN / EXECUTE）+ 存在的 Project |
| `sessions.sendMessage` | 运行期依赖 Provider + 默认 Key + Model；`agentConfig` 必填；异步执行，事件走 `events()` |
| `sessions.delete` | 删除对话历史 |
| `models.get` | 找不到返回 `null`（区别于 `providers.get` 抛异常） |

---

## 11. 与 Server API 的对应关系

| core API | server REST endpoint |
|---|---|
| `mederi.projects.list()` | `GET /v1/projects` |
| `mederi.projects.create(...)` | `POST /v1/projects` |
| `mederi.projects.get(id)` | `GET /v1/projects/{id}` |
| `mederi.projects.rename(id, ...)` | `PATCH /v1/projects/{id}` |
| `mederi.projects.delete(id)` | `DELETE /v1/projects/{id}` |
| `mederi.projects.addDirectory(id, ...)` | `POST /v1/projects/{id}/directories` |
| `mederi.projects.removeDirectory(id, ...)` | `DELETE /v1/projects/{id}/directories` |
| `mederi.providers.list()` | `GET /v1/providers` |
| `mederi.providers.create(...)` | `POST /v1/providers` |
| `mederi.providers.get(id)` | `GET /v1/providers/{id}` |
| `mederi.providers.update(id, ...)` | `PATCH /v1/providers/{id}` |
| `mederi.providers.delete(id)` | `DELETE /v1/providers/{id}` |
| `mederi.providers.addKey(id, ...)` | `POST /v1/providers/{id}/keys` |
| `mederi.providers.listKeys(id)` | `GET /v1/providers/{id}/keys` |
| `mederi.providers.deleteKey(...)` | `DELETE /v1/providers/{id}/keys/{keyId}` |
| `mederi.providers.setDefaultKey(...)` | `POST /v1/providers/{id}/keys/{keyId}/set-default` |
| `mederi.providers.listModels(id)` | `GET /v1/providers/{id}/models` |
| `mederi.providers.addModel(...)` | `POST /v1/providers/{id}/models` |
| `mederi.providers.updateModel(...)` | `PATCH /v1/providers/{id}/models/{modelId}` |
| `mederi.providers.deleteModel(...)` | `DELETE /v1/providers/{id}/models/{modelId}` |
| `mederi.models.list()` | `GET /v1/models` |
| `mederi.agents.list()` | `GET /v1/agents` |
| `mederi.agents.get(mode)` | `GET /v1/agents/{mode}` |
| `mederi.agents.update(mode, ...)` | `PATCH /v1/agents/{mode}` |
| `mederi.sessions.list()` | `GET /v1/sessions` |
| `mederi.sessions.create(...)` | `POST /v1/sessions` |
| `mederi.sessions.get(id)` | `GET /v1/sessions/{id}` |
| `mederi.sessions.rename(id, ...)` | `PATCH /v1/sessions/{id}` |
| `mederi.sessions.delete(id)` | `DELETE /v1/sessions/{id}` |
| `mederi.sessions.abort(id)` | `POST /v1/sessions/{id}/abort` |
| `mederi.sessions.sendMessage(id, ...)` | `POST /v1/sessions/{id}/messages` |
| `mederi.sessions.listMessages(id)` | `GET /v1/sessions/{id}/messages` |
| `mederi.sessions.getMessage(...)` | `GET /v1/sessions/{id}/messages/{messageId}` |
| `mederi.sessions.resolveApproval(...)` | `POST /v1/sessions/{id}/approvals/{approvalId}` |
| `mederi.sessions.events()` | `GET /v1/events` (SSE) |
| `mederi.sessions.events(id)` | `GET /v1/sessions/{id}/events` (SSE) |

> 当前 `mederi-server` 模块仅 Ktor 骨架，以上 REST / SSE endpoint 尚未实现。
