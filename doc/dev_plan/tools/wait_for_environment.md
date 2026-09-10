# wait_for_environment（等待环境就绪工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/wait_for_environment.rs`，环境状态在 `codex-rs/core/src/session/step_context.rs`（`TurnEnvironmentState` / `starting()` / `turn_environments()`），等待逻辑在 `TurnEnvironment::wait_until_ready()`。

## 一、功能描述

`wait_for_environment` 让模型**等待一个标记为 `starting` 的延迟执行环境变为可用**。

它的设计目的：

- Codex 支持「延迟环境」：某些执行环境（远程沙箱、云端工作区）不在会话开始时就绪，而是处于 `starting` 状态异步启动。此时模型在 `<environment_context>` 里能看到这个 `starting` 环境及其 id，但暂时无法使用它的文件/命令。
- 当任务确实需要该环境的文件、命令或已安装能力时，模型调用 `wait_for_environment` 阻塞等待其就绪。
- **不要**在任务可以用现有工具（如 connectors）完成时等待——这是文档里明确写给模型的约束。
- 等待可能耗时数分钟，会阻塞其他工具调用；若启动失败，模型应「不带该环境继续」。

工具名常量为 `WAIT_FOR_ENVIRONMENT_TOOL_NAME = "wait_for_environment"`。

## 二、Codex 的参数定义（args schema）

工具定义在 [wait_for_environment.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/wait_for_environment.rs) 中，描述可由宿主注入配置。

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| `environment_id` | string | **是** | `<environment_context>` 中标记为 `starting` 的精确环境 id。 |

`strict: false`，`defer_loading: None`，`required: ["environment_id"]`，`additionalProperties: false`。`output_schema: None`。

默认描述（`DEFAULT_TOOL_DESCRIPTION`）：
```
Wait for a selected execution environment marked as `starting` to become available. Use this when the current task needs that environment's files, commands, or installed capabilities. Do not wait if the task can be completed using tools already available, such as connectors. Waiting may take several minutes and blocks other tool calls. If startup fails, continue without that environment.
```
默认 `environment_id` 描述（`DEFAULT_ENVIRONMENT_ID_DESCRIPTION`）：
```
The exact environment ID marked as `starting` in `<environment_context>`.
```

### 描述大小保护（WaitForEnvironmentHandler::new）

- 宿主可提供 `WaitForEnvironmentToolConfig { tool_description, environment_id_description }` 覆盖默认描述。
- 若 `tool_description + environment_id_description` 合计超过 `1_024` 字节，**或**序列化后的完整 tool spec 超过 `1_000` 字节，则回退到 Core 默认描述（并打 `oversized ... falling back to Core defaults` 警告）。防止宿主配置撑爆 token 预算。

## 三、Codex 的执行逻辑

handler 在 [wait_for_environment.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/wait_for_environment.rs) 中实现。流程：

1. **解构 invocation**：取出 `payload`、`step_context`。
2. **校验 payload**：必须是 `ToolPayload::Function { .. }`，否则 `Fatal`。
3. **解析参数**：`parse_arguments::<WaitForEnvironmentArgs>`（`deny_unknown_fields`）。
4. **短路：已经就绪**：
   ```rust
   let already_ready = step_context.environments
       .turn_environments()          // 当前已就绪的环境
       .any(|env| env.environment_id == environment_id);
   if already_ready { /* 直接返回 ready */ }
   ```
5. **从 starting 中查找目标环境**：
   ```rust
   let Some(environment) = step_context.environments
       .starting()                  // 处于 starting 状态的环境
       .find(|env| env.selection.environment_id == environment_id)
   else {
       return Err("environment `{id}` is neither ready nor starting");
   };
   ```
6. **等待就绪**：`environment.wait_until_ready().await`：
   - 成功 → 环境变为 ready；
   - 失败 → 返回 `"Environment `{id}` failed to start and is unavailable. Continue without it."`（`RespondToModel`，引导模型继续）。
7. **返回**：`JsonToolOutput::new(json!({ "environment_id": id, "status": "ready" }))`。

### 数据存储在哪里

- 环境状态（`starting` / `ready`）由 `step_context.environments` 维护（`TurnEnvironmentState` 枚举）。
- 本工具不写入模型上下文，只消费环境状态并等待。

### 边界条件与错误处理

- 环境已 ready → 立即返回，不等待。
- 环境既不在 ready 也不在 starting → 报错 `"neither ready nor starting"`。
- 启动失败 → 返回引导继续的报错，不抛 Fatal。
- 等待期间阻塞其他工具调用（文档明确告知）。
- 描述超长回退默认（见第二节）。

## 四、输出格式

成功返回（`JsonToolOutput`，`output_schema: None`）：

```json
{ "environment_id": "<id>", "status": "ready" }
```

失败返回错误文本给模型（`RespondToModel`），无结构化输出。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
@Serializable
data class WaitForEnvironmentArgs(
    @SerialName("environment_id") val environmentId: String,   // 必填
)

@Serializable
data class WaitForEnvironmentResult(
    @SerialName("environment_id") val environmentId: String,
    val status: String = "ready",
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class WaitForEnvironmentTool(
    private val environments: EnvironmentManager,   // 等价 step_context.environments
) : Tool<WaitForEnvironmentArgs, WaitForEnvironmentResult>("wait_for_environment") {

    override val description: String =
        "Wait for a selected execution environment marked as `starting` to become available. ..." // 见 DEFAULT_TOOL_DESCRIPTION

    override suspend fun execute(args: WaitForEnvironmentArgs): WaitForEnvironmentResult {
        val env = args.environmentId
        // 1. 已就绪 → 直接返回
        if (environments.ready().any { it.id == env }) {
            return WaitForEnvironmentResult(env)
        }
        // 2. 从 starting 中找到目标
        val starting = environments.starting().find { it.id == env }
            ?: throw ToolException("environment `$env` is neither ready nor starting")
        // 3. 等待就绪；失败时引导继续
        if (!starting.waitUntilReady(timeout = ...)) {
            throw ToolException("Environment `$env` failed to start and is unavailable. Continue without it.")
        }
        return WaitForEnvironmentResult(env)
    }
}
```

### 5.3 执行逻辑要点

- **短路就绪**：环境已 ready 时立即返回，避免无谓等待。
- **只在 starting 中查找**：既非 ready 也非 starting 的环境报错，不要瞎等。
- **失败不 Fatal**：启动失败返回「Continue without it」式的错误文本，让模型继续，而不是中断整个 turn。
- **阻塞语义**：等待是阻塞式（同步 await），文档需明确「等待可能耗时数分钟并阻塞其他工具调用」。
- **描述大小保护**：若 Mederi 允许宿主注入描述，需做字节预算检查并回退默认（对齐 `MAX_COMBINED_DESCRIPTION_BYTES = 1024` / `MAX_SERIALIZED_TOOL_SPEC_BYTES = 1000`）。
- **是否实现取决于环境模型**：只有当 Mederi 存在「延迟启动环境」概念时本工具才有意义；若所有环境都是同步就绪，本工具可直接跳过。

### 5.4 依赖

- **EnvironmentManager**：维护环境生命周期状态（starting / ready / failed），提供 `ready()`、`starting()`、`waitUntilReady()`。等价 Codex 的 `step_context.environments` + `TurnEnvironment::wait_until_ready()`。
- **环境启动器/等待原语**：`waitUntilReady` 内部轮询或基于事件（Condition / CompletableFuture）等待环境启动完成。
- **不需要** tokenizer、HistoryStore、ContextManager。
- 若 Mederi 无「starting 环境」概念，本工具可降级为「始终 ready」空实现或直接不注册。
