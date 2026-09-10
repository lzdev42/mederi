# new_context_window（开新上下文窗口工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/new_context_window_spec.rs` 与 `new_context_window.rs`；请求的消费链路在 `codex-rs/core/src/session/mod.rs`、`codex-rs/core/src/state/session.rs`、`codex-rs/core/src/state/auto_compact_window.rs`。

## 一、功能描述

`new_context_window`（工具名常量 `NEW_CONTEXT_WINDOW_TOOL_NAME = "new_context"`）让模型**主动请求开启一个新的上下文窗口**。

它的设计目的：

- 当模型判断当前窗口已接近耗尽、或某个阶段任务告一段落时，可以主动触发「开窗」，避免在拥挤的上下文里继续推理导致质量下降。
- 关键语义：开新窗口**不会**对历史做摘要、**不会**清除或重置环境状态（文件系统、工作目录、进程等一概不变），仅仅是让后续推理从一个全新的、只含初始上下文（系统提示 + 环境信息等）的窗口重新开始。
- 工具描述原文：`"Start a new context window. Does not clear, reset, or otherwise affect environment state."`

重要：handler 只是「请求」开窗，真正开窗动作由主循环在下一个调度点消费该请求时执行（见第三节）。工具调用本身立即返回成功文本。

## 二、Codex 的参数定义（args schema）

工具定义在 [new_context_window_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/new_context_window_spec.rs)。

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

`strict: false`，`defer_loading: None`，`output_schema: None`。

## 三、Codex 的执行逻辑

handler 在 [new_context_window.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/new_context_window.rs) 中以匿名 async 块实现。流程极简：

1. **校验 payload**：`invocation.payload` 必须是 `ToolPayload::Function { .. }`，否则返回 `"new_context handler received unsupported payload"`。
2. **发起请求**：`invocation.session.request_new_context_window().await`。
3. **返回成功**：返回 `FunctionToolOutput::from_text(NEW_CONTEXT_WINDOW_MESSAGE, Some(true))`。

其中常量：

```rust
const NEW_CONTEXT_WINDOW_MESSAGE: &str =
    "A new context window will start without summarizing conversation history.";
```

### `request_new_context_window` 的内部链路

1. [session/mod.rs:3683](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/mod.rs#L3683) `Session::request_new_context_window` 拿锁后委托给 `state.request_new_context_window()`。
2. [state/session.rs:207](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/state/session.rs#L207) 再委托给 `self.auto_compact_window.request_new_context_window()`。
3. [auto_compact_window.rs:95](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/state/auto_compact_window.rs#L95)：仅置位标志 `self.new_context_window_requested = true`。

即：handler 这一步**只设置一个布尔标志**，不立即开窗。

### 请求的消费（实际开窗）

主循环在调度点调用 `Session::take_new_context_window_request()`（[session/mod.rs:3688](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/mod.rs#L3688)）：

- [auto_compact_window.rs:99](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/state/auto_compact_window.rs#L99)：读取并清零 `new_context_window_requested`，返回是否曾请求。

若为 true，则执行 `Session::start_new_context_window`（[session/mod.rs:3693](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/session/mod.rs#L3693)）：

- [state/session.rs:215](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/state/session.rs#L215)：`auto_compact_window.advance()` + `clear_prefill()`。
- [auto_compact_window.rs:77](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/state/auto_compact_window.rs#L77) `advance()`：
  - `window_number` 自增（`saturating_add(1)`）。
  - `ids.previous_window_id = Some(old window_id)`。
  - `ids.window_id = Uuid::now_v7()`（生成新窗口 UUID）。
  - 重置三个标志：`new_context_window_requested = false`、`token_budget_reminder_delivered = false`、`auto_compact_fallback_delivered = false`。
- 之后 `Session` 重建初始上下文（`build_initial_context_with_world_state`），用 `replace_compacted_history` 替换历史——即旧对话不再进入新窗口的可见上下文，但**不生成摘要**。

### 数据存储在哪里

- 请求标志存储在 `AutoCompactWindow` 状态机（内存态，session-level），不在模型可见上下文里。
- 窗口编号、窗口 UUID（current / previous / first）同样存储在 `AutoCompactWindow`。
- 这些信息通过 `TokenBudgetContext`（[token_budget_context.rs:12-36](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/context/token_budget_context.rs#L12-L36)）作为 `ContextualUserFragment`（developer 角色）注入到新窗口的初始上下文中，告知模型当前是第几个窗口、窗口 ID 等。

### 边界条件与错误处理

- payload 类型不匹配 → 立即返回错误，不设置请求标志。
- 重复请求：连续多次调用只会在标志位上体现为 `true`，`take_new_context_window_request` 消费时合并为一次开窗（幂等语义）。
- handler 不等待实际开窗完成，立即返回；实际开窗时机的早晚不影响工具调用的成功返回。
- 工具不校验「是否真的需要开窗」，完全信任模型决策。

## 四、输出格式

`FunctionToolOutput::from_text(NEW_CONTEXT_WINDOW_MESSAGE, Some(true))`：

- **返回给模型的文本**（固定）：
  ```
  A new context window will start without summarizing conversation history.
  ```
- **success**：`true`。
- 无 `output_schema`，无结构化 JSON 输出。
- `code_mode_result` 未在该 handler 显式实现，沿用 `FunctionToolOutput` 的默认行为（文本透传）。

## 五、Mederi 实现建议

### 5.1 Args / Result 数据类

```kotlin
object NewContextWindowArgs   // 无参数

data class NewContextWindowResult(val requested: Boolean = true)
```

### 5.2 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class NewContextWindowTool(
    private val session: Session,
) : Tool<NewContextWindowArgs, NewContextWindowResult>("new_context") {

    override val description: String =
        "Start a new context window. Does not clear, reset, or otherwise affect environment state."

    override suspend fun execute(args: NewContextWindowArgs): NewContextWindowResult {
        // 1. 仅设置「请求开窗」标志，不立即开窗
        session.requestNewContextWindow()
        // 2. 立即返回成功文本
        return NewContextWindowResult(requested = true)
    }
}
```

向 LLM 输出的文本固定为：
```
A new context window will start without summarizing conversation history.
```

### 5.3 执行逻辑要点

- **请求 / 消费分离**：`execute` 只置位一个 `newContextWindowRequested` 标志，真正开窗由主循环（或调度器）在下一个安全调度点检查并执行。这避免了在工具调用执行过程中重建上下文带来的复杂并发问题。
- **不摘要、不清环境**：开窗时丢弃旧对话历史，不生成摘要；环境状态（文件、工作目录、进程、工具结果缓存等）保持不变。需要在 Mederi 的 `ContextManager` / `HistoryStore` 上实现「替换历史为初始上下文」的等价操作。
- **窗口元信息**：维护窗口编号、当前/上一个/首个窗口 ID（可用 UUIDv7）。新窗口启动时把这些信息作为 developer 角色的初始上下文片段注入（等价 `TokenBudgetContext`），让模型知道自己在第几个窗口。
- **标志重置**：开窗时重置「token 预算提醒已送达」「fallback 已送达」等一次性标志，确保新窗口会重新触发相关提醒逻辑。
- **幂等**：多次请求合并为一次，`takeNewContextWindowRequest` 消费后清零。
- **不校验必要性**：信任模型决策，不做「是否真的需要开窗」的判断。

### 5.4 依赖

- **Session**：提供 `requestNewContextWindow()`、`takeNewContextWindowRequest()`、`startNewContextWindow()` 等方法；持有 `AutoCompactWindow` 状态机。
- **ContextManager / HistoryStore**：开窗时需要重建初始上下文并替换历史（等价 `build_initial_context_with_world_state` + `replace_compacted_history`）。
- **主循环 / 调度器**：需要一个调度点轮询 `takeNewContextWindowRequest()` 并触发实际开窗。
- **UUID 生成**：为新窗口分配 ID（建议 UUIDv7，与 Codex 一致，带时间序）。
- **不需要** Tokenizer（开窗动作本身不计算 token）；但开窗后若要注入 `TokenBudgetContext`，可能需要读取窗口 prefill 基线等 token 相关信息。
- **可选**：`AutoCompactWindow` 状态对象，封装窗口编号、IDs、各标志位，作为 Mederi 的 `AutoCompactWindow` 等价物。
