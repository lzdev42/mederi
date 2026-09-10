# get_context_remaining（上下文余量查询工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/get_context_remaining_spec.rs` 与 `get_context_remaining.rs`，核心计算逻辑在 `codex-rs/core/src/session/context_window.rs`，渲染片段在 `codex-rs/core/src/context/token_budget_context.rs`。

## 一、功能描述

`get_context_remaining` 让模型主动查询「当前上下文窗口还剩多少 token 可用」。

它的设计目的：

- 在长任务中，模型需要判断是否还有足够空间继续产出、是否应该先收尾再开新窗口。该工具把 token 预算的剩余量以**可读文本**形式回传给模型，辅助其做调度决策。
- 它是只读、无副作用的查询工具，不改变任何状态。
- 工具名常量为 `GET_CONTEXT_REMAINING_TOOL_NAME = "get_context_remaining"`。

返回的数值本质上是 `ContextWindowTokenStatus.base_window_tokens_remaining`（见第三节），并以自然语言片段 `"You have {n} tokens left in this context window."` 形式回传。

## 二、Codex 的参数定义（args schema）

工具定义在 [get_context_remaining_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/get_context_remaining_spec.rs)。

顶层对象：**无任何参数**。

```jsonc
{
  "type": "object",
  "properties": {},
  // required: 无（传 None）
  "additionalProperties": false
}
```

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| —— | —— | —— | 该工具不接受任何参数。 |

`strict: false`，`defer_loading: None`。

工具描述：`"Get the remaining tokens in the current context window."`

### 输出 schema（output_schema）

与 `update_plan` 不同，本工具显式声明了 `output_schema`：

```jsonc
{
  "type": "object",
  "properties": {
    "tokens_left": {
      "anyOf": [ { "type": "integer" }, { "type": "null" } ],
      "description": "Remaining tokens in the current context window, or null when unavailable."
    }
  },
  "required": ["tokens_left"],
  "additionalProperties": false
}
```

即结构化结果只包含一个字段 `tokens_left: integer | null`。

## 三、Codex 的执行逻辑

handler 在 [get_context_remaining.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/get_context_remaining.rs) 中以匿名 async 块实现。流程：

1. **校验 payload**：`invocation.payload` 必须是 `ToolPayload::Function { .. }`，否则返回 `"get_context_remaining handler received unsupported payload"`。
2. **计算 token 状态**：调用
   ```rust
   let token_status = context_window_token_status(
       invocation.session.as_ref(),
       invocation.turn.as_ref(),
   ).await;
   ```
   该函数定义在 [context_window.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/context_window.rs)。
3. **取剩余值**：`token_status.base_window_tokens_remaining`（`Option<i64>`），构造 `GetContextRemainingOutput::new(...)`。
4. **返回**：`Ok(boxed_tool_output(GetContextRemainingOutput { tokens_left }))`。

### `context_window_token_status` 的计算细节

`ContextWindowTokenStatus` 字段（[context_window.rs:6-17](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/context_window.rs#L6-L17)）：

| 字段 | 含义 |
| --- | --- |
| `active_context_tokens` | 当前活跃上下文的完整 token 用量（`sess.get_total_token_usage().await`）。 |
| `auto_compact_scope_tokens` | 计入 auto-compact 作用域的 token 用量，取决于 `AutoCompactTokenLimitScope`。 |
| `auto_compact_scope_limit` | auto-compact 作用域的 token 上限。 |
| `full_context_window_limit` | 模型的完整上下文窗口硬上限（`turn_context.model_context_window()`）。 |
| `base_window_tokens_remaining` | **本工具返回的字段**：基础（未加 buffer 的）窗口剩余 token。 |
| `auto_compact_window_prefill_tokens` | 当前窗口的 prefill 基线 token。 |
| `full_context_window_limit_reached` | 是否已达模型完整窗口上限。 |
| `token_limit_reached` | 是否已达（含 fallback buffer 的）触发压缩阈值。 |

`base_window_tokens_remaining` 的算法（[context_window.rs:57-63](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/context_window.rs#L57-L63)）：

```rust
let base_window_tokens_remaining = [
    tokens_remaining(auto_compact_scope_limit, auto_compact_scope_tokens),
    tokens_remaining(full_context_window_limit, active_context_tokens),
]
.into_iter()
.flatten()
.min();
```

其中 `tokens_remaining(limit, used) = limit.map(|l| l.saturating_sub(used).max(0))`。

含义：取「auto-compact 作用域剩余」与「模型完整窗口剩余」两者中的**较小值**；若两者都为 `None` 则结果为 `None`（即不可用）。这保证返回的是最紧约束下的剩余量，且永远不会为负（用 `max(0)` 钳制）。

`auto_compact_scope_tokens` 的取值依作用域而异：
- `AutoCompactTokenLimitScope::Total`：等于 `active_context_tokens`，上限取 `model_info.auto_compact_token_limit()`。
- `AutoCompactTokenLimitScope::BodyAfterPrefix`：等于 `active_context_tokens - prefill基线`（即窗口打开后新增的 body token），上限优先取配置 `model_auto_compact_token_limit`，否则回退到 `model_info.auto_compact_token_limit()`。

### 数据存储在哪里

- 本工具**不写入任何状态**，纯读取。
- 读取来源：`Session` 的 token 用量统计（`get_total_token_usage`）、`auto_compact_window_snapshot`（窗口 prefill 基线）、`TurnContext` 中的模型信息与配置（上下文窗口上限、auto-compact 上限、作用域、token 预算）。

### 输出渲染

`GetContextRemainingOutput::fragment()` 使用 `TokenBudgetRemainingContext`（[token_budget_context.rs:124-162](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/context/token_budget_context.rs#L124-L162)）：

- 有值：`"You have {tokens_left} tokens left in this context window."`
- `None`：`"You have unknown tokens left in this context window."`

注意：`TokenBudgetRemainingContext` 实现了 `ContextualUserFragment`（role = developer），但本工具的 `to_response_item` 是把它作为**工具调用输出**返回的，并非作为独立 context fragment 注入——它只是复用了 `body()` 的渲染文本。

### 边界条件与错误处理

- payload 类型不匹配 → 立即返回错误。
- 任何上限未知（`None`）时，`tokens_left` 为 `None`，渲染为「unknown」，工具本身不报错，`success_for_logging` 仍为 `true`。
- 用量超上限时，剩余量被钳制为 `0` 而非负数。

## 四、输出格式

`GetContextRemainingOutput` 实现了 `ToolOutput`：

- **`to_response_item`**：通过 `FunctionToolOutput::from_text(fragment, Some(true))` 构造，即返回给模型的文本是上述自然语言片段，`success = true`。
  - 有值示例：`You have 12345 tokens left in this context window.`
  - 未知示例：`You have unknown tokens left in this context window.`
- **`code_mode_result`**：JSON 对象 `{"tokens_left": <int|null>}`，与 `output_schema` 一致。
- **`log_preview`**：同 `fragment()`。
- **`success_for_logging`**：恒为 `true`。

因此：
- **文本模式**（给 LLM）：自然语言片段。
- **code 模式**：`{"tokens_left": ...}`。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
// 无参数，可用 Unit 或空对象
object GetContextRemainingArgs

@Serializable
data class GetContextRemainingResult(
    @SerialName("tokens_left") val tokensLeft: Long? = null,
)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class GetContextRemainingTool(
    private val session: Session,
    private val tokenizer: Tokenizer,            // 估算 token 用量
    private val modelContextWindowLimit: () -> Long?,
    private val autoCompactScopeLimit: () -> Long?,
) : Tool<GetContextRemainingArgs, GetContextRemainingResult>("get_context_remaining") {

    override val description: String =
        "Get the remaining tokens in the current context window."

    override suspend fun execute(args: GetContextRemainingArgs): GetContextRemainingResult {
        val active = session.getTotalTokenUsage()           // 当前上下文总用量
        val fullLimit = modelContextWindowLimit()           // 模型完整窗口上限
        val scopeLimit = autoCompactScopeLimit()            // auto-compact 作用域上限
        val scopeTokens = active                            // 简化：Total 作用域；若支持 BodyAfterPrefix 需减去 prefill 基线

        val remaining = listOfNotNull(
            scopeLimit?.let { (it - scopeTokens).coerceAtLeast(0) },
            fullLimit?.let { (it - active).coerceAtLeast(0) },
        ).minOrNull()                                       // 取较小值；都为 null 则 null

        return GetContextRemainingResult(tokensLeft = remaining)
    }
}
```

### 5.3 执行逻辑要点

- **纯只读、无副作用**：不要在 `execute` 里修改 session 状态。
- **剩余量取最小约束**：仿照 Codex，同时考虑 auto-compact 作用域上限与模型完整窗口上限，取较小者，保证返回的是最紧约束下的可用空间。
- **钳制非负**：用 `coerceAtLeast(0)`，避免返回负数。
- **未知时返回 null**：上限不可得时返回 `null`，并渲染为「unknown」文本。
- **输出双形态**：
  - 给 LLM 的文本：`"You have {n} tokens left in this context window."`（n 为 null 时用 `"unknown"`）。
  - 结构化（code 模式）：`{"tokens_left": <long|null>}`，需声明与 Codex 一致的 `output_schema`。
- **作用域支持**：若 Mederi 要支持 `BodyAfterPrefix` 语义，需维护「窗口 prefill 基线」，`scopeTokens = active - prefillBaseline`；初版可只实现 `Total` 作用域以简化。

### 5.4 依赖

- **Session**：提供 `getTotalTokenUsage()`（等价 `sess.get_total_token_usage()`）。
- **Tokenizer**：若需要本地估算 token 用量（而非完全依赖服务端 usage 回传），需要 tokenizer；若直接信任 provider 返回的 `usage.input_tokens`，则可不强依赖 tokenizer。
- **TurnContext / 配置**：提供 `model_context_window()`（模型完整窗口上限）、`auto_compact_token_limit`（作用域上限）、`AutoCompactTokenLimitScope`（作用域模式）。
- **不需要** HistoryStore（除非 token 用量来自历史累计）；不需要写入上下文的任何组件。
- **可选**：`AutoCompactWindowSnapshot`（窗口 prefill 基线），仅在实现 `BodyAfterPrefix` 作用域时需要。
