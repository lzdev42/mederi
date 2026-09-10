# write_stdin（向运行中进程写入输入工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/unified_exec/write_stdin.rs`，参数 schema 在 `codex-rs/core/src/tools/handlers/shell_spec.rs`（`create_write_stdin_tool`），核心执行在 `codex-rs/core/src/unified_exec/mod.rs`（`write_stdin` 请求处理）。

## 一、功能描述

`write_stdin` 用于向一个**已存在的 unified exec 会话（运行中的进程）**写入字符并取回最近的输出。

它的设计目的：

- 配合 `exec_command` 形成「启动命令 → 持续交互」的闭环：`exec_command` 启动一个长驻进程（如交互式 CLI、REPL），返回 `session_id`；模型随后用 `write_stdin` 向该进程的 stdin 写入输入、轮询输出。
- `chars` 为空时，`write_stdin` 退化为「**后台轮询**」：不写任何输入，只等待并返回进程新输出。
- 它是唯一能与正在运行的 shell 进程交互的通道，支撑「命令需要人工输入 / 密码 / 菜单选择」等场景。

工具名常量为 `write_stdin`。

## 二、Codex 的参数定义（args schema）

工具定义在 [shell_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/shell_spec.rs#L113-L153) 的 `create_write_stdin_tool`。

| 参数名 | 类型 | 是否必填 | 默认值 | 描述 |
| --- | --- | --- | --- | --- |
| `session_id` | integer | **是** | —— | 运行中的 unified exec 会话标识（来自 `exec_command` 的返回值）。 |
| `chars` | string | 否 | `""` | 要写入 stdin 的字节。默认空 = 只轮询不写入。 |
| `yield_time_ms` | integer | 否 | 见下文 | 等待输出后再返回的时长（毫秒）。 |
| `max_output_tokens` | integer | 否 | `10000` | 输出 token 预算；更大的请求可能被策略上限截断。 |

`strict: false`，`defer_loading: None`，`required: ["session_id"]`，`additionalProperties: false`。

工具描述：`"Writes characters to an existing unified exec session and returns recent output."`

`yield_time_ms` 默认值规则（[unified_exec.rs:60-66](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/unified_exec.rs#L60-L66)）：
- 非空写入：默认 `250ms`，上限 `30000ms`（写完后稍等再取输出）。
- 空轮询：默认 `5000ms`，上限 `300000ms`（纯等待输出）。

（注：默认值由 `default_write_stdin_yield_time_ms()` 返回 250，实际生效值由 unified_exec 管理器按「写入 vs 轮询」分别解释；文档描述里写明了两类上限。）

### 输出 schema（output_schema）

与 `exec_command` 共用 `unified_exec_output_schema()`（[shell_spec.rs:264-296](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/shell_spec.rs#L264-L296)）：

```jsonc
{
  "type": "object",
  "properties": {
    "chunk_id":           { "type": "string" },
    "wall_time_seconds":  { "type": "number" },
    "exit_code":          { "type": "number" },
    "session_id":         { "type": "number" },
    "original_token_count": { "type": "number" },
    "output":             { "type": "string" }
  },
  "required": ["wall_time_seconds", "output"],
  "additionalProperties": false
}
```

关键字段：`session_id`（进程仍在运行时，用它继续调用 `write_stdin`）、`exit_code`（本次调用期间进程结束）、`output`（命令输出文本，可能被截断）。

## 三、Codex 的执行逻辑

handler 在 [write_stdin.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/unified_exec/write_stdin.rs) 中实现。流程：

1. **解构 invocation**：取出 `session`、`turn`、`payload`。
2. **校验 payload**：必须是 `ToolPayload::Function { .. }`，否则返回 `"write_stdin handler received unsupported payload"`。
3. **解析参数**：`parse_arguments::<WriteStdinArgs>(&arguments)`；`session_id` 必填，其余有 serde default。
4. **调用 unified_exec 管理器**：
   ```rust
   session.services.unified_exec_manager.write_stdin(WriteStdinRequest {
       process_id: args.session_id,
       input: &args.chars,
       yield_time_ms: args.yield_time_ms,
       max_output_tokens: args.max_output_tokens,
       truncation_policy: turn.model_info.truncation_policy.into(),
       interaction_event: Some(WriteStdinInteractionEvent { session: &session, turn: &turn }),
   }).await
   ```
   - 管理器负责：校验 session 存在 → 写入 stdin（若 `input` 非空）→ 等待 `yield_time_ms` → 按 `max_output_tokens` 截断 → 返回最近输出。
   - 失败时返回 `"write_stdin failed: {err}"`（`RespondToModel`）。
5. **返回**：把管理器返回的 `ExecToolCallOutput` 装箱为 `ToolOutput`。

### 与 exec 生命周期的配合

`write_stdin` 实现了 `CoreToolRuntime` 的两个扩展回调：

- **`matches_kind`**：只匹配 `Function` payload。
- **`pre_tool_use_payload`**：返回 `None`。原因：`write_stdin` 是既有 exec 会话的传输通道——空写入是后台轮询、非空写入是延续一条已经以 Bash 身份跑过 `PreToolUse` 的命令，因此不在此处重复触发 pre hook。
- **`post_tool_use_payload`**：调用 `post_unified_exec_tool_use_payload`。原因：一次 `write_stdin` 轮询可能观察到原始 `exec_command` 的最终完成，此时需要发出那条命令对应的 Bash `PostToolUse`。

### 数据存储在哪里

- **进程会话**：unified_exec 管理器维护 `session_id → 运行中进程` 的映射，`write_stdin` 只读写该会话。
- 不把进程输出写回模型上下文——输出以工具调用结果形式返回给模型。

### 边界条件与错误处理

- `session_id` 对应的进程不存在/已结束 → 管理器报错，`"write_stdin failed: ..."`。
- 空 `chars` + 长 `yield_time_ms` = 纯轮询，不产生写入副作用。
- 输出超过 `max_output_tokens` → 截断，`original_token_count` 记录截断前的 token 数。
- 支持并行调用（`supports_parallel_tool_calls() == true`），可同时轮询多个会话。

## 四、输出格式

返回 `ExecToolCallOutput` 渲染为给模型的文本（通常形如）：
- 进程仍运行：输出片段文本，含 `session_id` 供继续调用。
- 进程结束：输出 + `exit_code`。
- code 模式：按 `unified_exec_output_schema()` 返回结构化对象。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
@Serializable
data class WriteStdinArgs(
    @SerialName("session_id") val sessionId: Int,       // 必填
    @SerialName("chars") val chars: String = "",
    @SerialName("yield_time_ms") val yieldTimeMs: Long? = null,   // null 时按写入/轮询取默认
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,  // 默认 10000
)

@Serializable
data class UnifiedExecResult(
    @SerialName("chunk_id") val chunkId: String? = null,
    @SerialName("wall_time_seconds") val wallTimeSeconds: Double,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("session_id") val sessionId: Int? = null,
    @SerialName("original_token_count") val originalTokenCount: Int? = null,
    val output: String,
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class WriteStdinTool(
    private val processManager: ProcessManager,   // 等价 unified_exec_manager
    private val tokenEstimator: (String) -> Int,  // 按模型估算 token，用于截断
) : Tool<WriteStdinArgs, UnifiedExecResult>("write_stdin") {

    override val description: String =
        "Writes characters to an existing unified exec session and returns recent output."

    override suspend fun execute(args: WriteStdinArgs): UnifiedExecResult {
        val session = processManager.find(args.sessionId)
            ?: throw ToolException("write_stdin failed: no session ${args.sessionId}")
        val yieldMs = args.yieldTimeMs ?: if (args.chars.isNotEmpty()) 250 else 5000
        val maxTokens = args.maxOutputTokens ?: 10_000
        // 1. 非空则写 stdin
        if (args.chars.isNotEmpty()) session.writeStdin(args.chars)
        // 2. 等待 yieldMs 收集新输出
        val output = session.collectOutput(yieldMs.coerceAtMost(if (args.chars.isNotEmpty()) 30_000 else 300_000))
        // 3. 按 token 预算截断
        val (truncated, originalCount) = truncateByTokens(output, maxTokens, tokenEstimator)
        return UnifiedExecResult(
            wallTimeSeconds = ...,
            exitCode = session.exitCode,
            sessionId = if (session.isRunning) session.id else null,
            originalTokenCount = originalCount,
            output = truncated,
        )
    }
}
```

### 5.3 执行逻辑要点

- **依赖 `exec_command` 建立的会话**：`session_id` 必须来自一次成功的 `exec_command`（或历史上 `write_stdin` 返回的 `session_id`）。
- **空 chars = 轮询**：`chars` 为空时不写 stdin，仅等待并返回新输出——这是「观察后台任务进度」的标准用法。
- **yield_time 分两类默认**：非空写入默认 250ms（上限 30s）；空轮询默认 5s（上限 300s）。若 `args.yieldTimeMs` 显式传入，直接使用并按其类别夹取上限。
- **token 截断**：输出按 `max_output_tokens` 截断，`original_token_count` 记录截断前估算值。
- **进程结束信号**：当观察到 `exit_code` 时，应结束该会话的生命周期管理（等价 unified_exec 里最终 `PostToolUse` 语义）。
- **进程交互安全性**：写入内容属于用户授权范围内（已由 `exec_command` 的 pre-hook 授权）；Mederi 若无 hook 体系，可省略 pre/post hook，仅保留会话授权校验。

### 5.4 依赖

- **ProcessManager（unified_exec 管理器）**：维护 `session_id → 运行中进程` 的映射，提供 `writeStdin`、`collectOutput`、`isRunning`、`exitCode` 等能力。这是本工具的核心依赖，Mederi 需要先具备「常驻进程会话」基础设施（若暂无，本工具应排在 exec 基础设施之后）。
- **Tokenizer / 估算函数**：输出 token 截断用。
- **不需要** HistoryStore、ContextManager——进程输出不写入模型上下文，只作为工具结果返回。
- 若 Koog 已有 shell/exec 能力支持常驻会话，直接在其上扩展 `write_stdin`；否则该工具依赖 exec 基础设施先行落地。
