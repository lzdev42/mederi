# Todo 系统改造计划（review 定稿 v2）

> 2026-09 定稿。目标：`update_plan` 更名重写为 `update_todo`（无 Plan 任务的轻量进度跟踪）+
> Plan 子任务投影到同一 todo 面板。全链路唯一真理源、结构化数据零手拼。

## 0. 目标

1. `update_plan` → `update_todo`：无 Plan 任务的轻量进度跟踪，机制对齐 opencode todo
   （整体替换、持久化、跨轮回注入、UI 展示）。
2. 有 Plan 时，todo 面板 = Plan 子任务投影：该做哪个 / 正在做哪个 / 做完了哪个（含 Failed）。
3. 全链路唯一真理源、结构化数据零手拼。

## 1. 现状事实（已查证）

| # | 事实 | 位置 |
|---|---|---|
| 1 | `update_plan` 事件 payload 手拼字符串、无人消费、不持久化、不回注入 | AgentTools.kt:67-86 |
| 2 | UI 已全就位：`TodoListCard`、`snapshot.todos`、`viewModel.todos`，只缺数据管道 | RightExtensionPanel.kt:629,848 |
| 3 | `PLAN_PROGRESS` 在 reducer 是空操作（`-> snapshot`） | SnapshotReducer.kt:167 |
| 4 | Plan 执行模型层有进度（子任务状态持久化 + 每轮挂载 Active Plan），UI 层零展示 | TurnExecutor.kt:195-223 |
| 5 | `spawn_agent` 改状态但不发事件；`verify/converge` 发事件但不带子任务列表 | SpawnAgentTool.kt:90-98 |
| 6 | 事件映射按 `valueOf(name)`，两端枚举同名即通 | MederiModelMapper.kt:126 |
| 7 | `ToolFactory.build` 仅 TurnExecutor 一个调用方；`MederiAiCore` 可达 `mederi.projects.get` | ToolFactory 唯一调用点 :569 |

## 2. 真理源与投影路径（防碎片核心）

| 值 | 唯一真理源 | 一切投影 |
|---|---|---|
| 无 Plan todo | `sessions.todos` 列（data.db） | 事件 payload、`# Current Todo` 段、快照、TodoListCard |
| Plan 子任务进度 | `PlanStore`（plans/*.json） | PLAN_PROGRESS payload、todo 面板、Active Plan 段 |
| UI `snapshot.todos` | **非真理源**，无状态纯投影 | 到达即整体替换，禁止合并/积累 |

**红线**：唯一真理源 ≠ 单一读取路径，而是"不存在第二份存储、第二份映射逻辑"。
事件与 hydration 同源同函数；reducer 无状态。

## 3. 数据契约（序列化纪律，禁手拼）

**规则**：结构化数据一律 `@Serializable` DTO + kotlinx.serialization；全链路禁止
`joinToString`/字符串模板/`buildString` 拼 JSON、禁止正则/字符串手术解析；
decode 失败 → `runCatching` 丢事件保快照；Json 实例单处定义（沿用 PlanStore 模式）。

**仅两个 core 类型**（review 决策：`FAILED` 直接进 domain 枚举，砍掉 PlanSubtaskStatus 瘦 DTO）：

```kotlin
// core/model/Todo.kt（新），包 xyz.mederi.domain.model
@Serializable enum class TodoStatus {
    @SerialName("pending") PENDING, @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED, @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED   // 仅 plan 投影使用；update_todo 参数拒绝
}
@Serializable data class TodoItem(val content: String, val status: TodoStatus)
```

- `sessions.todos` 列与 `TODO_UPDATED`/`PLAN_PROGRESS` 事件 payload **共用 `List<TodoItem>`
  序列化**（core 侧唯一 encode/decode 对：`encodeTodos()` / `decodeTodos()`），payload key 统一
  为 `"todos"`（两事件同构解码，reducer 一份 decode 代码）
- shared 契约侧：`TodoStatus` 加 `Failed` + `@SerialName` 小写（kotlinx 直接解码，无手写映射）；
  wire DTO `TodoWireItem(content, status)` 供 reducer 解码；hydration 经 `MederiModelMapper`
  （domain→契约）单一函数
- `TodoItem.id`（契约侧）= **渲染 key**，投影边界合成（`"t$index"`），永不持久化、永不交换
- `explanation` 参数不落库，仅事件 payload（原始消息查看器可见）

## 4. Part A：`update_todo`（无 Plan 任务）

- **工具**：`todos: List<TodoItemArg{content,status}>` 整体替换 + 可选 `explanation`；
  硬校验（聚合报错）：至多一个 IN_PROGRESS、content 非空、**拒绝 FAILED**（plan 专属语义）；
  空列表 = 清空；**硬门禁（代码强制）**：Plan 执行期（APPROVED/IN_PROGRESS）拒绝调用——
  确保 todo 面板在 Plan 期只显示 Plan 子任务投影（review 补强，原为提示词级规则）
- **执行**：`sessionStore.updateTodos()` 落库 → 发 `TODO_UPDATED`（payload `todos`=JSON、
  `explanation`）→ 返回确认文本
- **门禁**：仅主代理（`!isSubagent`）；子代理进度单是 spec
- **回注入**：TurnExecutor 每轮无活跃 Plan 且 `session.todos` 非空时挂 `# Current Todo` 段
  （`- [STATUS] content` + 进度计数）；**turn 边界刷新**，turn 中途不重建 prompt
- **提示词**：TOOL_GUIDELINES 的 update_plan 行替换为 update_todo，写明
  "有 Active Plan 时禁用——plan 子任务状态即 tracker；单步回复不需要"

## 5. Part B：Plan 子任务投影

四个状态变更点发 `PLAN_PROGRESS`，payload 统一加 `todos`
（`plan.toTodoProjection().encodeTodos()`，**一个共享函数**，四处调用）：

| 触发点 | 动作 | 子任务状态 |
|---|---|---|
| `create_plan` 批准后（含自动批准） | 补发事件 | 全 PENDING |
| `spawn_agent` 派工 | **补 eventBus + 发事件** | 目标 → IN_PROGRESS |
| `verify_subtask` | 已发，加 todos | → COMPLETED / FAILED |
| `converge_plan` | 已发，加 todos | 追加补救 PENDING |

`generate_spec` 状态未变，不发。

- **hydration**：`MederiAiCore.getSnapshot` 与 `MederiEventAggregator.observe` 初始快照时，
  活跃 Plan 存在 → `mederi.projects.get(projectId)` 构造 PlanStore → `planProjection()`；
  wasm 经 `/snapshot` 端点自动同源。**优先级：Plan 投影 > session.todos**
  （与提示词挂载规则同构）
- 提示词侧有活跃 Plan 时**不挂** `# Current Todo`（Active Plan 即 tracker，不养第二份进度）

## 6. Active Plan 段瘦身 + 指针

activePlanContent 构建处（TurnExecutor，唯一构建点）：

- **spec 只挂活跃子任务**（IN_PROGRESS 优先，否则下一个 PENDING）；历史 spec 留在磁盘
  （spawn 自取、generate_spec 可重写）
- 骨架全量保留：status/brief/targetFiles/verification 标准/验证结果/decisions
  （编排、验证、收敛的全局决策依据）
- Progress 行后加指针：`Current: Subtask N (IN_PROGRESS): {name}`
  （无 IN_PROGRESS 时取 nextPending）

## 7. UI（RightExtensionPanel.kt）

`TodoListCard` 从二态升级四态渲染：Pending 灰、**InProgress 高亮**、
Completed 绿勾+划线（现有）、**Failed 红**。纯渲染 `snapshot.todos`，无本地状态。

## 8. 改动文件清单

**core**（13）：`model/Todo.kt`(新)、`model/Session.kt`(+todos)、
`sqldelight/data/.../DataDatabase.sq`(+列+查询)、`store/SessionStore.kt`(+updateTodos)、
`store/sqlite/SqliteSessionStore.kt`、`store/InMemorySessionStore.kt`、
`tools/AgentTools.kt`(重写工具)、`tools/ToolFactory.kt`(+sessionStore 参数,注册表)、
`model/MederiEvent.kt`(+TODO_UPDATED)、`koog/TurnExecutor.kt`(挂载+瘦身+指针)、
`prompt/SystemPrompts.kt`、`plan/PlanProjection.kt`(新,投影+编解码)、
`tools/PlanTools.kt` + `tools/VerifyTools.kt` + `tools/subagent/SpawnAgentTool.kt`(投影事件)

**shared**（6）：`contract/models/ChatModels.kt`(+TODO_UPDATED)、
`contract/models/TodoModels.kt`(+Failed+SerialName+WireDTO)、
`contract/SnapshotReducer.kt`(两 case+decode)、
`bridge/MederiModelMapper.kt`(+toTodos)、`bridge/MederiEventAggregator.kt`(+hydration)、
`bridge/MederiAiCore.kt`(getSnapshot hydration)、`ui/components/RightExtensionPanel.kt`(四态)

**文档**：AGENTS.md（§5 加"DTO 编解码禁手拼"一行；§5.6 补 todo 跟踪语义；§8 状态）

**测试**：core `TodoToolTest`(新：校验拒绝/持久化往返/事件可解码/清空/投影映射)、
shared `SharedLogicDesktopTest.kt` 追加 reducer 用例
（TODO_UPDATED 替换 / PLAN_PROGRESS / 畸形 payload 降级 / 空列表清空）

## 9. 边界与既定取舍

- 面板同一槽位 last-writer-wins；plan 归档后投影停在终态，直到下次 TODO_UPDATED
- 会话重开：初始快照按优先级 hydration，无 Plan 时 todo 面板显示持久化的模型 todo
- 删 session 行即删 todo，无清理逻辑；新会话 `todos='[]'`
- `updateTodos` 沿现有模式更新 `updated_at`（与 updateAgentConfig 一致）
- 子代理（Executor/Researcher）不给 todo 工具；InMemory 会话天然无 todo

## 10. 验证与收尾

1. `./gradlew :core:jvmTest :app:shared:jvmTest` 全绿
2. 经用户已批准（2026-09 会话）：删除本地 `data.db` 重建（`config.db` 不动）
3. AGENTS.md 固化后交差
