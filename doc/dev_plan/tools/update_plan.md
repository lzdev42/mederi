# update_plan（计划管理工具）开发文档

> 源码出处：Codex `codex-rs/core/src/tools/handlers/plan_spec.rs` 与 `plan.rs`，参数类型定义于 `codex-rs/protocol/src/plan_tool.rs`。

## 一、功能描述

`update_plan` 是 Codex 提供给模型的「待办清单 / checklist」工具，用于在长任务执行过程中维护一份结构化的步骤计划。

它的设计目的：

- 让模型在多步骤任务中显式地声明「当前要做什么、做到哪一步、哪些已完成」，从而提升长程任务的可追踪性与自我组织能力。
- 把计划状态以**事件（event）**的形式从模型上下文中分离出去，由前端（TUI / app-server）独立渲染展示，同时写入 rollout 持久化记录。
- 它**不是** Plan 模式（`ModeKind::Plan`）里的规划工具——恰恰相反，在 Plan 模式下该工具被明确禁止调用。

需要强调的一点是：`update_plan` 产生的计划数据**不会**被注入回模型的可见上下文（不是 `ContextualUserFragment`）。它只是一个旁路通道：模型调用工具 → handler 发出 `PlanUpdate` 事件 → UI/rollout 消费。模型下一次看到的信息仅仅是工具返回的固定文本 `"Plan updated"`。

## 二、Codex 的参数定义（args schema）

工具定义在 [plan_spec.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/plan_spec.rs) 中通过 `JsonSchema` 手工构建，对应的 Rust 反序列化类型在 [plan_tool.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/protocol/src/plan_tool.rs) 中为 `UpdatePlanArgs`。

顶层对象（`additionalProperties: false`，`required: ["plan"]`）：

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| `explanation` | string | 否 | 对本次计划更新的可选解释说明。 |
| `plan` | array&lt;PlanItem&gt; | **是** | 计划步骤列表，整体替换当前计划。 |

`PlanItem`（对象，`additionalProperties: false`，`required: ["step", "status"]`）：

| 参数名 | 类型 | 是否必填 | 描述 |
| --- | --- | --- | --- |
| `step` | string | 是 | 步骤文本内容。 |
| `status` | string enum | 是 | 步骤状态，取值：`pending` / `in_progress` / `completed`。 |

工具描述（description）原文：

```
Updates the task plan.
Provide an optional explanation and a list of plan items, each with a step and status.
At most one step can be in_progress at a time.
```

说明：`strict: false`，即不强制严格 schema 校验。Rust 端 `UpdatePlanArgs` 使用 `#[serde(deny_unknown_fields)]`，反序列化时会拒绝未知字段。`explanation` 标注了 `#[serde(default)]`，因此可省略。

注意：schema 描述里写明「At most one step can be in_progress at a time」，但这一约束**并未在 handler 中做校验**——它依赖模型自觉遵守；handler 只做解析与事件转发。

## 三、Codex 的执行逻辑

handler 实现在 [plan.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/core/src/tools/handlers/plan.rs) 的 `PlanHandler::handle_call` 中。流程如下：

1. **解构 invocation**：从 `ToolInvocation` 取出 `session`、`turn`、`payload`，忽略 `call_id`。
2. **校验 payload 类型**：`payload` 必须是 `ToolPayload::Function { arguments }`，否则返回 `FunctionCallError::RespondToModel("update_plan handler received unsupported payload")`。
3. **模式校验**：若 `turn.mode == ModeKind::Plan`，直接报错 `"update_plan is a TODO/checklist tool and is not allowed in Plan mode"`。这是唯一的运行期业务校验。
4. **解析参数**：调用 `parse_update_plan_arguments(&arguments)`，用 `serde_json::from_str::<UpdatePlanArgs>` 把 JSON 字符串解析为强类型。解析失败时返回 `"failed to parse function arguments: {e}"`。
5. **发出事件**：`session.send_event(turn.as_ref(), EventMsg::PlanUpdate(args)).await`。`EventMsg::PlanUpdate(UpdatePlanArgs)` 定义在 [protocol.rs:1445](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/protocol/src/protocol.rs#L1445)。
6. **返回结果**：返回 `PlanToolOutput`（空结构体），它由 `ToolOutput` trait 渲染为固定文本 `"Plan updated"`。

### 数据存储在哪里

- **不在模型上下文里**：`PlanUpdate` 是一个 `EventMsg`，被分发到事件总线，不会作为 `ContextualUserFragment` 注入回模型可见上下文。
- **UI 侧**：TUI 通过 [plans.rs](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/tui/src/history_cell/plans.rs) 的 `PlanUpdateCell` 渲染为历史记录单元；app-server 在 [bespoke_event_handling.rs:1211](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/app-server/src/bespoke_event_handling.rs#L1211) 将其转换为 `TurnPlanUpdatedNotification`（`turn/plan/updated`）下发给客户端。
- **持久化**：rollout 策略 [policy.rs:167](file:///Users/liuzhe/Projects/mederi/codex-main/codex-rs/rollout/src/policy.rs#L167) 将 `PlanUpdate` 事件写入 rollout 文件。

也就是说，计划数据是**整体替换**式的：每次调用都用新的 `plan` 数组完全覆盖之前的计划，handler 内部不维护增量 diff，也不存储「上一次计划」。

### 边界条件与错误处理

- payload 类型不匹配 → 立即返回错误，不解析。
- Plan 模式下调用 → 立即返回错误，不发出事件。
- JSON 解析失败（字段缺失、类型不符、出现未知字段） → 返回解析错误信息给模型。
- 空的 `plan` 数组在 schema 层面是允许的（`required` 只要求 `plan` 存在，未要求非空），handler 不额外校验。
- 「至多一个 `in_progress`」的约束不做强制校验。

## 四、输出格式

`PlanToolOutput` 实现了 `ToolOutput` trait：

- **`to_response_item`**：构造 `ResponseInputItem::FunctionCallOutput`，`output` 为 `FunctionCallOutputPayload::from_text("Plan updated")`，并设置 `success = Some(true)`。即返回给模型的文本就是固定字符串 `"Plan updated"`，无 JSON 结构。
- **`log_preview`**：`"Plan updated"`。
- **`code_mode_result`**：空 JSON 对象 `{}`（code 模式下不返回业务数据）。
- **`success_for_logging`**：`true`。

因此模型看到的成功响应恒为：

```
Plan updated
```

无失败时的结构化输出 schema（`output_schema: None`）。

## 五、Mederi 实现建议

用 Kotlin + Koog 实现一个等价的 `UpdatePlanTool`，要点如下。

### 5.1 Args 数据类

```kotlin
@Serializable
data class PlanItemArg(
    val step: String,
    val status: StepStatus,
)

@Serializable
enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED } // wire: pending / in_progress / completed

@Serializable
data class UpdatePlanArgs(
    val explanation: String? = null,
    val plan: List<PlanItemArg>,
)
```

若使用 kotlinx.serialization，需要为 `StepStatus` 配置 `snake_case` 名称映射（Codex 用 `#[serde(rename_all = "snake_case")]`）。

### 5.2 Result 数据类

工具返回给 Koog 的结果可以是一个简单的标记对象，最终渲染为固定文本：

```kotlin
data class UpdatePlanResult(val ok: Boolean = true)
```

向 LLM 输出的文本固定为 `"Plan updated"`，不带 JSON 结构。

### 5.3 继承 Koog 的 Tool&lt;Args, Result&gt;

```kotlin
class UpdatePlanTool(
    private val session: Session,          // 用于发出事件
) : Tool<UpdatePlanArgs, UpdatePlanResult>("update_plan") {

    override val description: String =
        """Updates the task plan. Provide an optional explanation and a list of plan items, each with a step and status. At most one step can be in_progress at a time."""

    override suspend fun execute(args: UpdatePlanArgs): UpdatePlanResult {
        // 1. 模式校验：若当前处于 Plan 模式则抛出工具错误
        if (session.currentMode == ModeKind.PLAN) {
            throw ToolException("update_plan is a TODO/checklist tool and is not allowed in Plan mode")
        }
        // 2. 解析与校验已由框架完成（args 已是强类型）
        // 3. 发出计划更新事件，交给 UI / 持久化层
        session.emitEvent(PlanUpdateEvent(explanation = args.explanation, plan = args.plan))
        // 4. 返回成功（输出渲染层固定回 "Plan updated"）
        return UpdatePlanResult(true)
    }
}
```

### 5.4 执行逻辑要点

- **整体替换语义**：每次调用以传入的 `plan` 完整覆盖当前计划，不做增量 diff。
- **事件化、不进上下文**：计划状态不要塞进模型的可见上下文，应通过独立的事件通道（相当于 Codex 的 `EventMsg::PlanUpdate`）交给前端渲染与 rollout 持久化。这样符合 Codex 的「model visible context」约束——避免无界增长、避免污染上下文。
- **模式校验**：在 Plan 模式下禁用，对应 Codex 的 `ModeKind::Plan` 拦截。
- **不强制「至多一个 in_progress」**：与 Codex 一致，交给模型自觉；如需更严格可在 `execute` 里加一条校验并抛出 `ToolException`，但这会偏离 Codex 行为。

### 5.5 依赖

- **Session**：用于读取当前模式、发出 `PlanUpdateEvent`。等价于 Codex 的 `session.send_event(...)`。
- **事件总线 / EventEmitter**：需要一个让 UI 与持久化层订阅的事件通道。
- **不需要** HistoryStore、tokenizer、ContextManager——该工具不读写模型上下文，也不计算 token。
- **可选**：一个 `PlanStore`（内存态）用于 UI 实时展示当前计划快照，但 handler 本身不直接持有它，只通过事件更新。
