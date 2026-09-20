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

    UI->>UI: guardImageSupport 剔除放行(模型不支持图片 → 剔除本次图片<br/>+ 一次性轻提示 imageStrippedNotice; 历史保留)<br/>PromptComposer.compose(主指令+粘贴文本)<br/>乐观消息入 pendingUserMessages
    UI->>AC: sendMessage(conversationId, ChatPromptInput{text, model, agent, thinkingLevel=effectiveThinkingLevel, apiKeyId=getApiKeyId(model.provider)})
    AC->>AC: MederiInputMapper.toMessageParts / toAgentConfig；记录 lastApiKeyIdByProvider（自动命名跟随用）
    AC->>SM: SessionManager.sendMessage(id, SendMessageRequest{..., apiKeyId})
    SM->>TE: TurnExecutor.sendMessage(sessionId, request)
    TE->>TE: activeApiKeyId = request.apiKeyId（本 turn 唯一真理源）
    TE->>TE: 校验 IDLE → 读 Project → PlanStore.loadBySession<br/>组装 activePlanContent/spec指针/activeTodoContent(互斥)
    TE->>TE: SystemPrompts.build(agentMode, activePlan, activeTodo)<br/>+ withSkills(继承角色) + withProjectRules(AGENTS.md 指令链,<br/>向上:git根→项目目录 + 向下:项目目录直接子目录一层, 浅→深)
    TE->>TE: effectiveModel/effectiveReasoningLevel → sessionStore.updateAgentConfig
    TE->>HS: append(用户消息+durable环境块) 【durable-first】
    TE->>EB: SESSION_UPDATED
    TE->>TE: sessionStore.updateStatus(RUNNING)
    TE->>TE: scope.launch { runTurn() }
    AC-->>UI: (立即返回)

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
        loop 工具循环 (maxAgentIterations=50)
            K->>K: toAssistantMessageSafe(args 坏 JSON 降级 "{}")
            K->>HS: TurnIncrementalPersister.persistAssistant（增量落库防崩丢）
            K->>K: nodeExecuteTools: 执行工具<br/>(文件写白名单/沙盒命令/Plan/Verify/ask_user...)
            K-->>EB: TOOL_CALLED / TOOL_RESULT 事件
            alt ask_user / create_plan(APPROVAL)
                K-->>EB: QUESTION_REQUESTED / PLAN_APPROVAL_REQUESTED (工具协程挂起)
                UI-->>AC: resolveQuestion / resolvePlanApproval
                AC->>TE: resolveQuestion/resolvePlanApproval → deferred.complete
                K->>HS: persistToolResults
            end
            K->>LLM: send_tool_results → requestLLMStreaming
        end
        K->>HS: ChatMemory store → HistoryStoreChatHistoryProvider.reconcile<br/>(指纹对齐/插 SUMMARY/补诊断; 失败退回 replace)
        TE->>HS: (压缩触发时) 插入 SUMMARY 标记消息
        TE->>EB: MESSAGE_COMPLETED (可选 warning)
        TE->>TE: diffTracker.captureSnapshot → diffStore.save(TurnDiff)
        TE->>TE: sessionStore.updateStatus(IDLE)
    end
    UI->>UI: SnapshotReducer.applyWithRefresh + refreshPage 回查落库对齐
```

## 2. Turn 执行流程图（含错误分类）

```mermaid
flowchart TD
    S["sendMessage(sessionId, request, subagentRole?)"] --> V{"会话状态 == IDLE?"}
    V -- "否" --> E1["上抛异常(上游包装 MederiException)"]
    V -- "是" --> PREP["组装上下文: Project/PlanStore/Notebook<br/>activePlanContent(计划+spec指针+活跃spec)<br/>activeTodoContent(仅无活跃Plan)"]
    PREP --> SP["SystemPrompts.build / forSubagent<br/>+ withSkills(继承角色) + withProjectRules(AGENTS.md 指令链:<br/>向上 git根→项目目录 + 向下 直接子目录一层, 浅→深)"]
    SP --> CFG["解析 effectiveModel + effectiveReasoningLevel<br/>回写 sessionStore.updateAgentConfig"]
    CFG --> DF["durable-first: buildUserMessage(注入 NOT_FOR_UI 隐藏标记)<br/>historyStore.append → SESSION_UPDATED → RUNNING"]
    DF --> BG["scope.launch runTurn"]
    BG --> PF{"usedTokens > 70% 窗口?"}
    PF -- "是" --> COMP["compressOnce(mini agent, compressOnlyStrategy)<br/>失败不阻塞"]
    PF -- "否" --> BUILD
    COMP --> BUILD["ToolFactory.build → buildTurnAgent"]
    BUILD --> RUN["agent.run(MEDERI_INPUT_PERSISTED)"]
    RUN --> OK{"正常结束?"}
    OK -- "是" --> DONE["IDLE + MESSAGE_COMPLETED(warning?)<br/>diffTracker.captureSnapshot → diffStore.save"]
    OK -- "异常 e" --> T{"RetryableLLMClient.isTransientError(e)?"}
    T -- "是" --> IDLE["IDLE + MESSAGE_ERROR(分类 RATE_LIMIT)<br/>(RetryableLLMClient 已在流内重试过 STATUS/RETRYING)"]
    T -- "否" --> ERR["ERROR + MESSAGE_ERROR<br/>(ErrorCollector rich payload: 简报/errorId/完整诊断)"]
    DONE & IDLE & ERR --> STOP
    ABORT["abort(id): cancel job → stopAllForSession(级联收割本会话 RUNNING 子代理)<br/>+ cancelAll requester<br/>IDLE + MESSAGE_ERROR(ErrorCollector CANCELLED/WARNING)<br/>(abortAndJoin 同链路, cancelAndJoin 后收割)"] --> STOP["结束"]
```

**事件流消费侧**：`eventBus` → 三路消费：① `MederiAiCore.events()`（进程内）/ `GET /v1/events`（SSE 遥控端）→ 客户端 `SnapshotReducer` 聚合快照；② `launchStreamConsumer` 把 StreamFrame 转 MESSAGE_DELTA；③ UI 层特性（SessionTitleService 等）订阅。

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
    SW -- "STATUS RETRYING" --> ST["statusHint='attempt/max'(+|serverMsg 供应商真实报错)<br/>UI StatusBar 重试态第二行渲染"]
    SW -- "SESSION_UPDATED" --> SU["status=Working, 清旧 errorMessage"]
    D & C2 & ER & TO & PE & TD & ST & SU --> UI["WorkspaceViewModel.snapshot<br/>→ chatItems(derivedStateOf 展平渲染)"]
```

## 4. Plan Loop（复杂改动主流程）

```mermaid
flowchart TD
    U["用户请求"] --> T{"主代理分诊(Triage Flow, 提示词强制)"}
    T -- "纯读" --> R1["直接读文件回答<br/>不够深 → spawn_researcher → 完整报告 → 回答"]
    T -- "小改动(已知根因/几行代码)" --> R2["主代理直接 edit/write<br/>进度走 update_todo(无Plan)"]
    T -- "复杂改动" --> RES["(理解不足先 spawn_researcher)"]
    RES --> CP["create_plan(WHAT, 拆小可验证: 每子任务=spec+verification)<br/>PlanStore.save(.mederi/plans/{id}.json+md)"]
    CP --> MODE{"agentMode"}
    MODE -- "AUTONOMOUS" --> AUTO["自动 APPROVED"]
    MODE -- "APPROVAL" --> WAIT["PLAN_APPROVAL_REQUESTED → UI PlanApprovalCard<br/>用户批准/拒绝(resolvePlanApproval)"]
    WAIT -- "拒绝" --> REJ["告知用户结束/修改"]
    WAIT -- "批准" --> GEN
    AUTO --> GEN["generate_spec(planId, subtaskIndex, spec)<br/>逐子任务派生 HOW(行级规范), brief 恒不变"]
    GEN --> SPAWN["spawn_agent(planId, subtaskIndex)<br/>硬校验 planId/index/spec 存在 → updatePlan 原子置 IN_PROGRESS<br/>PLAN_PROGRESS('subtask-started'+todos投影)<br/>异步派工: 立即返回 agentId(不阻塞父 turn)<br/>独立子任务可同消息并行 spawn(无上限)"]
    SPAWN --> WAITAG["wait_agent(agentId, timeout)<br/>阻塞拿子代理执行报告"]
    WAITAG --> SUB["Executor 子代理(一次性,独立TurnExecutor)<br/>spec 注入其唯一用户消息,自顶向下执行,不问用户<br/>SPEC_FEEDBACK 回报 spec 与现实的矛盾"]
    SUB --> VER["verify_subtask(planId, subtaskIndex, status, evidence)<br/>自动执行 Subtask.verification 命令(10s)"]
    VER --> CHK{"verify 结果"}
    CHK -- "PASS(且命令 exit=0)" --> NEXT["子任务 COMPLETED → 下一个子任务"]
    CHK -- "执行错(FAIL/PARTIAL)" --> CONV["converge_plan 追加补救子任务(append-only) → 重执行"]
    CHK -- "spec 错" --> REGEN["重新 generate_spec 覆盖 → 重执行"]
    NEXT --> MORE{"还有子任务?"}
    MORE -- "是" --> SPAWN
    MORE -- "否" --> ARCH["全部 COMPLETED → plan 置 COMPLETED<br/>planStore.archive → .mederi/plans-done/"]
    CONV & REGEN --> SPAWN
```

**并行执行（2026-09，2026-09-14 异步化）**：工具执行节点 `nodeExecuteTools(parallel=true)`——同一条消息的多个工具调用并行执行，无并发上限，由 AI 调度（信任 AI，代码不设闸门，仅沙箱兜底）。约束：`create_plan` 单独发；**禁止同消息混发 generate_spec 与 spawn_agent**（并行无序，spawn 可能读到未写入的 spec）；同文件并发写已由 `FileWriteRegistry` 代码级硬拒绝（write_file/edit_file try-lock，占用即 Error，AI 下轮重试），不得重复执行同一命令仍靠 AI 自律。plan 状态写入一律走 `PlanStore.updatePlan`（原子读改写），防止并行 spawn/generate_spec/verify 互相覆盖。**子代理异步化（2026-09-14）**：spawn_agent / spawn_researcher 改为异步派工（立即返回 agentId，后台协程跑子代理），父代理 turn 不再被阻塞，可继续对话；plan workflow 中父代理在 spawn 后调 `wait_agent(agentId)` 阻塞拿结果再 verify。

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
    Note over TE: create_plan 挂起等批准期间用户可能切了模型——<br/>同 turn 后续 spawn_agent 动态读 session 拿到新模型
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

### 4.1 子代理生命周期与事件流（spawn → 终态）

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理(spawn_agent 工具)
    participant SS as SessionStore
    participant SM as SubagentManager
    participant SR as SubagentRunnerImpl
    participant EB as eventBus(主)

    M->>SS: get(parentSession) — 动态读模型<br/>(session.aiModel ?: 构造捕获的 turn 模型)
    M->>SM: spawn(task, briefing, spec, aiModel, reasoningLevel, ...)
    SM->>SM: agents[agentId] 占位注册(RUNNING)
    SM->>EB: SUBAGENT_STARTED(agentId, role, modelId, modelName, reasoningLevel, task, briefing?)
    Note over EB: sessionId=父会话；UI SubagentTracker 聚合进<br/>SubagentState 缓存(每个子代理一个 MVVM 对象)
    SM->>SR: 后台协程 run(内存 store + 独立 TurnExecutor)
    alt 正常
        SR-->>SM: 汇报全文
        SM->>EB: SUBAGENT_COMPLETED(agentId)
    else runner 抛错/结果带 [subagent error]
        SM->>EB: SUBAGENT_ERROR(agentId)
    else stop_agent / abort 级联收割
        SM->>EB: SUBAGENT_STOPPED(agentId)(NonCancellable emit)
    end
    Note over M: 父代理 wait_agent(agentId) 拿汇报全文<br/>(tool result 落库; UI 经 SubagentReportMarkdown 转折叠卡片)
```

**abort 级联收割**：用户点"停止"（abort/abortAndJoin）→ cancel 父 turn job → `stopAllForSession(parentSessionId)` 杀本会话全部 RUNNING 子代理——子代理挂全局 scope 不随父 turn 取消而亡，不收割即孤儿（旧模型继续写文件，与"继续"后重 spawn 的新代理并发写同一批 targetFiles）。被杀子代理发 `SUBAGENT_STOPPED`，plan 子任务保持 IN_PROGRESS（"继续"后重 spawn 是干净路径）。

## 5. 上下文压缩流程（自动 + 手动）

```mermaid
flowchart TD
    A["触发1: runTurn 前 preflight<br/>contextUsedTokens(estimate) > 70% 窗口"]
    B["触发2: graphStrategy 节点内<br/>isHistoryTooBig(prompt) > 70%"]
    C["触发3: 用户手动 compressHistory()"]
    A & B & C --> D["MederiCompressionStrategy.compress(llmSession, memory)"]
    D --> E["压缩源 = llmSession.prompt.messages<br/>保留最近 30%(最少5条)原文"]
    E --> F["更早非 system 消息 → requestLLMWithoutTools<br/>生成 TLDR(五节: 关键决策/用户讨论/未完成讨论/当前阶段/关键记忆)"]
    F --> G["新历史 = system + TLDR:... + recent"]
    G --> H["ChatMemory 回写 store → HistoryStoreChatHistoryProvider.reconcile<br/>检测首条 TLDR 未落库 → 按内容指纹对齐<br/>→ 插入 SUMMARY 标记消息(不删任何已有消息)"]
    H --> I["效果: message_history 全量保留(UI 可见/回滚可用)<br/>aiViewWindow = 最后一条 SUMMARY 及其后 → AI 视图变小"]
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

## 7. 子代理（spawn_agent / spawn_researcher / 异步管理）时序

> 2026-09-14：spawn 改为**异步派工**——`SpawnAgentTool` 调 `SubagentManager.spawn` 立即返回 agentId，
> 子代理在后台协程运行，父代理 turn 不被阻塞。需要结果时父代理再调 `wait_agent` 阻塞拿结果，
> 或 `agent_status` 查询、`stop_agent` 主动停止。

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理工具协程(SpawnAgentTool)
    participant SM as SubagentManager
    participant PS as PlanStore
    participant SR as SubagentRunnerImpl<br/>(后台协程)
    participant ITE as 独立 TurnExecutor<br/>(InMemory store + 独立 eventBus)
    participant EB as 主 eventBus

    M->>PS: load(planId) 硬校验 planId/subtaskIndex/spec 非空
    alt 校验失败
        M-->>M: 返回拒绝文本(spec 不存在即拒)
    end
    M->>PS: updatePlan 原子置 subtask IN_PROGRESS
    M->>EB: PLAN_PROGRESS('subtask-started' + todos 投影)
    M->>SM: spawn(task, briefing(含 planDetail), plan=st.spec, role=EXECUTOR, ...)
    SM->>SM: agents[agentId]=RUNNING; scope.launch { SR.run(...) }
    SM-->>M: 立即返回 {"agentId":"sub_xxx","status":"RUNNING"} (不阻塞)
    M-->>M: 主代理继续对话/派发其他任务/调 wait_agent
    Note over SM,ITE: 后台执行(与父 turn 并行)
    SR->>SR: 内存建临时 Session(sub_xxxxxxxx, AUTONOMOUS)
    SR->>ITE: sendMessage(inputText=spec清单自顶向下+SPEC_FEEDBACK约定, subagentRole=EXECUTOR)
    Note over ITE: RESEARCHER 则 inputText=只读调研任务<br/>工具裁剪=read_file+list_directory
    Note over ITE: 继承策略=中心化 AgentCapabilities 表<br/>MCP: 主/执行/研究都继承(每子代理 turn 独立现连现断)<br/>Skills: 主/执行注入提示词, 研究不注入
    loop 子 turn 内
        ITE->>ITE: 正常 TurnExecutor 流程(事件走独立 eventBus)
    end
    SR->>SR: 等 MESSAGE_COMPLETED/MESSAGE_ERROR 终态事件
    SR->>SR: 取最后一条 ASSISTANT 消息文本 → SM 更新状态 COMPLETED/ERROR
    Note over SM,SR: scope 继承调用方协程上下文: stop_agent 取消可级联取消内部 turn
    M->>SM: wait_agent(agentId, timeout) [父代理需要结果时]
    SM-->>M: 返回最终状态+结果(超时 TIMEOUT, 子代理继续后台跑)
    M->>EB: (主代理继续) verify_subtask / converge_plan / 下一个 spawn
    Note over M: 独立子任务可同消息并行: 多个 spawn 各自后台跑,<br/>无并发上限, 由 AI 调度(信任 AI); 逐个 wait_agent 拿结果后 verify_subtask
    Note over ITE: 子代理一次任务即死; 无会话残留
```

## 8. 回滚（rollbackMessage）流程

```mermaid
flowchart TD
    U["用户点击用户消息上的「退回并重新编辑」"] --> VM["WorkspaceViewModel.rollbackMessage(convId, messageId, messageText)"]
    VM --> V1["先验后切: restoreInputFromMessage 反解该消息内容<br/>(主指令 PromptComposer.parse + 大段文本附件 + data: 图片 base64 还原)"]
    V1 --> V2["本地切片: messages 截到目标消息之前(UI 零等待)"]
    V2 --> AC["AiCore.rollbackToMessage(convId, messageId)"]
    AC --> SM["SessionManager.rollbackToMessage"]
    SM --> TE["TurnExecutor.abortAndJoin(sessionId)<br/>等旧 turn 完全死透(防收尾落库复活已删消息)"]
    TE --> HS["historyStore.replace(id, msgs.take(targetIndex))<br/>(目标不存在显式抛错)"]
    HS --> RE["回退成功 → 内容粘贴回输入框重建待发态:<br/>inputDraft=主指令 + pendingPastedTexts=大段文本 + pendingImages=图片<br/>用户切换模型/模式/Agent、修改后自行发送<br/>(失败 → error 走 ErrorBoard，输入区保持原状)"]
```

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
    participant BA as BrowserAgentRunner(后台协程)
    participant BC as BrowserControl(Camoufox/JCEF)
    participant EB as eventBus
    participant WV as WorkspaceViewModel

    U->>MA: "搜一下51job的Java开发工作"
    MA->>RBT: browser(action=RUN, task, browser="jcef")
    RBT->>BTM: runTask(task, model, projectId, sessionId, browser)
    BTM->>BTM: resolve(browser)→factory(suspend)→createBrowserControl<br/>tasks[taskId]=STARTED; scope.launch{...}
    BTM->>EB: BROWSER_TASK_STARTED(taskId, STARTED, browser="jcef")
    RBT-->>MA: {"taskId":"bt_xxx","status":"RUNNING","browser":"jcef"} (立即返回, 不阻塞)
    MA-->>U: 主代理继续对话(浏览器任务在后台跑)
    Note over WV: JCEF 分支：UI 自动展开浏览器面板
    EB-->>WV: BROWSER_TASK_STARTED(browser=="jcef")
    Note over WV: canRenderJcef==true → openDockPanel(BROWSER)
    Note over BTM,BC: 后台执行(与主 turn 并行)
    BTM->>BC: JCEF: createAiTab()→新建 tab=KBPage→JCEFBrowserControl<br/>Camoufox: BiDiBrowserControl(binary 已下载)→start()
    BTM->>BA: runner.run(task)
    loop 4-phase 循环(每步)
        BA->>BC: snapshot() → 新鲜 a11y tree
        BA->>BA: LLM 一次调用([system+memory+history+snapshot]) → decision{actions,memory,is_done}
        BA->>BC: 执行 actions(navigate/click/type/scroll/done)
        BA->>BTM: onStep(step, thought, results)
        BTM->>EB: BROWSER_TASK_STEP(taskId, step, thought, results, browser)
        Note over BA: memory = decision.memory(AI自总结)<br/>stepHistory 保留最后10条<br/>snapshot 用完即丢
    end
    BA-->>BTM: BrowserTaskResult(success, message)
    BTM->>EB: BROWSER_TASK_COMPLETED/ERROR(taskId, message, browser)
    Note over U: 用户自己在浏览器任务面板看细节<br/>JCEF 任务时浏览器面板已自动展开(tab=该任务页面)
    Note over MA: 用户问"任务怎样了?" → 主代理查 browser(STATUS, taskId)
```

> 浏览器选择（2026-09-15）：`browser`(RUN) 的 `browser` 参数缺省 → BrowserRegistry 默认。
> AI 提示词：测用户自己的网页 → `jcef`（内置可见，UI 自动展开浏览器面板显示该 tab）；
> 第三方自动化/抓取 → `camoufox`（无头，不展开面板）。遥控端/wasm 无 JCEF 宿主（canRenderJcef=false），
> 收到 jcef 事件不展开。

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
        D1["LaunchedEffect(remoteControlEnabled)"] --> D2["DesktopRemoteControlHooks.start(port, password)<br/>= RemoteServer.start(aiCore, port, password, webappDir)"]
        D2 --> D3{"请求端口可用?"}
        D3 -- "是" --> D4["Running(port)"]
        D3 -- "否(被占)" --> D5["port=0 OS 挑空闲 → Running(actualPort, portFallback)"]
        D4 & D5 --> D6["AppState 回写 remote.port 实际端口"]
        D7["startTunnel"] --> D8{"cloudflared 已装?"}
        D8 -- "否" --> D9["UI 提示自行安装"]
        D8 -- "是" --> D10["pty4j 真实 pty 拉起 cloudflared tunnel run<br/>进程退出→OS关pty→SIGHUP→cloudflared 退出"]
    end
    subgraph host2["server: Application.kt"]
        S1["读环境变量(PORT/CONFIG_DIR/PASSWORD/WEBAPP_DIR/RETRY)"] --> S2["MederiAiCore(configDir) + runBlocking initialize()"] --> S3["embeddedServer(Netty){ remoteModule(...) }.start(wait=true)"]
    end
    subgraph client["wasmJs 遥控端"]
        W1["ComposeViewport { RemoteGate { MederiApp() } }"] --> W2["探测: GET origin/v1/providers + localStorage 密码 Bearer"]
        W2 -- "200" --> W3["Ready → ServerAiCore.initialize<br/>(轮询 /v1/ready 20s → 拉全局状态)"]
        W2 -- "401" --> W4["密码输入 → 重试 → 写 localStorage"]
    end
    D4 & S3 --> ROUTE["remoteModule(aiCore):<br/>Bearer 鉴权 / webapp 托管 / SPA fallback<br/>/v1 路由 → AiCore 方法 / SSE /v1/events"]
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
    M->>APP: setContent { App() }
    APP->>ST: 创建 AppState(AiCoreProvider.default(), preferences)
    APP->>MAC: initialize()
    MAC->>MED: Mederi.local(configDir)
    MED->>MED: MederiPaths.ensureDirectories + handleLegacyFiles(旧文件须批准)
    MED->>MED: 双库 driver + Schema.create + 7 Store 装配
    MED->>MED: Manager 层 + API 层装配
    MAC->>MAC: cleanupLegacyBuiltinProviders / cleanupStaleRunningSessions / syncBuiltinProviders
    MAC->>MAC: refreshGlobalState(4 个 StateFlow) → isReady=true
    MAC->>SVC: modelCatalog.start()(models.dev 每小时刷新)
    MAC->>SVC: autoRefreshBuiltinGoogleModels(后台)
    MAC->>SVC: autotitleService.start()(自动改名)
    APP->>ST: hydrate()(恢复偏好, 列表就绪后 3s 内回填选中态)
    APP->>APP: MainScreen 组合 Sidebar+Workspace; WorkspaceViewModel.attach(选中会话)
    M->>M: 观察 remoteControlEnabled → 自动启停内嵌 RemoteServer
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

**Message 状态机**：`PROCESSING(streaming 占位) → COMPLETED / ERROR`；快照里 isStreaming 对应 PROCESSING。

**Plan 状态机**：`PENDING_APPROVAL → (批准) APPROVED → (spawn 首子任务) IN_PROGRESS → (全子任务 COMPLETED) COMPLETED → archive(plans-done/)`；`PENDING_APPROVAL → (拒绝) 停留/用户重试`。

**Subtask 状态机**：`PENDING → (spawn) IN_PROGRESS → (verify PASS) COMPLETED`；`IN_PROGRESS → (verify FAIL/PARTIAL) FAILED → (converge_plan 追加补救 或 regenerate spec) 重执行`。

**ToolCallState（契约层）**：`Pending → Running → Completed / Failed`。

**RemoteServerUiState**：`Idle → Starting → Running(port, portFallback?) / Failed`；**TunnelUiState**：`Idle → Starting → Running(url) / Failed(notInstalled)`。
