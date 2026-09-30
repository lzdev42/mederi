# 04 · 核心流程图与时序图

> 本篇描述运行时行为。类与文件的位置见 01-core.md / 02-app-shared.md。

## 1. 发送消息全链路（sendMessage 端到端时序图）

```mermaid
sequenceDiagram
    autonumber
    participant UI as WorkspaceViewModel<br/>(app/shared commonMain)
    participant AC as AiCore<br/>(MederiAiCore 直调 / ServerAiCore REST)
    participant SM as SessionManagerImpl
    participant TE as TurnExecutor
    participant HS as HistoryStore (data.db)
    participant K as Koog AIAgent (turn 内)
    participant LLM as LLM 供应商
    participant EB as eventBus (SharedFlow)

    UI->>UI: guardImageSupport 剔除放行(模型不支持图片 → 剔除本次图片<br/>+ 一次性轻提示 imageStrippedNotice, 历史保留)<br/>PromptComposer.compose(主指令+粘贴文本)<br/>乐观消息入 pendingUserMessages
    UI->>AC: sendMessage(conversationId, ChatPromptInput{text, model, agent, thinkingLevel=effectiveThinkingLevel, apiKeyId=getApiKeyId(model.provider)})
    AC->>AC: MederiInputMapper.toMessageParts / toAgentConfig；记录 lastApiKeyIdByProvider（自动命名跟随用）
    AC->>SM: SessionManager.sendMessage(id, SendMessageRequest{..., apiKeyId})
    SM->>TE: TurnExecutor.sendMessage(sessionId, request)
    TE->>TE: activeApiKeyId = request.apiKeyId（本 turn 唯一真理源）
    TE->>TE: 校验 IDLE → 读 Project → PlanStore.loadBySession<br/>组装 activePlanContent/spec指针/activeTodoContent(互斥)
    TE->>TE: SystemPrompts.build(agentMode, activePlan, activeTodo)<br/>+ withSkills(继承角色) + withProjectRules(AGENTS.md 指令链,<br/>向上:git根→项目目录 + 向下:项目目录直接子目录一层, 浅→深)
    TE->>TE: effectiveModel/effectiveReasoningLevel → sessionStore.updateAgentConfig
    TE->>HS: append(用户消息+durable环境块) 【durable-first】
    TE->>EB: SESSION_UPDATED (客户端 refreshPage 即时回查, 新 turn 用户消息立即可见)
    TE->>TE: sessionStore.updateStatus(RUNNING)
    TE->>TE: scope.launch { runTurn() }
    AC-->>UI: (立即返回)
    alt 用户在 turn 运行中插话 (引导模式 Steering, 见 2.1)
        UI->>AC: steerMessage(conversationId, input)
        AC->>TE: steerMessage
        TE->>TE: RUNNING → pendingSteerings 入队 + STATUS(steering_queued)<br/>非 RUNNING → 降级 sendMessage
    end

    rect rgb(235, 244, 255)
        note over TE,LLM: runTurn（后台协程）
        TE->>TE: 解析 apiKey = activeApiKeyId?.let{getKeyValue(provider,it)} ?: getDefaultKeyValue(provider)<br/>(选定 key 优先，未选回退默认；压缩/子代理/浏览器同类同源)
        TE->>TE: preflightCompressionIfNeeded<br/>(contextUsedTokens > 70% 窗口 → compressOnce，带 apiKeyId)
        TE->>TE: ToolFactory.build(工具集, agentMode/role裁剪)<br/>透传 AgentsSubtreeDiscovery: read/list 工具访问路径上<br/>发现未注入过的 AGENTS.md 时追加进工具返回文本(会话级去重)
        TE->>TE: buildTurnAgent: KoogClientFactory(+RetryableLLMClient)<br/>+ KoogModelBuilder + KoogParamsBuilder<br/>+ ChatMemory(HistoryStoreChatHistoryProvider)<br/>+ graphStrategy(+Compression) + EventHandler
        K->>HS: load → aiViewWindow(最后 SUMMARY 之后) → KoogMessageMapper
        K->>LLM: requestLLMStreaming(系统提示+AI视图窗口+哨兵输入)
        loop 流式帧
            LLM-->>K: StreamFrame (text/reasoning/tool_call/tool result/end)
            K-->>EB: MESSAGE_DELTA (launchStreamConsumer)
            EB-->>UI: SSE 或进程内 → SnapshotReducer 更新快照
        end
        loop 工具循环 (maxAgentIterations=3000)
            K->>K: toAssistantMessageSafe(args 坏 JSON 降级 "{}")
            K->>HS: TurnIncrementalPersister.persistAssistant（增量落库防崩丢）
            K->>K: nodeExecuteTools: 执行工具<br/>(文件写白名单/沙盒命令/office_read/office_write(pptx 只读)/<br/>list_processes/stop_process/Plan/Verify/ask_user...)
            K-->>EB: TOOL_CALLED / TOOL_RESULT 事件
            K->>K: 工具边界 (nodeSendToolResult) pollSteering 出队<br/>→ SteeringItem 注入工具结果消息 (引导模式插话)
            alt ask_user / create_plan(APPROVAL)
                K-->>EB: QUESTION_REQUESTED / PLAN_APPROVAL_REQUESTED (工具协程挂起)
                UI-->>AC: resolveQuestion / resolvePlanApproval
                AC->>TE: resolveQuestion/resolvePlanApproval → deferred.complete
                K->>HS: persistToolResults
            end
            K->>LLM: send_tool_results → requestLLMStreaming
        end
        K->>HS: ChatMemory store → HistoryStoreChatHistoryProvider.reconcile<br/>(指纹对齐/插 SUMMARY/补诊断, 失败退回 replace)
        TE->>HS: (压缩触发时) 插入 SUMMARY 标记消息
        TE->>TE: diffTracker.captureSnapshot → diffStore.save(TurnDiff)<br/>装配 turnDiffSummary (files/additions/deletions 单轮文件变更汇总)<br/>回填最后一条 assistant 消息.turnDiffSummary (UI TurnDiffCard)
        TE->>TE: sessionStore.updateStatus(IDLE)
        TE->>EB: MESSAGE_COMPLETED (可选 warning)<br/>有文件变更时携 turnDiffSummary/diffMessageId
        TE->>TE: finally 冲刷: pendingEventMessages 补发 + pendingSteerings 残留降级新 turn<br/>(撞 RUNNING 放回队列)
    end
    UI->>UI: SnapshotReducer.applyWithRefresh + refreshPage 回查落库对齐
```

## 2. Turn 执行流程图（含错误分类）

```mermaid
flowchart TD
    S["sendMessage(sessionId, request, subagentRole?)"] --> V{"会话状态 == IDLE?"}
    S2["steerMessage(sessionId, request) 引导模式"] --> RQ{"会话 RUNNING<br/>且 activeJobs 活跃?"}
    RQ -- "否" --> S
    RQ -- "是" --> STQ["pendingSteerings 入队 (STATUS steering_queued)<br/>工具边界 nodeSendToolResult 经 pollSteering 出队注入<br/>turn 收尾残留: finally 冲刷降级新 turn (见 2.1)"]
    STQ --> RUN
    V -- "否" --> E1["上抛异常(上游包装 MederiException)"]
    V -- "是" --> PREP["组装上下文: Project/PlanStore/Notebook<br/>activePlanContent(计划+spec指针+活跃spec)<br/>activeTodoContent(仅无活跃Plan)"]
    PREP --> SP["SystemPrompts.build / forSubagent<br/>+ withSkills(继承角色) + withProjectRules(AGENTS.md 指令链:<br/>向上 git根→项目目录 + 向下 直接子目录一层, 浅→深)"]
    SP --> CFG["解析 effectiveModel + effectiveReasoningLevel<br/>回写 sessionStore.updateAgentConfig"]
    CFG --> DF["durable-first: buildUserMessage(注入 NOT_FOR_UI 隐藏标记)<br/>historyStore.append → SESSION_UPDATED → RUNNING"]
    DF --> BG["scope.launch runTurn"]
    BG --> PF{"usedTokens > 70% 窗口?"}
    PF -- "是" --> COMP["compressOnce(mini agent maxAgentIterations=10, compressOnlyStrategy)<br/>失败不阻塞"]
    PF -- "否" --> BUILD
    COMP --> BUILD["ToolFactory.build → buildTurnAgent"]
    BUILD --> RUN["agent.run(MEDERI_INPUT_PERSISTED)"]
    RUN --> OK{"正常结束?"}
    OK -- "是" --> DONE["IDLE + MESSAGE_COMPLETED(warning?; 有文件变更时携 turnDiffSummary/diffMessageId)<br/>diffTracker.captureSnapshot → diffStore.save → 回填最后一条 assistant 消息 turnDiffSummary"]
    OK -- "异常 e" --> T{"isTransientError(e)<br/>或 RECOVERABLE?"}
    T -- "是" --> IDLE["IDLE + MESSAGE_ERROR(可恢复环境态/限流/网络中断)<br/>(首帧前重试/首帧后防重留痕，保持可继续状态)"]
    T -- "否" --> ERR["ERROR + MESSAGE_ERROR<br/>(ErrorCollector rich payload: 简报/errorId/完整诊断)"]
    DONE & IDLE & ERR --> STOP
    ABORT["abort(id): cancel job → stopAllForSession(级联收割本会话 RUNNING 子代理)<br/>+ cancelAll requester<br/>IDLE + MESSAGE_ERROR(ErrorCollector CANCELLED/WARNING)<br/>(abortAndJoin 同链路, cancelAndJoin 后收割)"] --> STOP["结束"]
```

**事件流消费侧**：`eventBus` → 三路消费：① `MederiAiCore.events()`（进程内）/ `GET /v1/events`（SSE 遥控端）→ 客户端 `SnapshotReducer` 聚合快照；② `launchStreamConsumer` 把 StreamFrame 转 MESSAGE_DELTA；③ UI 层特性（SessionTitleService 等）订阅。

### 2.1 引导/排队模式（Steering）

用户在 turn RUNNING 时插话的两条 UI 路径（共用 `QueuedMessage` 模型，输入框上方队列横幅展示 `currentQueuedMessages`）：

- **排队模式**：`WorkspaceViewModel.enqueueCurrentInput` 把当前输入框文本/粘贴/图片压入会话队列 `queuedMessagesByConv`（`removeQueuedMessage` 可移除）；turn 结束恢复 Idle 时**自动出队** `sendQueuedMessage`（即普通 sendMessage，携带入队时的模型/推理档/agent/apiKeyId）。
- **引导模式**：`steerQueuedMessage` 取出排队项，经 `AiCore.steerMessage`（契约 → `MederiAiCore` 直调 / `POST /v1/sessions/{id}/steer` 遥控路由）**立即注入**运行中的 turn。

Core 侧链路（`TurnExecutor`）：

| 链路环节 | 行为 |
|---|---|
| `steerMessage` | 会话 RUNNING 且 activeJobs 活跃 → `SteeringItem` 入 `pendingSteerings` 队列 + 发 `STATUS(status=steering_queued)`（SnapshotReducer 忽略该事件，见 §3）；非 RUNNING → 安全降级 `sendMessage` |
| 工具边界注入 | `graphStrategy`（`mederiSingleRunStrategy*`）的 `nodeSendToolResult` 调 `pollSteering` 出队，有项则把插话追加进本轮工具结果消息，LLM 下一步即可见并据此调整 |
| turn 收尾冲刷 | turn 结束 finally 冲刷未被工具边界消费的残留 `pendingSteerings`（逐条降级 `sendMessage` 开新 turn，撞 RUNNING 冲突放回队列） |

```mermaid
sequenceDiagram
    autonumber
    participant VM as WorkspaceViewModel
    participant AC as AiCore
    participant TE as TurnExecutor
    participant K as Koog Agent (运行中的 turn)

    Note over VM,K: 当前 turn RUNNING，用户输入插话
    VM->>VM: enqueueCurrentInput 压入会话队列(队列横幅可见)
    alt 引导模式(立即注入运行中 turn)
        VM->>AC: steerMessage(convId, input)
        AC->>TE: steerMessage
        TE->>TE: RUNNING → pendingSteerings 入队<br/>+ STATUS(steering_queued)
        K->>K: 工具边界 (nodeSendToolResult) pollSteering 出队<br/>→ 注入工具结果消息，LLM 下一步可见
    else 排队模式(turn 先结束)
        Note over TE: turn 结束 finally: 未消费的残留 steering<br/>降级 sendMessage 开新 turn
        VM->>VM: Idle → 自动出队 sendQueuedMessage(普通 sendMessage)
    end
```

## 3. 事件 → UI 快照聚合流程

```mermaid
flowchart TD
    EB["core eventBus<br/>MutableSharedFlow(replay=0, buffer=256, DROP_OLDEST)"] --> MAP["MederiModelMapper.toCoreEvent"]
    MAP --> CH1["desktop/server: 进程内 Flow"]
    MAP --> CH2["GET /v1/events SSE<br/>(15s 心跳注释帧, Bearer 鉴权)"]
    CH1 & CH2 --> CLIENT["客户端 (commonMain)"]
    CLIENT --> OB["observeConversation(id)"]
    OB --> INIT["初始: getSnapshot 构建完整快照<br/>(Aggregator: Session+历史Message+initialTodos hydration<br/>Plan投影 > session.todos; planContent 磁盘读取增强)"]
    INIT --> LOOP
    LOOP["事件循环"] --> SR["SnapshotReducer.applyWithRefresh(snapshot, event, refreshPage)"]
    SR --> SW{"event type"}
    SW -- "MESSAGE_DELTA" --> D["在 streaming Assistant 占位消息上<br/>合并 Text/Reasoning 块 / 增量 ToolCall / 新建 File 块"]
    SW -- "MESSAGE_COMPLETED" --> C1["先发完成状态快照"]
    C1 --> C2["refreshPage() 回查 listMessagesPage<br/>(token统计/contextUsedTokens 对齐落库) → 发第二个快照"]
    SW -- "MESSAGE_ERROR" --> ER["写 errorMessage/errorId/errorDiagnostic,<br/>回查后发最终快照(status=Error)"]
    SW -- "TOOL_CALLED/RESULT" --> TO["按 toolCallId 精确匹配更新 ToolCall block"]
    SW -- "QUESTION_*/PLAN_APPROVAL_*" --> PE["写/清 pendingQuestion / pendingPlanApproval"]
    SW -- "TODO_UPDATED/PLAN_PROGRESS" --> TD["解码 payload.todos 整体替换(失败丢弃事件)"]
    SW -- "STATUS RETRYING" --> ST["statusHint='attempt/max|serverMsg|retryAt'(serverMsg=供应商真实报错; retryAt=delayMs+当前时间)<br/>UI StatusBar 重试态第二行渲染"]
    SW -- "SESSION_UPDATED" --> SU["status=Working, 清旧 errorMessage<br/>+ refreshPage 即时回查 listMessagesPage(新 turn 用户消息立即可见)"]
    SW -- "STATUS(steering_queued 引导)" --> SIG["忽略: payload 形状与 RETRYING 约定 key 不符(代码现状)<br/>steering 提示仅入队侧日志, 不渲染 StatusBar"]
    D & C2 & ER & TO & PE & TD & ST & SU --> UI["WorkspaceViewModel.snapshot<br/>→ chatItems(derivedStateOf 展平渲染)"]
```

**BROWSER_TASK_* 事件**：`BROWSER_TASK_STARTED/STEP/COMPLETED/ERROR/STOPPED` 不经 `SnapshotReducer` 聚合，由 `WorkspaceViewModel` 侧直接消费（展开浏览器面板 / 更新步骤列表）。

## 4. Plan Loop（复杂改动主流程）

```mermaid
flowchart TD
    U["用户请求"] --> T{"主代理分诊(Triage Flow, 提示词强制)"}
    T -- "纯读" --> R1["直接读文件回答<br/>不够深 → subagent(SPAWN_RESEARCHER) → 报告(始终落盘, 父收摘要+路径) → 回答"]
    T -- "小改动(已知根因/几行代码)" --> R2["主代理直接 edit/write<br/>进度走 update_todo(无Plan)"]
    T -- "复杂改动" --> RES["(理解不足先 subagent(SPAWN_RESEARCHER))"]
    RES --> CP["create_plan(WHAT, 拆小可验证: 每子任务=spec+verification)<br/>前置 voidActivePlans 作废同会话旧计划（发 PLAN_PROGRESS 'voided'）<br/>PlanStore.save(.mederi/plans/{id}.json+md)"]
    CP --> MODE{"agentMode"}
    MODE -- "AUTONOMOUS" --> AUTO["自动 APPROVED"]
    MODE -- "APPROVAL" --> WAIT["PLAN_APPROVAL_REQUESTED → UI PlanApprovalCard<br/>用户批准/拒绝(resolvePlanApproval)"]
    WAIT -- "拒绝" --> REJ["告知用户结束/修改"]
    WAIT -- "批准" --> GEN
    AUTO --> GEN["generate_spec(planId, subtaskIndex, spec)<br/>逐子任务派生 HOW(行级规范), brief 恒不变<br/>不转录文件摘录:executor 自己 read_file 原文件"]
    GEN --> SPAWN["subagent(action=SPAWN, planId?, subtaskIndex?)<br/>planId 非空 → 硬校验 subtaskIndex/spec 存在 → updatePlan 原子置 IN_PROGRESS<br/>planId 为空 → ad-hoc 路径: task+briefing 直接派 executor(跳过 spec/IN_PROGRESS)<br/>PLAN_PROGRESS('subtask-started'+todos投影+subtasks 全量 JSON 列表)<br/>briefing 简化注入: 只 task + planDetail(意图)<br/>(researchNotes/appendix 不再注入,executor 自己 read_file research.md/原文件)<br/>异步派工: 立即返回 agentId(不阻塞父 turn)<br/>独立子任务可同消息并行 spawn(无上限)"]
    SPAWN --> ENDTURN["END TURN（父代理 turn 结束,不阻塞）"]
    ENDTURN --> SUB["Executor 子代理(一次性,独立TurnExecutor)<br/>spec 注入其唯一用户消息,自顶向下执行,不问用户<br/>完成时报告落盘 {planId}/reports/NN-executor.md, 父上下文只收尾部1500字符+路径(捕获SPEC_FEEDBACK)<br/>SPEC_FEEDBACK 回报 spec 与现实的矛盾"]
    SUB --> WAKE["子代理终态 → SUBAGENT_COMPLETED/ERROR/STOPPED 事件(eventBus, NonCancellable emit)<br/>→ handleSubagentTerminalEvent 组 &lt;event_message&gt; → pendingEventMessages 入队<br/>→ 父 turn 空闲 dispatchPendingEventMessages 批量合并唤醒<br/>(同会话多个完成合并成一条内部消息,一次 turn)"]
    WAKE --> VER["verify_subtask(planId, subtaskIndex, status, evidence)<br/>自动执行 Subtask.verification 命令(30s)"]
    VER --> CHK{"verify 结果"}
    CHK -- "PASS(且命令 exit=0)" --> NEXT["子任务 COMPLETED → 下一个子任务"]
    CHK -- "执行错(FAIL/PARTIAL)" --> CONV["converge_plan 追加补救子任务 → 重执行"]
    CHK -- "spec 错" --> REGEN["重新 generate_spec 覆盖 → 重执行"]
    NEXT --> MORE{"还有子任务?"}
    MORE -- "是" --> SPAWN
    MORE -- "否" --> ARCH["全部 COMPLETED → plan 置 COMPLETED<br/>planStore.archive → .mederi/plans-done/{planId}/<br/>planStore.writeWalkthrough → plans/{planId}/walkthrough.md (archive 时随之移动)"]
    CONV & REGEN --> SPAWN
```

**并行执行（2026-09，2026-09-14 异步化，2026-09 并发上限硬门禁）**：工具执行节点 `nodeExecuteTools(parallel=true)`——同一条消息的多个工具调用并行执行；**子代理单会话并发受限**（设置页可配，默认 2，见下述并发门禁），同父会话内 EXECUTOR 与 RESEARCHER 同池计数，达到上限时 spawn 工具返回指导文案让模型结束 turn 等待唤醒；浏览器任务不经 SubagentManager，天然排除在外。约束：`create_plan` 单独发；**禁止同消息混发 generate_spec 与 subagent(SPAWN)**（并行无序，spawn 可能读到未写入的 spec）；同文件并发写已由 `FileWriteRegistry` 代码级硬拒绝（write_file/edit_file try-lock，占用即 Error，AI 下轮重试），不得重复执行同一命令仍靠 AI 自律。plan 状态写入一律走 `PlanStore.updatePlan`（原子读改写），防止并行 subagent(SPAWN)/generate_spec/verify 互相覆盖。**子代理异步化（2026-09-14）+ 主动上报（2026-09-25）**：子代理统一为单一 `subagent` 工具 action 分流（SPAWN/SPAWN_RESEARCHER/STATUS/STOP，原 6 个独立工具封装合并；`agent_status`/`stop_agent` 工具类保留但不向 AI 注册）。`subagent(SPAWN)` 支持 planId 可选：有 planId 走计划流程（spec 硬校验+IN_PROGRESS），无 planId 走 ad-hoc 路径（task+briefing 直接派 executor，跳过 spec/IN_PROGRESS）。派工异步（立即返回 agentId，后台协程跑子代理），父代理 turn 不再被阻塞，可继续对话；子代理终态经主 eventBus 发 `SUBAGENT_COMPLETED/ERROR/STOPPED` 事件（NonCancellable emit），父 TurnExecutor 订阅后 `handleSubagentTerminalEvent` 合成 `<event_message>` 入 `pendingEventMessages` 队列，父 turn 空闲时 `dispatchPendingEventMessages` 批量合并成一条内部消息自动唤起新 turn（同会话多个完成合并成一次 turn；竞态（父会话正忙）时放回队列等 turn 收尾 finally 冲刷；abortAndJoin 清空该会话队列）。agent 丢失/归档（不在内存表）时 `subagent(STATUS)` 返回 NOT_FOUND + `AgentRecoveryInfo`（retired 记录：末次状态/result/报告路径/touchedFiles 部分认知），父代理据此判断已改动文件（与 targetFiles 比对查越界）再决定重跑或接受部分成果。`wait_agent` 枚举值已删，父代理不再阻塞拉取。**diff 合并子代理改动（2026-09-23）**：主 turn runTurn 开头 `ParentDiffRegistry.register`，子代理 turn 结束时把文件改动经 `onTurnDiff` 回调 + `ParentDiffRegistry.mergeInto` 合并进父 turn 的 `TurnDiffTracker`（`mergeChanges`），finally 里 `unregister`——修复"turn 改动摘要不含子代理改动"的 bug。子代理自身 diff 仍独立落库（其临时 InMemory store），合并只影响父 turn 的 TurnDiff 聚合。**walkthrough 自动生成（2026-09-23）**：计划全部子任务 COMPLETED 时，`PlanStore.writeWalkthrough(plan)` 自动装配 walkthrough 文档写至 `plans/{planId}/walkthrough.md`（archive 时随整个计划目录移到 `plans-done/{planId}/walkthrough.md`，内容由 `buildWalkthrough` 聚合 plan/subtask/spec/verification/changes；`verify_subtask` 全 PASS 文案亦指向该路径）。

**Plan 审批时序（APPROVAL 模式）**：

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理(工具协程)
    participant PAR as PlanApprovalRequester
    participant EB as eventBus
    participant UI as WorkspaceViewModel(SnapshotReducer)
    participant TE as TurnExecutor
    participant PS as PlanStore(.mederi/plans/)

    M->>PS: PlanStore.save(plan)
    M->>PAR: request(planId, planPath, title, summary, planContent, subtaskCount)
    PAR->>PAR: 取消当前 pending(superseded)
    PAR->>EB: PLAN_APPROVAL_REQUESTED(payload 含 planContent)
    EB-->>UI: SnapshotReducer → pendingPlanApproval + planApprovals
    Note over M: CompletableDeferred.await() 挂起<br/>(turn 保持 RUNNING)
    UI->>TE: resolvePlanApproval(convId, planId, approved, model, thinkingLevel)
    Note over UI: model/thinkingLevel = 批准时刻输入框选中值<br/>(与 send() 同源: selectedModel/effectiveThinkingLevel)
    TE->>TE: 批准且 model≠null → sessionStore.updateAgentConfig<br/>写入 session.aiModel/reasoningLevel("最后一次选择"语义)
    Note over TE: create_plan 挂起等批准期间用户可能切了模型——<br/>同 turn 后续 subagent(SPAWN) 动态读 session 拿到新模型
    TE->>PAR: resolve(planId, approved) → deferred.complete
    PAR->>EB: PLAN_APPROVAL_RESOLVED
    EB-->>UI: 清 pendingPlanApproval, 对应项 status=APPROVED/REJECTED
    PAR-->>M: PlanApprovalResult(approved, superseded=false)
    alt approved=true
        M->>M: notebook.append → 继续生成 spec/spawn
    else approved=false
        M->>M: 向用户说明, 不执行计划
    end
```

**跨 turn / 重启后批准（"批准与 turn 解耦"）**（2026-09）：
上面是同 turn 批准——requester（内存 CompletableDeferred）存活，`resolvePlanApproval` 直接唤醒挂起的 create_plan，AI 同 turn 执行。
翻历史 / 进程重启后 requester 已消亡，无法唤醒任何挂起协程，走**跨 turn 分支**（`TurnExecutor.resolvePlanApproval`）：
1. 批准模型写入 session（同上），拒绝（approved=false）无存活 turn 可唤醒、无状态变更，直接返回 false（计划保持 PENDING_APPROVAL）；
2. 从 PlanStore 按 planId `load` 并校验：会话匹配、非终态、PENDING_APPROVAL——不满足则跳过（作废/已完成/他会话计划不可批）；
3. `updatePlan` 置 APPROVED 落盘 → 发 `PLAN_APPROVAL_RESOLVED` 事件；
4. 以一条 **UI 隐藏内部消息**（整条文本以 `<<<NOT_FOR_UI>>>` 开头，UI 渲染 `substringBefore` 得空串 → 用户不可见、AI 可见）经 `sendMessageInternal` 启动新执行 turn，AI 读到 Active Plan 已是 APPROVED 后自行走 generate_spec → spawn → verify。

**重启/翻历史恢复待批准卡片**：`MederiAiCore.initialPlanApproval()`（MederiEventAggregator.observe 初始快照 + getSnapshot 共用）读该会话最新非终态计划投影为契约 `PlanApprovalRequest` 填入 `pendingPlanApproval`——`status` 区分：
- APPROVAL 模式 + PENDING_APPROVAL → `"PENDING"`（待用户批准）；
- 自动模式已 APPROVED / 执行中 IN_PROGRESS → `"AUTO_APPROVED"`（已自动审批，UI 可展示而非完全不展示）。
职能边界：core **只把计划状态作为数据给 UI**，不判定卡片必须在对话内/外——UI 层自决渲染位置。

**问与计划挂起时用户直接回复**（三条硬规则之一）：`sendMessageInternal` 检测到 pending 时先 `injectNeutralPlanToolResult`（补写中性 ToolResult 落库，非批准/非拒绝）再 abort 旧 turn——计划保持 PENDING_APPROVAL，AI 响应新消息，绝不反问"为什么"。计划无"拒绝"态，只有批准/作废/被无视三态。

### 4.1 子代理生命周期与事件流（spawn → 终态）

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理(subagent 工具)
    participant SS as SessionStore
    participant SM as SubagentManager
    participant SR as SubagentRunnerImpl
    participant EB as eventBus(主)
    participant TE as TurnExecutor(父)

    M->>SS: get(parentSession) — 动态读模型<br/>(session.aiModel ?: 构造捕获的 turn 模型)
    M->>SM: spawn(task, briefing, spec, aiModel, reasoningLevel, ...)
    SM->>SM: agents[agentId] 占位注册 RUNNING<br/>后台协程跑子代理, 不阻塞调用方
    SM->>EB: SUBAGENT_STARTED(agentId, role, modelId, modelName, reasoningLevel, task, briefing?)
    Note over EB: sessionId=父会话；UI SubagentTracker 聚合进<br/>SubagentState 缓存(每个子代理一个 MVVM 对象)
    SM-->>M: 返回 agentId（立即，不阻塞父 turn）
    M->>M: END TURN（父代理结束 turn，期间可自由对话）
    SM->>SR: 后台协程 run(内存 store + 独立 TurnExecutor)
    alt 正常完成/出错/停止
        SR-->>SM: 汇报全文（executor 报告落盘 {planId}/reports/NN-executor.md）
        SM->>EB: SUBAGENT_COMPLETED / ERROR / STOPPED(agentId/role/status/result/reportPath/planId/subtaskIndex)<br/>(NonCancellable emit, sessionId=父会话)
        Note over SM: finally 同步写 retired[agentId]=AgentRecoveryInfo<br/>(agent 丢失/归档后 STATUS 仍能返回部分认知)
    else agent 丢失/归档(不在内存表)
        M->>SM: subagent(STATUS, agentId) → NOT_FOUND + recovery<br/>(AgentRecoveryInfo: 末次状态/result/报告路径/touchedFiles 部分认知)
    end
    EB->>TE: SUBAGENT_* 终态事件 → handleSubagentTerminalEvent<br/>组 <event_message> (type/agentId/status/ReportPath/Summary)
    TE->>TE: pendingEventMessages 入队(按父会话分组)<br/>父 turn 空闲 → dispatchPendingEventMessages 批量合并成一条内部消息<br/>→ sendMessageInternal 唤起新 turn（同会话多通知合并成一次 turn）
    Note over TE: 竞态：唤起时父会话正忙 → 放回队列，等 turn 收尾 finally 再冲刷<br/>abortAndJoin → 清空该会话队列
```

**abort 级联收割**：用户点"停止"（abort/abortAndJoin）→ cancel 父 turn job → `stopAllForSession(parentSessionId)` 杀本会话全部 RUNNING 子代理——子代理挂全局 scope 不随父 turn 取消而亡，不收割即孤儿（旧模型继续写文件，与"继续"后重 spawn 的新代理并发写同一批 targetFiles）。被杀子代理发 `SUBAGENT_STOPPED`，plan 子任务保持 IN_PROGRESS（"继续"后重 spawn 是干净路径）。

**子代理汇报取数链（2026-09）**：全量报告可随时拉取——`AiCore.getSubagentReport(agentId)` → `SessionManager.getSubagentReport` → `SubagentManager.getReport`：磁盘读报告全文（`{planId}/reports/NN-executor.md` / `research.md`），文件缺失时回退内存 result 或 `AgentRecoveryInfo`（retired）记录；遥控端对应路由 `GET /v1/subagents/{agentId}/report`。`<event_message>` 唤醒消息的 UI 通道：对话流内由 `EventMessageCard`（系统事件卡）渲染——读落盘报告全文/摘要展示，**非**仅 SubagentTracker 卡片。

## 5. 上下文压缩流程（自动 + 手动）

```mermaid
flowchart TD
    A["触发1: runTurn 前 preflight<br/>contextUsedTokens(estimate) > 70% 窗口"]
    B["触发2: graphStrategy 节点内<br/>isHistoryTooBig(prompt) > 70%"]
    C["触发3: 用户手动 compressHistory()"]
    A & B & C --> D["MederiCompressionStrategy.compress(llmSession, memory)"]
    D --> E["压缩源 = llmSession.prompt.messages<br/>CompressionPlanner: 保留段≤窗口×0.3 / 旧消息按窗口×0.5 分批 / 单条超窗口×0.9 head-trim"]
    E --> F["逐批 requestLLMWithoutTools 生成小结 → combineBatchSummaries 合并为单条 TLDR:(五节)"]
    F --> G["新历史 = system + TLDR:... + recent<br/>(head-trim 仅影响 AI 视图, HistoryStore 全量不删)"]
    G --> H["ChatMemory 回写 store → HistoryStoreChatHistoryProvider.reconcile<br/>检测首条 TLDR 未落库 → 按内容指纹对齐<br/>→ 插入 SUMMARY 标记消息(不删任何已有消息)"]
    H --> I["效果: message_history 全量保留(UI 可见/回滚可用)<br/>aiViewWindow = 最后一条 SUMMARY 及其后 → AI 视图变小"]
    A -. preflight 压缩失败(不阻塞).-> W["自动压缩失败(原因回灌 streamWarning)<br/>MESSAGE_COMPLETED/MESSAGE_ERROR.warning 对用户可见"]
```

## 6. ask_user 问询时序

```mermaid
sequenceDiagram
    autonumber
    participant K as Koog 工具协程(AskUserTool)
    participant QR as QuestionRequester
    participant EB as eventBus
    participant UI as WorkspaceViewModel(questionPage/questionAnswers)
    participant TE as TurnExecutor

    K->>QR: request(List<Question>)
    QR->>EB: QUESTION_REQUESTED(questionId=q_xxxxxxxx, questions JSON)
    EB-->>UI: SnapshotReducer → pendingQuestion → QuestionCard
    Note over K: deferred.await() 挂起
    UI->>UI: 用户逐题作答(选项/自定义/多选) 或 拒绝
    alt 提交答案
        UI->>TE: replyQuestion → resolveQuestion(convId, questionId, answers)
        TE->>QR: resolve(questionId, answers) → deferred.complete
        QR->>EB: QUESTION_RESOLVED
        EB-->>UI: 清 pendingQuestion
        QR-->>K: QuestionResult(answers)
        K->>K: 工具返回 AskUserResult JSON 给模型
    else 拒绝/中止
        UI->>TE: rejectQuestion
        TE->>QR: reject(questionId)(rejected=true) / abort 时 cancelAll()
        QR-->>K: QuestionResult(rejected=true) → 工具返回 "User declined..."
    end
```

## 7. 子代理（subagent 工具 action 分流 / 异步管理 / 主动上报）时序

> 2026-09-14：spawn 改为**异步派工**——单一 `subagent` 工具（`ToolFactory.SUBAGENT_TOOL_NAMES`，
> action=SPAWN/SPAWN_RESEARCHER/STATUS/STOP；原 6 个独立工具 spawn_agent/spawn_researcher/agent_status/
> stop_agent/wait_agent 封装合并，内部实现类保留但不向 AI 注册）调 `SubagentManager.spawn`
> 立即返回 agentId，子代理在后台协程运行，父代理 turn 不被阻塞。
> 2026-09-25：**主动上报（eventBus 事件链）**——子代理终态经主 eventBus 发
> `SUBAGENT_COMPLETED/ERROR/STOPPED` 事件（NonCancellable emit，sessionId=父会话），父 TurnExecutor
> 订阅后组 `<event_message>` 入 `pendingEventMessages` 队列，父 turn 空闲时
> `dispatchPendingEventMessages` 批量合并成一条内部消息唤起新 turn；
> 父代理不再需要 `wait_agent`（已删），用 `subagent(STATUS)` 查询、`subagent(STOP)` 主动停止。
> **概览面板与详情弹窗用户手动关闭（2026-09）**：UI 针对运行中的子代理提供关闭按钮（`WorkspaceViewModel.stopSubagent` → `AiCore.stopSubagent(agentId)`），SubagentManager 设置 `stopReason = "被用户关闭"` 并取消子代理协程；子代理退出时发射携带 `result = "被用户关闭"` 的 `SUBAGENT_STOPPED` 事件，按正常终态事件进入父会话 `pendingEventMessages` 并唤醒父 turn，与正常结束一样汇报给 AI 说明被用户关闭。
> agent 丢失/归档（不在内存表）后无超时卡死类通知机制（代码无该实现）——`subagent(STATUS)` 返回
> NOT_FOUND + `AgentRecoveryInfo`（retired 记录：末次状态/result/报告路径/touchedFiles 部分认知），
> AI 据此判断已改动文件、再决定重跑或接受部分成果。
> 子代理 `SPAWN` 的 planId 可选：无 planId 走 ad-hoc 路径（task+briefing 直接派 executor，
> 跳过 spec/IN_PROGRESS）；有 planId+subtaskIndex 时 spec 存在性硬校验仍成立。

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理工具协程(subagent 工具 → SpawnAgentTool)
    participant SM as SubagentManager
    participant PS as PlanStore
    participant SR as SubagentRunnerImpl<br/>(后台协程)
    participant ITE as 独立 TurnExecutor<br/>(InMemory store + 独立 eventBus)
    participant PTE as 父 TurnExecutor<br/>(pendingEventMessages 队列)
    participant EB as 主 eventBus

    M->>M: subagent(action=SPAWN, planId?, subtaskIndex?)
    alt planId 非空（计划流程）
        M->>PS: load(planId) 硬校验 subtaskIndex/spec<br/>(subtaskIndex 越界/spec 缺失/已 COMPLETED 均返回拒绝文本)
        M->>PS: updatePlan 原子置 subtask IN_PROGRESS
        M->>EB: PLAN_PROGRESS('subtask-started' + todos 投影 + subtasks 全量 JSON 列表)
    else planId 为空（ad-hoc 执行）
        M->>M: task+briefing 直接派 executor<br/>(跳过 spec/IN_PROGRESS/PLAN_PROGRESS)
    end
    M->>M: SubagentConfigManager.resolve(role=EXECUTOR/RESEARCHER,<br/>fallbackModel=会话模型, fallbackReasoning=会话推理档)<br/>→ 配置了独立模型/推理档则覆盖父会话, 否则继承
    M->>SM: spawn(task, briefing(简化: 只 task + planDetail, 不再注入 researchNotes/appendix), plan=st.spec, role=EXECUTOR, planId, executorSubtaskIndex, planStore)
    SM->>SM: agents[agentId]=RUNNING<br/>scope.launch 后台协程跑子代理, 不阻塞调用方
    SM->>EB: SUBAGENT_STARTED(agentId, role, modelId, modelName, reasoningLevel, task, briefing?)
    SM-->>M: 立即返回 {"agentId":"sub_xxx","status":"RUNNING"} (不阻塞)
    M-->>M: 主代理 END TURN, 期间继续对话/派发其他任务
    Note over SM,ITE: 后台执行(与父 turn 并行)
    SR->>SR: 内存建临时 Session(sub_xxxxxxxx, AUTONOMOUS)
    SR->>ITE: sendMessage(inputText=spec清单自顶向下+SPEC_FEEDBACK约定, subagentRole=EXECUTOR)
    Note over ITE: RESEARCHER 则 inputText=只读调研任务<br/>工具裁剪=read_file+list_directory
    Note over ITE: 继承策略=中心化 AgentCapabilities 表<br/>MCP: 主/执行/研究都继承(每子代理 turn 独立现连现断)<br/>Skills: 主/执行注入提示词, 研究不注入
    loop 子 turn 内
        ITE->>ITE: 正常 TurnExecutor 流程(事件走独立 eventBus)
    end
    SR->>SR: 等 MESSAGE_COMPLETED/MESSAGE_ERROR 终态事件
    SR->>SR: 取最后一条 ASSISTANT 消息文本 → persistReportAndReturnSummary<br/>EXECUTOR: 落盘 {planId}/reports/NN-executor.md, 返回尾部1500字符+路径(捕获SPEC_FEEDBACK)<br/>RESEARCHER: 报告始终落盘——有活跃 plan → PlanStore.writeResearchReport 写 {planId}/research.md<br/>无活跃 plan → PlanStore.writeStandaloneResearchReport 写 .mederi/research/{timestamp}.md<br/>(两种均返回头部800字符+路径, 全量报告不再回灌父上下文)
    SR->>SM: 更新状态 COMPLETED/ERROR, 返回摘要
    Note over SM,SR: scope 继承调用方协程上下文: subagent(STOP) 取消可级联取消内部 turn
    SM->>EB: SUBAGENT_COMPLETED/ERROR/STOPPED (payload agentId/role/status/result/reportPath/planId/subtaskIndex)<br/>NonCancellable emit, sessionId=父会话, 同时写 retired[agentId]=AgentRecoveryInfo
    EB->>PTE: SUBAGENT_* 终态事件 → handleSubagentTerminalEvent<br/>组 <event_message> (type/agentId/status/ReportPath/Summary)
    PTE->>PTE: pendingEventMessages 入队(按父会话分组)<br/>父 turn 空闲 → dispatchPendingEventMessages 批量合并成一条内部消息<br/>→ sendMessageInternal 唤起新 turn<br/>竞态(父会话正忙) → 放回队列, 等 turn 收尾 finally 冲刷<br/>abortAndJoin → 清空该会话队列
    PTE->>M: (被唤醒的新 turn) verify_subtask / converge_plan / 下一个 subagent(SPAWN)
    Note over M: 独立子任务可同消息并行: 多个 subagent(SPAWN/SPAWN_RESEARCHER) 各自后台跑,<br/>单会话受并发上限控制(默认 2, 达上限原子拒绝), 完成事件合并唤醒后逐个 verify_subtask
    Note over ITE: 子代理一次任务即死, 无会话残留
```

**briefing 简化 + 报告落盘（方案A 2026-09-24）**：SpawnAgentTool 拼装 briefing 时只注入 ①`task`（来自调用方参数）②`planDetail`（子任务意图/brief，恒不变）——不再注入 `researchNotes` 全文、不再注入 `appendix` 摘录（appendix 字段已删）。executor 是干脏活的便宜模型，需要调研结论时自己 `read_file .mederi/plans/{planId}/research.md`，需要原文件认知时直接 `read_file` 该路径。executor 完成时报告落盘到 `{planId}/reports/NN-executor.md`，父上下文只收尾部 1500 字符 + 路径（尾部捕获 SPEC_FEEDBACK）；researcher 报告**始终落盘**——有活跃 plan 时写 `{planId}/research.md`，无活跃 plan 时经 `PlanStore.writeStandaloneResearchReport` 写 `.mederi/research/{timestamp}.md`，两种情况父上下文都只收头部 800 字符 + 路径（全量报告不再有回灌通道；详情一律 read_file 取盘）。EXECUTOR 无 planId/planStore 或写盘失败时才回退全文回灌。子代理 TurnExecutor 透传 `onTurnDiff` 回调，结束时把文件改动经 `ParentDiffRegistry` 合并进父 turn 的 diffTracker（见 §4 diff 合并说明）。

**per-role 模型解析（2026-09）**：`SpawnAgentTool` / `SpawnResearcherTool` 派发时经 `SubagentConfigManager.resolve(role=EXECUTOR/RESEARCHER, fallbackModel=会话模型, fallbackReasoning=会话推理档)` 覆盖父会话模型——配置了独立模型/推理档则覆盖，否则继承。

## 8. 回滚（rollbackMessage）流程

```mermaid
flowchart TD
    U["用户点击用户消息上的「退回并重新编辑」"] --> VM["WorkspaceViewModel.rollbackMessage(convId, messageId, messageText)"]
    VM --> V1["先验后切: restoreInputFromMessage 反解该消息内容<br/>(主指令 PromptComposer.parse + 大段文本附件 + data: 图片 base64 还原)"]
    VM --> V2["本地切片: messages 截到目标消息之前(UI 零等待)<br/>即时从 subagentStates 剔除被回退子代理(保留消息外全删)"]
    V2 --> AC["AiCore.rollbackToMessage(convId, messageId)"]
    AC --> SM["SessionManager.rollbackToMessage"]
    SM --> EXT["计算 keptAgentIds: 从截断后保留消息中提取全部子代理 ID"]
    EXT --> TE["TurnExecutor.rollbackAndJoin(sessionId, keptAgentIds)"]
    TE --> RS["SubagentManager.rollbackSubagents(sessionId, keptAgentIds)<br/>遍历会话内未保留的子代理: 标记 isDiscarded=true + cancel 协程<br/>发射 SUBAGENT_DISCARDED(UI 剔除) 坚决不发 STOPPED(无感丢弃不惊动 AI)<br/>从 agents 与 retired 内存表中彻底清理"]
    RS --> AB["abortAndJoin(sessionId)<br/>等旧 turn 完全死透(防收尾落库复活已删消息)"]
    AB --> HS["historyStore.replace(id, msgs.take(targetIndex))<br/>(目标不存在显式抛错)"]
    HS --> RE["回退成功 → 内容粘贴回输入框重建待发态:<br/>inputDraft=主指令 + pendingPastedTexts=大段文本 + pendingImages=图片<br/>用户切换模型/模式/Agent、修改后自行发送<br/>(失败 → error 走 ErrorBoard，输入区保持原状)"]
```

**回滚子代理无感丢弃（2026-09）**：当用户回滚到某一条消息时，若该消息时尚未创立子 Agent（即子 Agent 是在后续被回退掉的对话轮次中生成的），这些子 Agent 会被静默丢弃。`SessionManager` 从保留的历史消息中提取所有合法的 `keptAgentIds`，通知 `SubagentManager.rollbackSubagents`。未保留的子代理标记为 `isDiscarded = true` 并取消运行，发射 `SUBAGENT_DISCARDED` 事件通知 UI 即时移出缓存；**坚决不发射 `SUBAGENT_STOPPED` 事件**，不生成 `<event_message>`，绝不向 AI 汇报，真正实现无感丢弃。

## 8.1 浏览器任务（browser，2026-09-14；2026-09 合并为单一入口）

> 主代理只派发/看状态/停止，浏览器内部对主代理完全透明。BROWSER agent 在后台协程跑
> 4-phase 循环（perceive→decide→execute→postprocess），页面快照每步新鲜取、用完即丢，
> memory 是 AI 自总结。细节经 BROWSER_TASK_* 事件给 UI，用户自己看。
> 主代理入口为 `browser` 工具（action=RUN/STATUS/STOP/INFO，委托原 run_browser_task 等四个工具类）。

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant MA as 主代理 turn
    participant RBT as browser(RUN)→RunBrowserTaskTool
    participant BTM as BrowserTaskManager
    participant BO as BrowserOperator(手和眼)
    participant BB as BrowserBrain(大脑)
    participant BC as BrowserControl(Camoufox)
    participant EB as eventBus
    participant WV as WorkspaceViewModel

    U->>MA: "搜一下51job的Java开发工作"
    MA->>RBT: browser(action=RUN, task, browser="camoufox", recipe="job_filter")
    RBT->>BTM: runTask(task, model, projectId, sessionId, browser, recipe)
    BTM->>BTM: resolve(browser)→factory(suspend)→createBrowserControl<br/>tasks[taskId]=STARTED; scope.launch{...}
    BTM->>EB: BROWSER_TASK_STARTED(taskId, STARTED, browser="camoufox")
    RBT-->>MA: {"taskId":"bt_xxx","status":"RUNNING","browser":"camoufox"} (立即返回, 不阻塞)
    MA-->>U: 主代理继续对话(浏览器任务在后台跑)
    Note over WV: 任意 BROWSER_TASK_STARTED：UI 自动展开浏览器面板
    EB-->>WV: BROWSER_TASK_STARTED(任意 browser)
    Note over WV: openDockPanel(BROWSER)（Camoufox 任务监控）
    Note over BTM,BC: 后台执行(与主 turn 并行)
    BTM->>BC: Camoufox: BiDiBrowserControl(binary 已下载)→start()
    BTM->>BO: operator.run(task)
    loop 4-phase 循环(每步)
        BO->>BC: snapshot() → 新鲜 a11y tree
        BO->>BO: LLM 一次调用([system+memory+history+snapshot]) → decision{actions,memory,is_done}
        opt 需要内容判定 (遇到判定需求 / ask_ai)
            BO->>BB: judge(content, instruction, recipeRules)
            BB-->>BO: BrainResult (返回结构化判定)
        end
        BO->>BC: 执行 actions(navigate/click/type/scroll/done)
        BO->>BTM: onStep(step, thought, results)
        BTM->>EB: BROWSER_TASK_STEP(taskId, step, thought, results, browser)
        Note over BO: memory = decision.memory(AI自总结)<br/>stepHistory 保留最后10条<br/>snapshot 用完即丢
    end
    BO-->>BTM: BrowserTaskResult(success, rawMessage)
    BTM->>BB: generateFinalReport(taskHistory, goal, rawMessage, recipeRules)
    BB-->>BTM: BrainResult (一句话简报 + reports/*.md 落盘)
    BTM->>EB: BROWSER_TASK_COMPLETED/ERROR(taskId, executiveSummary, browser)
    Note over U: 用户在任务面板看细节；主代理查 STATUS 拿到一句话简报<br/>任意 BROWSER_TASK_STARTED 即浏览器面板已自动展开（Camoufox 任务监控面板）
    Note over MA: 用户问"任务怎样了?" → 主代理查 browser(STATUS, taskId)
```

> 浏览器选择（2026-09）：`browser`(RUN) 的 `browser` 参数缺省 → BrowserRegistry 默认。
> 内置 JCEF 浏览器宿主已移除，Camoufox（BiDiBrowserControl）是唯一注册源、即默认；
> 任意 BROWSER_TASK_STARTED 都会把浏览器面板展开为 Camoufox 任务监控视图。浏览器面板在所有端均可展开
> （不再依赖内置 JCEF 宿主，遥控端/wasm 同样适用）。

> 浏览器子代理模型解析（2026-09-23）：browser(RUN) 派发时，RunBrowserTaskTool 直传父会话模型/推理档
> （不再按浏览器名解析）；BrowserTaskManager.runTask 后台按角色独立解析——操作者
> （SubagentRole.BROWSER_OPERATOR）与大脑（SubagentRole.BROWSER_BRAIN）经 SubagentConfigManager.resolve
> 各取配置的独立模型/推理档（设置页 Agents 面板与 Executor/Researcher 同款卡片可配模型+推理档+恢复继承），
> 配置了独立模型/推理档则覆盖父会话，否则继承；operator/brain 各建独立 llmCaller
> （BrowserLLMHelper 或注入的 llmCallerProvider），Operator 按操作者模型执行、Brain 按大脑模型执行。
> 浏览器实现（现仅 Camoufox）仍由 browser(RUN) 的 browser 参数选择，与角色配置无关。

## 9. 会话自动改名（SessionTitleService）

```mermaid
sequenceDiagram
    autonumber
    participant TE as TurnExecutor(durable-first 落库)
    participant EB as eventBus
    participant ST as SessionTitleService(jvmMain, 挂 MederiAiCore.initialize)
    participant MC as ModelCatalog(models.dev 价格目录)
    participant OC as OneShotCompletion(复用用户供应商 Koog 链路, 不发推理参数)
    participant SM as SessionManager.rename

    TE->>EB: SESSION_UPDATED(用户消息已落库)
    EB-->>ST: 事件
    ST->>ST: 首次处理该会话?<br/>标题=="New Session" 且恰好一条 user 消息?
    alt 条件满足
        ST->>ST: 选模型(当前供应商优先→其他已连接; 全量模型不过滤 isEnabled)<br/>① 免费(input==0&&output==0)<br/>② 小模型(flash/lite, 排除 mini)
        Note over ST: 有候选 → 逐个请求, 报错换下一个
        ST->>MC: getFor(providerKey, baseUrl, modelId) 查免费/价格
        ST->>OC: execute(provider, model, key): 候选之一<br/>key = preferredApiKeyId(provider)?.let{getKeyValue} ?: 默认<br/>(候选即当前会话供应商时用该会话最近选定 key, 其余默认)
        OC-->>ST: ≤40 字标题
        alt 全部候选失败
            ST->>OC: execute(用户发信息用的模型): 兜底
            alt 兜底也失败
                ST->>ST: session_yyyy-MM-ddTHH:mm:ss
            end
        end
        ST->>SM: rename(id, title)
    end
    Note over ST: 全程静默不重试; 每会话只处理一次
```

## 10. 遥控链路启动（desktop 内嵌 / 独立 server / wasm 密码门）

```mermaid
flowchart TD
    subgraph host1["desktop: main.kt"]
        D1["LaunchedEffect(remoteControlEnabled)"] --> D2["DesktopRemoteControlHooks.start(port, password)<br/>= Server.start(aiCore, port, password, webappDir)"]
        D2 --> D3{"请求端口可用?"}
        D3 -- "是" --> D4["Running(port)"]
        D3 -- "否(被占)" --> D5["port=0 OS 挑空闲 → Running(actualPort, portFallback)"]
        D4 & D5 --> D6["AppState 回写 remote.port 实际端口"]
        D7["startTunnel"] --> D8{"cloudflared 已装?"}
        D8 -- "否" --> D9["UI 提示自行安装"]
        D8 -- "是" --> D10["pty4j 真实 pty 拉起 cloudflared tunnel run<br/>进程退出→OS关pty→SIGHUP→cloudflared 退出"]
    end
    subgraph host2["server: Application.kt"]
        S1["读环境变量(PORT/CONFIG_DIR/PASSWORD/WEBAPP_DIR/RETRY)"] --> S2["MederiAiCore(configDir) + runBlocking initialize()"] --> S3["embeddedServer(Netty){ serverModule(...) }.start(wait=true)"]
    end
    subgraph client["wasmJs 遥控端"]
        W1["ComposeViewport { RemoteGate { MederiApp() } }"] --> W2["探测: GET origin/v1/providers + localStorage 密码 Bearer"]
        W2 -- "200" --> W3["Ready → ServerAiCore.initialize<br/>(轮询 /v1/ready 20s → 拉全局状态)"]
        W2 -- "401" --> W4["密码输入 → 重试 → 写 localStorage"]
    end
    D4 & S3 --> ROUTE["serverModule(aiCore):<br/>Bearer 鉴权 / webapp 托管 / SPA fallback<br/>/v1 路由 → AiCore 方法 / SSE /v1/events"]
```

## 11. 初始化时序（desktop 冷启动全链）

```mermaid
sequenceDiagram
    autonumber
    participant M as main.kt(desktop)
    participant APP as MederiApp/App/MainScreen
    participant ST as AppState
    participant MAC as MederiAiCore
    participant MED as Mederi.create()
    participant SVC as 子服务

    M->>M: PtyTerminalHub() → terminalManager
    M->>M: 后台 CoroutineScope(Dispatchers.Default).launch { DesktopBrowserRuntime.ensureInitialized() } 预热 KBrowser（供 inkcompose mermaid/导出）
    M->>APP: setContent { App() }
    APP->>ST: 创建 AppState(AiCoreProvider.default(), preferences)
    APP->>MAC: initialize()
    MAC->>MED: Mederi.local(configDir)
    MED->>MED: MederiPaths.ensureDirectories + handleLegacyFiles(旧文件须批准)
    MED->>MED: 双库 driver + Schema.create + 7 Store 装配
    MED->>MED: Manager 层 + API 层装配
    MED->>MED: Camoufox BrowserRegistry.register(BiDi 浏览器源, 浏览器任务唯一注册源)
    MAC->>MAC: cleanupLegacyBuiltinProviders / cleanupStaleRunningSessions / syncBuiltinProviders
    MAC->>MAC: refreshGlobalState(4 个 StateFlow) → isReady=true
    MAC->>SVC: modelCatalog.start()(models.dev 每小时刷新)
    MAC->>SVC: autoRefreshBuiltinGoogleModels(后台)
    MAC->>SVC: autotitleService.start()(自动改名)
    MAC->>SVC: startSessionStatusSync()(core 事件流 → _projects 会话状态点, 驱动侧边栏)
    MAC->>SVC: startCamoufoxUpdateCheck()(后台 Camoufox 更新检查)
    APP->>ST: hydrate()(恢复偏好, 列表就绪后 3s 内回填选中态)
    APP->>APP: MainScreen 组合 Sidebar+Workspace, WorkspaceViewModel.attach(选中会话)
    M->>M: 观察 remoteControlEnabled → 自动启停内嵌 Server
```

## 12. apply_patch 工具三阶段（已注销，不注册给 AI——实现保留备用）

```mermaid
flowchart LR
    A["模型产出 Codex 风格补丁<br/>*** Begin Patch / Add/Delete/Update File / Move to / @@ / End Patch"] --> P["PatchParser.parse<br/>逐行状态机; heredoc 剥离兜底<br/>→ List[PatchHunk]"]
    P --> V["verifyHunks: deriveNewContents dry-run 全部校验<br/>(PatchApplier.seekSequence 四级放宽匹配:<br/>精确→trimEnd→trim→Unicode标点归一化)"]
    V -- "任一失败" --> F["拒绝: 磁盘零改动, 返回错误"]
    V -- "全部通过" --> AP["applyHunks: computeReplacements(游标只增不减)<br/>→ applyReplacements(倒序应用)<br/>支持 Add/Delete/Update+Move"]
    AP --> T["产出 A/M/D 摘要 + List[PatchChange]<br/>→ TurnDiffTracker.trackPatch"]
    T --> D["turn 收尾 captureSnapshot → TurnDiff 落 diffs 表<br/>→ UI Diff 面板(unifiedDiff LCS 渲染)"]
```

## 13. 图片发送链路（能力门禁）

```mermaid
flowchart TD
    P1["用户粘贴/选择图片<br/>ClipboardHelper.getImage / tryAttachImage<br/>(剔除放行：模型不支持图片也允许附加，<br/>给一次性轻提示 imageStrippedNotice；附件按钮仍按模型能力显隐)"] --> G1{"guardImageSupport<br/>剔除放行(模型不支持图片时：发送不拒绝，<br/>一次性轻提示 imageStrippedNotice，图片保留在对话历史)"}
    G1 -- "模型不支持(supportsImages=false)" --> N["放行：发送继续，图片从本次 AI 上下文剔除<br/>一次性轻提示：模型不支持图片，发送信息中已剔除（历史保留）"]
    G1 -- "支持" --> A1["ImageAttachment(base64DataUrl) 入 pendingImages"]
    A1 --> SEND["send(text)"]
    SEND --> M1["MederiInputMapper.toMessageParts<br/>图片 → MessagePart.Image(base64 dataUrl)"]
    M1 --> CORE["core SendMessageRequest"]
    CORE --> H1["HistoryStoreChatHistoryProvider(includeImages = model.supportsImages).load<br/>KoogMessageMapper.toKoogMessages(window, includeImages)<br/>用户 Image part → null（AI 上下文无图，历史存储仍带图）"]
    H1 --> K1["KoogMessageMapper.toKoogPart<br/>Image → Attachment(includeImages=true 时图片仍映射为 Attachment)"]
    K1 --> CAP["KoogModelBuilder.buildCapabilities<br/>supportsImages → LLMCapability.Vision.Image<br/>缺 Image 时 Koog 会拒绝图片消息——本方案在 AI 视图层剔除，请求体无图，天然不触发"]
    CAP --> LLM["LLM 供应商"]
```

## 14. 终端（Terminal）数据流

```mermaid
flowchart LR
    TM["PtyTerminalHub(TerminalManager 实现, JVM)<br/>key→常驻 shell(pty4j, TERM=xterm-256color)"] --> |output 流| TS["PtyTerminalSession"]
    TS --> HUB2["64KB scrollback 环形缓冲<br/>(新客户端 attach 先回放再续流)"]
    HUB2 --> TVM["TerminalViewModel(多 tab 状态机: tab/未读/错误/结束)"]
    TVM --> TV["TerminalView(expect: desktop=jediterm+SwingPanel;<br/>wasm/移动端=遥控端占位)"]
    TV -- "write stdin/resize/kill" --> TS
    Note["生命周期: 会话随宿主进程;<br/>进程退出→OS 关 pty master→SIGHUP→shell 退出"]
```

## 15. 状态机汇总

**Session 状态机**：`IDLE → (sendMessage) RUNNING → (正常) IDLE`；`RUNNING → (可重试错误) IDLE(限流提示)`；`RUNNING → (致命错误) ERROR → (下次 sendMessage 前校验失败)`；`RUNNING → (abort) IDLE`。

**契约层会话状态（ConversationStatus，SnapshotReducer）**：4 值 `Idle / Working / Error / WaitingUser`——`QUESTION_REQUESTED`/`PLAN_APPROVAL_REQUESTED` → **WaitingUser**（同时把流式占位消息 isStreaming 置 false，交互挂起），对应 `QUESTION_RESOLVED`/`PLAN_APPROVAL_RESOLVED` → Working；双侧（进程内与 SSE 遥控端）经 `startSessionStatusSync` 同步驱动侧边栏状态点。

**排队/引导（Steering，见 §2.1）**：`RUNNING` 中 `steerMessage` → `pendingSteerings` 入队（发 `STATUS(steering_queued)`，SnapshotReducer 按代码现状忽略）→ 工具边界 `pollSteering` 注入；turn 收尾残留降级新 turn；UI 侧 turn 结束回 Idle 时排队队列自动出队。

**Message 状态机**：`PROCESSING(streaming 占位) → COMPLETED / ERROR`；快照里 isStreaming 对应 PROCESSING。

**Plan 状态机**：`PENDING_APPROVAL → (批准) APPROVED → (spawn 首子任务) IN_PROGRESS → (全子任务 COMPLETED) COMPLETED → archive(plans-done/)`；`PENDING_APPROVAL/APPROVED/IN_PROGRESS → (create_plan 前置 voidActivePlans) VOIDED → .mederi/plans-voided/`；`PENDING_APPROVAL → (拒绝) 停留/用户重试`。

**Subtask 状态机**：`PENDING → (spawn) IN_PROGRESS → (verify PASS) COMPLETED`；`IN_PROGRESS → (verify FAIL/PARTIAL) FAILED → (converge_plan 追加补救 或 regenerate spec) 重执行`。

**ToolCallState（契约层）**：`Pending → Running → Completed / Failed`。

**RemoteServerUiState**：`Idle → Starting → Running(port, portFallback?) / Failed`；**TunnelUiState**：`Idle → Starting → Running(url) / Failed(reason, notInstalled)`（`notInstalled=true` = 本机未装 cloudflared，AppState.kt L72-73）。
