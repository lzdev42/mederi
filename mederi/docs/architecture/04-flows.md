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

    UI->>UI: guardImageSupport 图片门禁<br/>PromptComposer.compose(主指令+粘贴文本)<br/>乐观消息入 pendingUserMessages
    UI->>AC: sendMessage(conversationId, ChatPromptInput{text, model, agent, thinkingLevel=effectiveThinkingLevel})
    AC->>AC: MederiInputMapper.toMessageParts / toAgentConfig
    AC->>SM: SessionManager.sendMessage(id, SendMessageRequest)
    SM->>TE: TurnExecutor.sendMessage(sessionId, request)
    TE->>TE: 校验 IDLE → 读 Project → PlanStore.loadBySession<br/>组装 activePlanContent/spec指针/activeTodoContent(互斥)
    TE->>TE: SystemPrompts.build(agentMode, workType, activePlan, activeTodo)
    TE->>TE: effectiveModel/effectiveReasoningLevel → sessionStore.updateAgentConfig
    TE->>HS: append(用户消息+durable环境块) 【durable-first】
    TE->>EB: SESSION_UPDATED
    TE->>TE: sessionStore.updateStatus(RUNNING)
    TE->>TE: scope.launch { runTurn() }
    AC-->>UI: (立即返回)

    rect rgb(235, 244, 255)
        note over TE,LLM: runTurn（后台协程）
        TE->>TE: preflightCompressionIfNeeded<br/>(contextUsedTokens > 70% 窗口 → compressOnce)
        TE->>TE: ToolFactory.build(工具集, agentMode/role裁剪)
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
    PREP --> SP["SystemPrompts.build / forSubagent"]
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
    T -- "是" --> IDLE["IDLE + MESSAGE_COMPLETED(限流提示)<br/>(RetryableLLMClient 已在流内重试过 STATUS/RETRYING)"]
    T -- "否" --> ERR["ERROR + MESSAGE_ERROR"]
    DONE & IDLE & ERR --> STOP
    ABORT["abort(id): cancel job + cancelAll requester<br/>IDLE + MESSAGE_ERROR('用户中止了对话')"] --> STOP["结束"]
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
    SW -- "MESSAGE_ERROR" --> ER["回查后发最终快照(status=Error)"]
    SW -- "TOOL_CALLED/RESULT" --> TO["按 toolCallId 精确匹配更新 ToolCall block"]
    SW -- "QUESTION_*/PLAN_APPROVAL_*" --> PE["写/清 pendingQuestion / pendingPlanApproval"]
    SW -- "TODO_UPDATED/PLAN_PROGRESS" --> TD["解码 payload.todos 整体替换(失败丢弃事件)"]
    SW -- "STATUS RETRYING" --> ST["statusHint='供应商限流,重试中(attempt/max)'"]
    SW -- "SESSION_UPDATED" --> SU["status=Working, 清旧 errorMessage"]
    D & C2 & ER & TO & PE & TD & ST & SU --> UI["WorkspaceViewModel.snapshot<br/>→ chatItems(derivedStateOf 展平渲染)"]
```

## 4. Plan Loop（复杂改动主流程）

```mermaid
flowchart TD
    U["用户请求"] --> T{"主代理分诊(Triage Flow, 提示词强制)"}
    T -- "纯读" --> R1["直接读文件回答<br/>不够深 → spawn_researcher → 完整报告 → 回答"]
    T -- "小改动(已知根因/几行代码)" --> R2["主代理直接 edit/write/apply_patch<br/>进度走 update_todo(无Plan)"]
    T -- "复杂改动" --> RES["(理解不足先 spawn_researcher)"]
    RES --> CP["create_plan(WHAT, 拆小可验证: 每子任务=spec+verification)<br/>PlanStore.save(.mederi/plans/{id}.json+md)"]
    CP --> MODE{"agentMode"}
    MODE -- "AUTONOMOUS" --> AUTO["自动 APPROVED"]
    MODE -- "APPROVAL" --> WAIT["PLAN_APPROVAL_REQUESTED → UI PlanApprovalCard<br/>用户批准/拒绝(resolvePlanApproval)"]
    WAIT -- "拒绝" --> REJ["告知用户结束/修改"]
    WAIT -- "批准" --> GEN
    AUTO --> GEN["generate_spec(planId, subtaskIndex, spec)<br/>逐子任务派生 HOW(行级规范), brief 恒不变"]
    GEN --> SPAWN["spawn_agent(planId, subtaskIndex)<br/>硬校验 planId/index/spec 存在 → 子任务 IN_PROGRESS<br/>PLAN_PROGRESS('subtask-started'+todos投影)"]
    SPAWN --> SUB["Executor 子代理(一次性,独立TurnExecutor)<br/>spec 注入其唯一用户消息,自顶向下执行,不问用户<br/>SPEC_FEEDBACK 回报 spec 与现实的矛盾"]
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
    UI->>TE: resolvePlanApproval(convId, planId, approved)
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

## 7. 子代理（spawn_agent / spawn_researcher）时序

```mermaid
sequenceDiagram
    autonumber
    participant M as 主代理工具协程(SpawnAgentTool)
    participant PS as PlanStore
    participant SR as SubagentRunnerImpl
    participant ITE as 独立 TurnExecutor<br/>(InMemory store + 独立 eventBus)
    participant EB as 主 eventBus

    M->>PS: load(planId) 硬校验 planId/subtaskIndex/spec 非空
    alt 校验失败
        M-->>M: 返回拒绝文本(spec 不存在即拒)
    end
    M->>PS: subtask 置 IN_PROGRESS
    M->>EB: PLAN_PROGRESS('subtask-started' + todos 投影)
    M->>SR: run(task, briefing(含 planDetail), plan=st.spec, role=EXECUTOR, ...)
    SR->>SR: 内存建临时 Session(sub_xxxxxxxx, AUTONOMOUS)
    SR->>ITE: sendMessage(inputText=spec清单自顶向下+SPEC_FEEDBACK约定, subagentRole=EXECUTOR)
    Note over ITE: RESEARCHER 则 inputText=只读调研任务<br/>工具裁剪=read_file+list_directory
    loop 子 turn 内
        ITE->>ITE: 正常 TurnExecutor 流程(事件走独立 eventBus)
    end
    SR->>SR: 等 MESSAGE_COMPLETED/MESSAGE_ERROR 终态事件
    SR->>SR: 取最后一条 ASSISTANT 消息文本
    SR-->>M: 返回报告字符串(异常转 "[subagent error] ...")
    M->>EB: (主代理继续) verify_subtask / converge_plan / 下一个 spawn
    Note over ITE: 子代理一次任务即死; 无会话残留
```

## 8. 回滚（rollbackMessage）流程

```mermaid
flowchart TD
    U["用户点击某消息回滚并重发"] --> VM["WorkspaceViewModel.rollbackMessage(convId, messageId, messageText)"]
    VM --> V1["先验后切: 提取该消息附件(图片/粘贴文本)+图片门禁"]
    V1 --> V2["本地切片: messages 截到目标消息之前"]
    V2 --> V3["乐观消息更新 UI"]
    V3 --> AC["AiCore.rollbackToMessage(convId, messageId)"]
    AC --> SM["SessionManager.rollbackToMessage"]
    SM --> TE["TurnExecutor.abortAndJoin(sessionId)<br/>等旧 turn 完全死透(防收尾落库复活已删消息)"]
    TE --> HS["historyStore.replace(id, msgs.take(targetIndex))<br/>(目标不存在显式抛错)"]
    HS --> RE["用户编辑后的消息作为新输入重发(复用 sendMessage 链路)<br/>thinkingLevel 只发 effectiveThinkingLevel"]
```

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
        ST->>OC: execute(provider, model, key): 候选之一
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

## 12. apply_patch 工具三阶段

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
    P1["用户粘贴/选择图片<br/>ClipboardHelper.getImage / tryAttachImage"] --> G1{"guardImageSupport<br/>(send 与 rollbackMessage 共用唯一实现)"}
    G1 -- "模型不支持(supportsImages=false)" --> X["拦截并提示(附件按钮显隐+粘贴拦截双门禁)"]
    G1 -- "支持" --> A1["ImageAttachment(base64DataUrl) 入 pendingImages"]
    A1 --> SEND["send(text)"]
    SEND --> M1["MederiInputMapper.toMessageParts<br/>图片 → MessagePart.Image(base64 dataUrl)"]
    M1 --> CORE["core SendMessageRequest"]
    CORE --> K1["KoogMessageMapper.toKoogPart<br/>Image → Attachment"]
    K1 --> CAP["KoogModelBuilder.buildCapabilities<br/>supportsImages → LLMCapability.Vision.Image<br/>(缺 Image 时 Koog 发送直接拒绝——历史事故)"]
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
