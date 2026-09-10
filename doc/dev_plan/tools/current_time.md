# current_time（当前时间查询工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/current_time.rs`，时间格式化在 `codex-rs/core/src/context/current_time_reminder.rs`。
> 注意：Codex 中该工具在 `clock` 命名空间下，完整工具名是 `clock.curr_time`（不是裸 `current_time`），描述与返回格式沿用 Codex 原文。

## 一、功能描述

`curr_time` 让模型查询「当前 UTC 时间」。

它的设计目的：

- 在长任务或需要判断时效性（如日期、调度、日志时间戳）的场景，模型需要精确的当前时间，而不是训练截止时间。
- 它是只读、无副作用、无参数的工具，任何时刻调用都能得到当前时间。
- 通过 `time_provider` 注入时钟源，便于测试时固定时间（`current_time(thread_id)`），而非直接调用系统时钟。

命名空间为 `clock`，工具名为 `curr_time`，因此模型看到的是 `clock.curr_time`。

## 二、Codex 的参数定义（args schema）

工具定义在 [current_time.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/current_time.rs) 中，通过 `ToolSpec::Namespace` 包装：

```jsonc
{
  "type": "object",
  "properties": {},          // 无任何参数
  "required": null,          // 不要求任何字段
  "additionalProperties": false
}
```

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| —— | —— | —— | 该工具不接受任何参数。 |

`strict: false`，`defer_loading: None`。

工具描述：
- 命名空间描述：`"Tools for reading and waiting on time."`
- 工具描述：`"Return the current time in UTC."`

### 输出 schema（output_schema）

```jsonc
{
  "type": "object",
  "properties": {
    "current_time": {
      "type": "string",
      "description": "Current UTC time formatted as YYYY-MM-DD HH:MM:SS UTC."
    }
  },
  "required": ["current_time"],
  "additionalProperties": false
}
```

即结构化结果只包含 `current_time: string`。

## 三、Codex 的执行逻辑

handler 在 [current_time.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/current_time.rs) 中实现。流程：

1. **校验 payload**：必须是 `ToolPayload::Function { .. }`，否则返回 `"curr_time handler received unsupported payload"`。
2. **读取当前时间**：
   ```rust
   let current_time = invocation.session.services.time_provider
       .current_time(invocation.session.thread_id).await?;
   ```
   - `time_provider` 是注入的服务，`current_time(thread_id)` 返回 `DateTime<Utc>`；测试时可替换为固定时钟。
   - 读取失败时返回 `FunctionCallError::Fatal("failed to read current time: ...")`。
3. **构造输出**：`CurrentTimeReminder::new(current_time)`，交给 `CurrentTimeOutput` 渲染。

### 时间格式化（CurrentTimeReminder）

定义在 [current_time_reminder.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/context/current_time_reminder.rs)：

```rust
pub(crate) fn formatted_time(&self) -> String {
    self.current_time.format("%Y-%m-%d %H:%M:%S UTC").to_string()
}
```

该结构体还实现了 `ContextualUserFragment`（role = developer），`body()` 为 `"It is {formatted_time}."`。注意：`curr_time` 工具调用时**不是**作为独立 context fragment 注入，而是复用 `render()`/`formatted_time()` 的文本作为工具调用输出。

### 数据存储在哪里

- 不写入任何状态，纯读取。
- 读取来源：`services.time_provider`（可注入、可固定）。

### 边界条件与错误处理

- payload 类型不匹配 → 立即返回错误。
- time_provider 读取失败 → 返回 `Fatal` 错误。
- 无参数，无需参数校验。

## 四、输出格式

`CurrentTimeOutput` 实现了 `ToolOutput`：

- **`to_response_item`**：`FunctionToolOutput::from_text(self.0.render(), Some(true))`。
  - `render()` = `CurrentTimeReminder::body()` = `"It is {formatted_time}."`
  - 示例：`It is 2026-08-14 09:30:00 UTC.`
- **`code_mode_result`**：`{"current_time": "<formatted_time>"}`（与 `output_schema` 一致，格式为 `YYYY-MM-DD HH:MM:SS UTC`）。
- **`log_preview`**：同 `render()`。
- **`success_for_logging`**：恒为 `true`。

因此：
- **文本模式**（给 LLM）：`It is 2026-08-14 09:30:00 UTC.`
- **code 模式**：`{"current_time": "2026-08-14 09:30:00 UTC"}`。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
// 无参数，可用 Unit 或空对象
object CurrentTimeArgs

@Serializable
data class CurrentTimeResult(
    @SerialName("current_time") val currentTime: String,   // "YYYY-MM-DD HH:MM:SS UTC"
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class CurrentTimeTool(
    private val clock: Clock,          // 注入时钟源，测试时固定
) : Tool<CurrentTimeArgs, CurrentTimeResult>("curr_time") {

    override val description: String = "Return the current time in UTC."

    override suspend fun execute(args: CurrentTimeArgs): CurrentTimeResult {
        val now = clock.now()                              // 取 UTC 时间
        val formatted = now.format("yyyy-MM-dd HH:mm:ss 'UTC'")
        return CurrentTimeResult(formatted)
    }
}
```

> 命名说明：工具名建议与 Codex 保持一致使用 `curr_time`（若 Mederi 不打算引入 `clock` 命名空间，也可以用裸 `current_time`，但需要在计划文档里确认；默认跟随 Codex 用 `clock.curr_time` 语义或直接命名为 `current_time` 均可，二选一并全局统一）。

### 5.3 执行逻辑要点

- **纯只读、无副作用**：不要在 `execute` 里修改任何状态。
- **时钟注入**：不要直接调用 `LocalDateTime.now()`，通过 `Clock` 接口注入，便于单元测试固定时间（对齐 Codex 的 `time_provider`）。
- **必须用 UTC**：格式为 `YYYY-MM-DD HH:MM:SS UTC`，与 Codex 一致。
- **输出双形态**：
  - 给 LLM 的文本：`"It is {formatted}."`。
  - 结构化（code 模式）：`{"current_time": "<formatted>"}`，声明与 Codex 一致的 `output_schema`。

### 5.4 依赖

- **Clock / 时间提供者**：抽象时钟接口（等价 `time_provider`），生产实现返回系统 UTC 时间，测试实现返回固定时间。
- **不需要** Session、HistoryStore、tokenizer、ContextManager——该工具不读写上下文。
- 若 Kotlin 端用 `java.time.Clock`，直接可用 `Clock.systemUTC()` 作为默认实现。
