# Mederi API 文档

> 版本：v1.0.0（架构重构后）
> 状态：与当前代码同步

## 一、两种使用模式

Mederi 是一个 Kotlin 库，封装 Koog 的 AI Agent 能力。它提供两种入口模式，**创建后的 API 完全一致**。

### 1.1 本地模式：`Mederi.local(config)`

适用于桌面应用、本地脚本、个人工具。

```kotlin
import xyz.mederi.Mederi

val mederi = Mederi.local(config = "~/.mederi")
```

`~` 会自动展开为当前用户目录（如 `/Users/alice/.mederi`）。

指定目录后，Mederi 会自动创建：

```text
/path/to/.mederi/
├── config/
│   ├── providers.json    # 供应商配置
│   └── agents.json       # Agent 配置
└── data/
    └── mederi.db         # SQLite（对话历史 + API Key + Session 列表）
```

### 1.2 后端模式：`Mederi.backend(...)`

适用于把 Mederi 作为互联网后端服务的 Agent 端。调用方提供所有 Store，Mederi 不读写本地文件。

```kotlin
val mederi = Mederi.backend(
    historyStore = MyPostgresHistoryStore(dataSource),
    sessionStore = MyPostgresSessionStore(dataSource),
    apiKeyStore = MyPostgresApiKeyStore(dataSource),
    providerStore = MyPostgresProviderStore(dataSource),
    agentStore = MyPostgresAgentStore(dataSource),
    projectStore = MyPostgresProjectStore(dataSource)
)
```

### 1.3 自定义模式：`Mederi.create { }`

以上两种模式最终都调用 `create`。需要精细控制时使用。

```kotlin
val mederi = Mederi.create {
    configDir = "/path/to/.mederi"
    // 或显式注入 store：
    // historyStore = customHistoryStore
}
```

---

## 二、核心子 API

创建 Mederi 实例后，通过以下子 API 操作：

```kotlin
mederi.providers   // ProviderApi
mederi.models      // ModelApi
mederi.agents      // AgentApi
mederi.sessions    // SessionApi
```

---

## 三、Provider API

管理模型供应商（OpenAI、DeepSeek、Google 等）。

### 3.1 创建供应商

```kotlin
import xyz.mederi.api.CreateProviderRequest
import xyz.mederi.api.CreateApiKeyRequest
import xyz.mederi.api.CreateModelRequest
import xyz.mederi.provider.domain.model.ProviderType

val provider = mederi.providers.create(
    CreateProviderRequest(
        name = "OpenAI Prod",
        type = ProviderType.OPENAI_CHAT.name,
        baseUrl = "https://api.openai.com/v1",
        apiKeys = listOf(
            CreateApiKeyRequest(name = "Primary", value = "sk-...", isDefault = true)
        ),
        models = listOf(
            CreateModelRequest(providerModelId = "gpt-4o", name = "GPT-4o", supportsReasoning = false)
        )
    )
)
```

### 3.2 常用 API

```kotlin
mederi.providers.list()                    // 所有供应商（含 API Key）
mederi.providers.get(id)                   // 单个供应商
mederi.providers.update(id, request)       // 更新
mederi.providers.delete(id)                // 删除
mederi.providers.listKeys(providerId)      // 列出 API Key
mederi.providers.addKey(providerId, name, value, isDefault)
mederi.providers.deleteKey(providerId, keyId)
mederi.providers.setDefaultKey(providerId, keyId)
mederi.providers.listModels(providerId)    // 列出模型
mederi.providers.addModel(providerId, request)
mederi.providers.deleteModel(providerId, modelId)
```

---

## 四、Model API

模型是 Provider 下的配置，每个模型有一个 Mederi 内部 ID。

```kotlin
val models = mederi.providers.listModels(providerId)
val model = mederi.models.get(modelId)
```

---

## 五、Agent API

Agent 是**预设配置**，不是实体。Mederi 内置三种 Agent 预设（Explore / Plan / Execute），以 `AgentMode` 为 key。用户不能创建自定义 Agent，但可以为每种预设设置默认模型、推理等级、自定义系统提示词和工具列表。

### 5.1 Agent 预设字段

```kotlin
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.AgentPreset

data class AgentPreset(
    val mode: AgentMode,                       // EXPLORE / PLAN / EXECUTE
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",             // 由 mode 决定，用户可覆盖
    val tools: List<String> = emptyList(),     // 由 mode 决定，用户可覆盖
    val aiModel: AIModel? = null,              // 用户设置的默认模型
    val reasoningLevel: ReasoningLevel? = null // 用户设置的默认推理等级
)
```

### 5.2 更新 Agent 预设

```kotlin
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.provider.domain.model.ReasoningLevel

val model = mederi.providers.listModels(providerId).first()

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

### 5.3 常用 API

```kotlin
mederi.agents.list()                        // 返回 3 个内置预设
mederi.agents.get(mode = AgentMode.EXECUTE)
mederi.agents.update(mode, aiModel, reasoningLevel, systemPrompt, tools)
// create / delete 暂不存在
```

---

## 六、Session API

Session 是**对话容器**，记录当前 `mode` 和用户最后一次选择的模型/推理等级。系统提示词和工具由当前 mode 对应的 Agent 预设动态提供。

### 6.1 创建 Session

```kotlin
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.CreateSessionRequest
import xyz.mederi.domain.model.AgentMode

val model = mederi.providers.listModels(providerId).first()

val session = mederi.sessions.create(
    CreateSessionRequest(
        agentConfig = AgentConfig(AgentMode.EXECUTE, model),
        projectId = project.id,
        title = "Fix login bug"
    )
)
```

### 6.2 Session 保存的配置

创建后，Session 保存：

- `mode`
- `aiModel`
- `reasoningLevel`
- `env`

`systemPrompt` 和 `tools` 不冗余存储，发送消息时根据 `mode` 查询 `AgentPreset`。

### 6.3 发送消息（流式）

```kotlin
import xyz.mederi.api.AgentConfig
import xyz.mederi.api.SendMessageRequest
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.provider.domain.model.ReasoningLevel

mederi.sessions.sendMessage(
    session.id,
    SendMessageRequest(
        agentConfig = AgentConfig(
            mode = AgentMode.EXECUTE,
            aiModel = model,
            reasoningLevel = ReasoningLevel.MEDIUM
        ),
        parts = listOf(MessagePart.Text("帮我看看这个登录 401 的问题"))
    )
)

> `AgentConfig` 必填。如果 `aiModel` / `reasoningLevel` 为 null，会继承 Session 记住的上次值。

`sendMessage` 只触发运行，立即返回。用户消息会追加到历史，AI 回复通过事件流异步推送。

### 6.4 监听事件流

```kotlin
import xyz.mederi.domain.model.EventType
import kotlinx.coroutines.flow.filter

mederi.sessions.events(session.id)
    .filter { it.type == EventType.MESSAGE_DELTA }
    .collect { event ->
        val type = event.payload["type"]     // "text" / "reasoning" / "tool_call"
        val content = event.payload["content"]
        print(content)
    }
```

事件类型：

| 事件 | 触发时机 |
|---|---|
| `SESSION_CREATED` | Session 创建 |
| `SESSION_UPDATED` | Session 状态变化（RUNNING/IDLE/ERROR） |
| `MESSAGE_DELTA` | 流式输出一个文字/推理/工具调用片段 |
| `MESSAGE_COMPLETED` | AI 回复完成 |
| `MESSAGE_ERROR` | AI 运行出错 |
| `TOOL_CALLED` | 工具即将执行（含工具名和参数） |
| `TOOL_RESULT` | 工具执行完成或失败（含结果和错误标志） |
| `APPROVAL_REQUESTED` | 工具请求用户审批（write_file/edit_file/apply_patch/execute_command） |
| `APPROVAL_RESOLVED` | 用户回复审批（批准或拒绝） |

`MESSAGE_COMPLETED` 的 `messageId` 是最终 assistant 消息在历史中的 ID。

### 6.5 审批（Approval）

当 Agent 使用写文件、执行命令等工具时，如果配置了审批，工具执行会挂起等待用户确认：

```kotlin
// 订阅审批事件
mederi.sessions.events(session.id).collect { event ->
    if (event.type == EventType.APPROVAL_REQUESTED) {
        val approvalId = event.payload["approvalId"]
        val tool = event.payload["tool"]
        val description = event.payload["description"]
        // UI 展示审批对话框，用户选择批准或拒绝
    }
}

// 用户回复
mederi.sessions.resolveApproval(
    sessionId = session.id,
    approvalId = approvalId,
    approved = true,
    feedback = "Looks good"
)
```

受审批控制的工具：`write_file`、`edit_file`、`apply_patch`、`execute_command`。

### 6.6 中止运行

```kotlin
mederi.sessions.abort(session.id)
```

### 6.7 常用 API

```kotlin
mederi.sessions.list()
mederi.sessions.get(id)
mederi.sessions.rename(id, RenameSessionRequest(title = "New Title"))
mederi.sessions.delete(id)
mederi.sessions.abort(id)
mederi.sessions.resolveApproval(sessionId, approvalId, approved, feedback)
mederi.sessions.listMessages(sessionId)
mederi.sessions.getMessage(sessionId, messageId)
mederi.sessions.events(sessionId)   // 某个 session 的事件
mederi.sessions.events()            // 所有事件
```

---

## 七、消息模型

### 7.1 MessagePart

```kotlin
sealed class MessagePart {
    data class Text(val text: String) : MessagePart()
    data class Image(val url: String, val mimeType: String? = null) : MessagePart()
    data class File(val path: String) : MessagePart()
    data class ToolCall(val id: String?, val tool: String, val args: String) : MessagePart()
    data class ToolResult(val id: String?, val tool: String, val output: String, val isError: Boolean = false) : MessagePart()
    data class Reasoning(val content: List<String>, val summary: List<String>? = null, val encrypted: String? = null, val id: String? = null) : MessagePart()
}
```

| 子类 | 字段 | 说明 |
|---|---|---|
| `Text` | `text` | 文本内容 |
| `Image` | `url`, `mimeType?` | 图片附件（已映射到 Koog，发送给 LLM） |
| `File` | `path` | 文件附件 |
| `ToolCall` | `id?`, `tool`, `args` | 工具调用（Assistant 消息） |
| `ToolResult` | `id?`, `tool`, `output`, `isError` | 工具结果（User 消息，已映射到 Koog） |
| `Reasoning` | `content`, `summary?`, `encrypted?`, `id?` | 推理过程（Assistant 消息） |

### 7.2 Message

```kotlin
data class Message(
    val id: String?,
    val sessionId: String,
    val role: MessageRole,        // SYSTEM / USER / ASSISTANT
    val parts: List<MessagePart>,
    val status: MessageStatus,
    val createdAt: String,
    val finishReason: String?,
    val totalTokens: Int?
)
```

---

## 八、AgentMode 与工具权限

AgentMode 是**工具权限**，不是不同的 Agent：

| 模式 | 权限 |
|---|---|
| `AgentMode.EXPLORE` | 只读 | 仅允许搜索、读取文件、查看结构 |
| `AgentMode.PLAN` | 只读 + 规划 | 可读取分析、制定计划但不执行 |
| `AgentMode.EXECUTE` | 读写 | 允许所有操作（写文件、执行命令等） |

三种模式都可以作为子 Agent 被主 Agent 调用，但只有 `PLAN` 和 `EXECUTE` 能直接创建 Session。

---

## 九、完整示例：本地聊天

```kotlin
import xyz.mederi.Mederi
import xyz.mederi.api.*
import xyz.mederi.domain.model.*
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val mederi = Mederi.local(config = "~/.mederi")

    // 1. 创建 Provider
    val provider = mederi.providers.create(
        CreateProviderRequest(
            name = "OpenAI",
            type = ProviderType.OPENAI_CHAT.name,
            baseUrl = "https://api.openai.com/v1",
            apiKeys = listOf(
                CreateApiKeyRequest(name = "Primary", value = System.getenv("OPENAI_API_KEY"), isDefault = true)
            ),
            models = listOf(
                CreateModelRequest(providerModelId = "gpt-4o", name = "GPT-4o", supportsReasoning = false)
            )
        )
    )
    val model = mederi.providers.listModels(provider.id).first()

    // 2. 创建 Session（直接选 EXECUTE Agent 模式）
    val session = mederi.sessions.create(
        CreateSessionRequest(
            agentConfig = AgentConfig(
                mode = AgentMode.EXECUTE,
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE
            ),
            projectId = "proj_xxx",
            title = "Test"
        )
    )

    // 3. 监听流式回复
    val job = launch {
        mederi.sessions.events(session.id)
            .filter { it.type == EventType.MESSAGE_DELTA }
            .collect { print(it.payload["content"]) }
    }

    // 4. 发送消息
    mederi.sessions.sendMessage(
        session.id,
        SendMessageRequest(
            agentConfig = AgentConfig(
                mode = AgentMode.EXECUTE,
                aiModel = model,
                reasoningLevel = ReasoningLevel.NONE
            ),
            parts = listOf(MessagePart.Text("Say hello"))
        )
    )

    job.join()
}
```

---

## 十一、REPL 测试工具

项目包含一个 `cli` 模块，提供命令行 REPL，用于快速测试 API 和供应商配置。

### 运行

有两种方式运行 REPL。推荐用生成的脚本，更稳定：

```bash
# 1. 生成并运行脚本（推荐）
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli

# 2. 直接用 Gradle run
./gradlew :cli:run
```

不传参数默认使用 `~/.mederi`。如需指定目录：

```bash
./cli/build/install/cli/bin/cli /path/to/.mederi
```

### 常用命令

```text
> help                                    显示帮助
> providers                               列出供应商
> provider-create OpenAI OPENAI_CHAT https://api.openai.com/v1 sk-xxx gpt-4o
> projects                                列出项目
> project-create MyProject /tmp/project   创建项目
> agents                                  列出 Agent 预设
> sessions                                列出 Session
> session-create EXECUTE proj_xxx "Test"  创建 Session
> send sess_xxx 你好                      发送消息，流式输出
> messages sess_xxx                       查看历史
> abort sess_xxx                          中止当前 turn
> exit                                    退出
```

REPL 会创建真实的 `Mederi.local()` 实例并调用真实 API，需要有效的 API Key。


1. **旧数据库不兼容**：schema 已变，启动前删除旧的 `data/mederi.db`。
2. **API Key 安全**：`ProviderApiKey.maskedValue` 返回脱敏值（如 `sk-...7890`），原始值不通过 API 返回。
