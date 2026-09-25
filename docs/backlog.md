# Mederi 待办清单

> 记录于 2026-09-08，基于端到端测试 + 限流重试修复过程中的发现

## 一、UI/UX 对接缺口

本轮新增/修改的功能中，以下项**代码侧已完成但尚未与 UI 对接或未做 UX 优化**：

| # | 功能 | core/contract 侧状态 | UI 缺口 |
|---|---|---|---|
| U1 | **限流重试设定（LlmRetryConfig）** | 进程级单例可写穿，server 侧环境变量 `MEDERI_LLM_RETRY_MAX/_MIN_MS/_MAX_MS` 可用 | 桌面 app 设置页无入口。需要：SettingsScreen 加数字输入框 → AppState → 写穿 LlmRetryConfig（同 SandboxConfig.extraWritablePaths 模式） |
| U2 | **"重试中"状态显示** | `STATUS` 事件 → SnapshotReducer 写 `statusHint` → `deriveTurnStatus` 返回 `Retrying` —— 代码路径完整编译通过 | **从未启动桌面/wasm UI 视觉确认**"重试中"是否真渲染在状态栏。需要：Compose Hot Reload MCP 启动桌面 app → 触发限流 → `get_semantic_tree` 检查状态栏文案 |
| U3 | **PlanStatus.IN_PROGRESS 从未设置** | ✅ 已修复（2026-09-08）：spawn_agent 执行前设 SubtaskStatus.IN_PROGRESS + PlanStatus.APPROVED→IN_PROGRESS，verify_subtask 设终态。日志实测 `planStatus=IN_PROGRESS` | UI 的"当前进行中的子任务"现在有数据源了，但 UI 侧是否展示该状态未验证 |
| U4 | **子代理/shell 写文件不进 diffTracker** | spawn_agent 子代理写的文件、execute_command 用 shell 改的文件都不进父会话 diff 面板 | diff 差异审查面板对计划路径盲区。设计取舍待定：子代理回传 diff 给父代理汇总，或 diff 面板改为磁盘快照对比 |

---

## 二、P0 修复计划

> **状态：2026-09-08 P0-1 / P0-2 已修复并端到端验证。**

### P0-1：assistant 消息 mid-turn 不落库 ✅ 已修复

**修复实现**（比原计划多修了一个连带 bug）：
1. **`TurnIncrementalPersister`**（新文件，`core/.../infrastructure/koog/`）：两个锚点增量落库——
   `nodeCallLLM` 拿到完整 response 后 `persistAssistant`；`nodeLLMSendToolResults` 前由
   `nodeLLMSendToolResultsPersistable`（Mederi 包装节点）`persistToolResults`。
   重复写防护依赖 HistoryStoreChatHistoryProvider 内容指纹 reconcile（strategy 级 store 识别已存在消息）。
2. **连带修复：碎片化工具调用合并**（`MederiAgentStrategies.mergeFragmentedToolCalls`）——
   deepseek 等端点把一次工具调用拆成多片（首片带 id+name，续片只有 args 增量），且 Koog
   StreamFrameFlowBuilder 是"过渡即冲刷"语义（text 与 tool call 挤兑 pending 槽），
   中间的 text delta 把进行中的调用提前冲成 Complete——一次 read_file 重建成
   `[Call(read_file,{}), Call({}), Call({}), Call({})]` 空壳序列，污染消息历史、
   下轮模型调空工具。修复：重建前合并相邻碎片（id/name 均空的 Complete 并入前一个调用，
   中间的空 Text 帧是挤兑副产品、不闭合调用）。**注意：不可在 MederiOpenAILLMClient 层缓冲续片**，
   那会绕过 Koog builder 的合并语义反而拆帧（试过错，已回滚）。

**验证**（`MEDERI_LLM_RETRY_MAX=1` 强制耗尽路径）：
- 修复前：turn 崩溃后 raw 消息仅 USER 文本，3 次工具调用消失
- 修复后：崩溃前的 assistant（含完整 args 的 tool call）+ tool results 均在 raw 消息中；
  正常路径 deepseek S1 2/2 调用全 OK、agnes S1 4/4 OK、T1 PASS + plans-done 归档正常

### P0-2：List<DecisionArg>/List<PlannedChangeArg> 无宽松化 ✅ 已修复

**修复实现**：`PlanTools.kt` 新增类级 `JsonTransformingSerializer`：
- `coerceObjectListField(element, fieldName)`：对指定字段做形状矫正（JsonArray 原样 / JsonObject 包单元素数组 /
  JsonPrimitive 解析字符串内容为 JsonArray）
- `LenientCreatePlanArgs`（`@KeepGeneratedSerializer` + `@Serializable(with=...)` 组合）：
  先矫正 `subtasks[*].decisions`，再矫正顶层 `keyDecisions`/`changes`
- `LenientSubtaskArg`：矫正 `decisions`
- `LenientStringList`（原有 List<String> 宽松化）不变

**验证**：T1 场景 create_plan 首调即成功（修复前模型首调崩在字段形状、自愈浪费 1-2 轮）。

---

## 三、P2 打磨计划

> **状态：2026-09-08 两项已完成。**

### P2-1：verify_subtask 显示与 0 基索引错位 ✅ 已修复
统一为 `Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size})`，带总数上下文。

### P2-2：模型收尾纪律 ✅ 已修复（提示词侧）
`SystemPrompts.PLANNING_DISCIPLINE` 新增 Completion rule (hard)：write_log 仅允许在
全部子任务 verified PASS 后调用；有 PENDING/FAILED/IN_PROGRESS 子任务时继续 Plan Loop。
（代码门禁暂不加，观察提示词效果）

---

## 四、未测试覆盖盲区（后续测试记录）

以下分支/路径**从未被任何测试场景触达**，是风险面积。按风险排序：

| 优先级 | 未测项 | 风险描述 | 测试方式建议 |
|---|---|---|---|
| P1 | **APPROVAL 模式**（计划批准挂起/恢复） | 整条 suspend/resume 路径零测试：PlanApprovalRequester 挂起 turn、UI 审批卡、resolvePlanApproval endpoint、恢复 turn | 用 APPROVAL agent 建 session，提交一个需要 create_plan 的任务，验证会话挂起等批准 → curl resolvePlanApproval → 恢复 |
| P1 | **converge_plan 故意 FAIL 分支** | 只自然触发过一次（sess_ccef7dfb），未刻意验证完整链路：verify FAIL → converge_plan → 新 spec → spawn → verify PASS → 归档 | 构造一个 spec 故意写错（如要求创建不存在的文件）→ 验证 FAIL → converge → 修正 → PASS → 归档 |
| P2 | **spawn_researcher** | 零测试。只读工具集（read_file + list_directory）沙箱是否真限制写、子代理是否真不创建持久 session | 提示词"调研 pkg/ 代码结构并报告"，验证 researcher 只读不写 |
| P2 | **沙箱拒绝路径** | 只测过"写白名单内成功"。白名单外写入被拒的错误反馈、execute_command 沙箱拒绝 | 提示词"在 /tmp 下创建文件"→ 验证被拒 + 错误信息 |
| P2 | **自动压缩（70% pre-flight）** | 从未触发。SUMMARY 窗口、压缩标记、压缩后行为 | 构造超长对话（重复发消息直到 >70% contextWindow）→ 验证压缩触发 + 后续行为 |
| P2 | **WORK 模式** | 零测试。WORK 与 CODE 的工具集差异、提示词差异 | 用 WORK agent 建会话，发一个非编码任务 |
| P2 | **ask_user** | 零测试。问题挂起/恢复流程 | 提示词含模糊指令 → 验证模型调 ask_user → curl resolveQuestion → 恢复 |
| P3 | **rollbackToMessage** | 零测试。回滚后历史/状态一致性 | 手动触发回滚到某条消息 → 验证后续消息被删 + 下轮模型上下文正确 |
| P3 | **maxAgentIterations 撞顶** | 未知：50 次迭代后行为？会话状态？错误信息？ | 构造一个无限循环的提示词（"不停 read 同一个文件"）→ 观察 50 次后 |
| P3 | **Linux/Windows 沙箱** | macOS only。bwrap 探测链、Windows 降级 | 需对应平台环境，暂记 |

---

## 五、已修复 bug 清单（本批次）

| # | bug | 文件 | 验证方式 |
|---|---|---|---|
| 1 | ReadFileArgs @Serializable 被注释吞掉 | FileSystemTools.kt | S1 PASS + 变异测试（回滚 → FLOW_FAIL） |
| 2 | generate_spec 漏注册 PLAN_TOOL_NAMES | ToolFactory.kt | S2 PASS（3 spec → 3 spawn → 3 verify） |
| 3 | DecisionArg 必填字段无默认值 | PlanTools.kt | S2 create_plan 1 次成功 |
| 4 | 提示词缺 create_plan JSON 范例/报错即停规则 | PromptGuides.kt + SystemPrompts.kt | S2 模型首调参数形状正确 |
| 5 | 边注册顺序：Text+Call 混排丢工具调用 | MederiAgentStrategies.kt | S1 混排消息 4/4 全执行 |
| 6 | 计划归档无触发点（isAllCompleted/archive 孤儿） | VerifyTools.kt | T1 plans-done + COMPLETED 实锤 |
| 7 | 限流直接进会话 ERROR | RetryableLLMClient.kt + TurnExecutor.kt | M2 STATUS 事件 + session=Idle 实锤 |
| 8 | deepseek 分片工具调用 → 空壳 Call 污染历史+事件流 | MederiAgentStrategies.kt（mergeFragmentedToolCalls）+ TurnExecutor.kt（consumer 跳过 blank name 碎片帧） | deepseek S1：TOOL_CALLED 6→2、rebuilt [Call] args 完整 |
| 9 | nodeSendCompressedHistory 缺增量持久化 | MederiAgentStrategies.kt | 编译通过（压缩路径端到端仍未测，见盲区表） |
| 10 | PlanStatus/SubtaskStatus.IN_PROGRESS 死状态 | SpawnAgentTool.kt | T1 日志实测 planStatus=IN_PROGRESS |
| 11 | 工具轮后 LLM 响应不流式 | MederiAgentStrategies.kt（nodeSendToolResult/nodeSendCompressedHistory 改 requestLLMStreaming） | A2 拒绝后最终回复 DELTA(text) 实测 |
| 12 | 拒绝语义空洞导致模型非确定（盲目重提/疯狂循环） | PlanTools.kt（拒绝分支明确指引直接文本询问）+ SystemPrompts（Rejection rule hard） | A2 PASS |
| 13 | verify_subtask 纯登记（evidence 全凭模型自述）→ executor 自报成功但产物错误时归档了 COMPLETED | VerifyTools.kt（auto-run verification 命令 + exit 非 0 拒绝 PASS）+ ShellTools.kt（抽出 runCommand 共用）+ SystemPrompts（assert 式验证 nudge） | converge 全链 PASS |
| 14 | ~~手动压缩 endpoint 是死的~~ → 复审推翻：代码改动是 no-op，真问题是测试消息数不足。compress 在 compressOnly 路径依赖 prompt.messages（ChatMemory preprocessor 装载），2 轮对话 6 条 → n=5 → older=1 < 2 → **设计内的合理 no-op**。修复 = 测试加第三轮（8 条非 system → older=3）。代码回退原实现 + 注释写明 no-op 阈值 | 无代码改动（回退）；e2e-p2b.sh 加第三轮 | R3 PASS（TLDR 落库 + 事实保留）；LenientCreatePlanArgsTest + ConvergePlanMechanismTest 单测全绿 |

### P2 盲区测试结果（2026-09-08，scripts/e2e-p2a.sh + e2e-p2b.sh）

| 场景 | 结果 | 备注 |
|---|---|---|
| R1 spawn_researcher 只读 | PASS | 报告含真实文件内容；researcher 被诱导写文件也未产生新文件 |
| R2 写路径拒绝 | PASS | 白名单外写入被拒，"outside project directories" 进消息历史，turn 正常收尾 |
| R3 手动压缩 | PASS（测试修正后） | TLDR 落库 + 压缩后模型仍记得预算/日期；需 ≥3 轮问答才过压缩阈值（设计如此） |
| R4 WORK 模式 | PASS | 非编码任务（总结文档）正常 |
| R5 ask_user | PASS | QUESTION_REQUESTED 挂起 → resolveQuestion 恢复 → 用答案写文件 |

遗留观察（模型方差，非产品缺陷）：agnes 在 verify 被拒后偶有不调 converge_plan 而直接自己修的情况
（提示词已明确指引，模型遵循度有波动）；deepseek 限流会干扰 converge 场景的失败注入时序。

### 已知遗留（有意不修）

| # | 问题 | 理由 |
|---|---|---|
| 1 | 计划被遗弃后永远 ACTIVE（归档只在全 PASS 触发） | 低风险：loadBySession 按 session 隔离；重开计划由模型侧 create_plan 覆盖。如出现堆积再加 TTL/session 结束清理 |
| 2 | 判定器把"failed to parse arguments"（模型缺字段）计入流程侧故障 | T1 出现过 2 次 agnes 缺 title 的模型侧笔误被判 FLOW_FAIL（重跑 PASS）。可收紧签名排除 "Field 'x' is required"，但会弱化真序列化 bug 的检出——暂保留 |
| 3 | `AgentOption.tools` 契约从未接线 | TurnExecutor:537 硬编码 `ALL_TOOL_NAMES`；API 传的 tools 列表被忽略。converge 测试中需要摘掉主会话写工具时无法通过 API 实现。属功能接线，非 bug fix——记录待做 |
| 4 | SPEC_FEEDBACK 处置无规范 | executor 诚实回报 spec 与现实矛盾，但主代理对 SPEC_FEEDBACK 的处理无提示词指引（模型会绕过 converge 自己直接修）。需在 SystemPrompts 加 SPEC_FEEDBACK → re-generate_spec 的明确指引 |
