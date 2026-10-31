# AI 运行时内部机制

> 本文档从 AGENTS.md 拆出，记录 AI 运行时的**机制实现细节**（沙盒实现、子代理事件机制、上下文挂载、审计备忘）。
> AI 作为使用者**不需要时刻背这些**——只在排查系统自身行为、或改动系统内部机制时读。
> 规则/不变量/真理源仍在 AGENTS.md。

## 1. 执行沙盒实现细节

**安全模型 = 分诊判断 + 沙箱**（无逐操作询问）：主代理按分诊流程自主判断执行路径，
沙盒（路径/命令强制）兜底。不变量 = 文件工具只能写项目目录、bash 走 OS 级写沙箱（macOS Seatbelt / Linux bwrap 检测 / Windows 降级警告）、读全盘放行。全局白名单在设置页配置。

**不变量（代码强制，全平台）**：
- 文件工具（write_file/edit_file/apply_patch）只能写项目 directories + `.mederi/` + 全局白名单；读全盘放行
- **同文件并发写硬拒绝（2026-09）**：write_file/edit_file/apply_patch 落盘前经进程级 `FileWriteRegistry`（`tools/FileWriteRegistry.kt`）try-lock——文件已被其他并发工具调用占用则直接返回 Error，写完（含异常）finally 释放；键 = canonical path，跨 turn/跨子代理/跨 session 生效。bash 与 MCP 工具是外部进程，不在注册表管辖内（提示词约束兜底）
- bash：macOS Seatbelt（sandbox-exec）/ Linux bwrap（只检测不代装）/ Windows 降级警告；shell 探测链 bash→sh（Windows bash.exe→cmd）
- **进程回收**：每条命令是独立进程组，bash 启动即登记 `ProcessRegistry`；`list_processes`/`stop_process` 宿主侧（沙箱外）按注册表整组回收——macOS 沙箱内信号不可用（profile 无 process-signal，实测 unbound），只能靠宿主侧。**只杀 mederi 自己启动的进程**，注册表查不到 pid 即拒绝，沙箱内命令无法写注册表
- AgentMode 两模式工具集完全一致，**唯一区别 = 计划批准者**：APPROVAL 等用户批准，AUTONOMOUS 自动批准立即执行
- `subagent`(action=SPAWN) planId/subtaskIndex 可选：携带 planId+subtaskIndex 时硬校验 spec 存在性（spec 不存在即拒）；不带 planId 走 ad-hoc 执行路径（task+briefing 直接派 executor，无 spec 门禁）

**项目目录模型**：`directories.first()` = 主目录（承载 `.mederi/` 工作区、shell cwd、相对路径解析首选），
其余目录平等读写。多目录 containment 白名单有效。

**并行工具调度（2026-09 定稿）**：Koog 工具执行节点已开 `parallel=true`——同一条消息里的多个工具调用并行执行，**无并发上限，由 AI 调度**（信任 AI 策略）。配套硬性约束：
- `create_plan` 必须单独发一条消息，不得与任何工具同消息批量；
- **禁止同消息混发 `generate_spec` 与 `subagent`(SPAWN)**（并行无序，spawn 可能读到未写入的 spec）——先为所有独立子任务生成 spec，再同消息一起 subagent(SPAWN)；
- 并行批量时杜绝竞争：同文件并发写已由 `FileWriteRegistry` 代码级硬拒绝（占用即 Error，AI 下轮重试）；不重复执行同一命令仍靠 AI 自律（shell 是外部进程，管不了）。
- 因并行而生，plan 状态写入必须原子：`subagent`(SPAWN) 的 IN_PROGRESS 标记、`generate_spec` 的 spec 写入、`verify_subtask` 的验证结果一律走 `PlanStore.updatePlan`（原子读改写，进程级锁），禁止裸 load→copy→save。

**系统环境注入**：每条用户消息尾部 `<<<NOT_FOR_UI>>>` 隐藏块注入时间、OS/版本/arch、
shell 与沙箱状态、项目目录、Java 版本——AI 需要知道但不该让用户重复输入的环境事实。

**提示词拼装**：`core/prompt/SystemPrompts.kt` 是骨架（身份/原则/工具清单/工作流拼接），
`core/prompt/PromptGuides.kt` 是教程素材库（SANDBOX_USAGE 等）——新主题在此加常量往骨架拼。

## 2. 分诊流程的角色与上下文挂载

### 三角色

| 角色 | 职责 | 工具 | 生命周期 |
|---|---|---|---|
| 主代理 | 唯一决策者：分诊、调研结论、计划、验证、收敛；小改动亲自动手 | 全套（含写） | 长期存活 |
| Researcher | 眼睛：只读深度调研，输出完整报告，不做决定、不写文件 | read_file + list_directory（+ MCP 工具） | 一次任务即死 |
| Executor | 手：照 spec 清单自顶向下执行，不问用户，不猜——5 类 SPEC_FEEDBACK（missing/ambiguous/vs-reality/unsatisfiable/compile-blocked）回报 spec 缺失/歧义/矛盾；编译命令由 spec 指定，不自行发现 | 全套（无 plan/spawn/verify/ask_user）+ MCP 工具 + skills 提示词注入 | 一次任务即死 |

> **代理继承策略中心化（硬性）**：主/子代理"是否继承 MCP / 注入 skills"一律读 `AgentCapabilities`（domain/model/AgentCapabilities.kt）这张**唯一表**——`MAIN(true,true)` / `EXECUTOR(true,true)` / `RESEARCHER(true,false)`。TurnExecutor 开 MCP 会话、SystemPrompts 注入 skills 段都只查此表，**禁止**在别处再写 `subagentRole == EXECUTOR` 之类的散装判断。新增子代理角色 = `SubagentRole` 加值 + `AgentCapabilities` 加行 + `of()` 加分支，其余装配自动生效。

### 上下文挂载（防失忆，方案A 2026-09-24）

- **主代理**：每轮 turn 从 PlanStore 现读活跃计划，只把**动态状态**挂进系统提示词的 `# Active Plan` 段——
  title/status/businessLogic + 进度计数（passed/failed/pending/in-progress）+ `Current: Subtask N` 指针行 +
  每个子任务的一行状态速览（`- [status] Subtask N: name`）+ 活跃子任务 spec + 已验证子任务最近一次验证结果（status/gapType/evidence）。
  静态详情（brief/targetFiles/verification 命令/decisions）**不再挂**——`plan.md` 落盘在
  `.mederi/plans/{planId}/plan.md`，主代理按需 `read_file` 取（活动子任务 spec 仍挂：spawn 前确认要用）。
  段尾给两个路径指针：`plan.md`（静态详情）+ `research.md`（researcher 报告）。
- **轻量 todo（update_todo，2026-09）**：todo-list 与 plan 彻底解耦——todo-list 只由 update_todo 驱动（session.todos 列，每轮挂 `# Current Todo` 段，turn 边界刷新），plan 期间也允许 update_todo 记录 plan 之外的独立事项；plan 子任务展示由 planApprovals 投影表驱动
- **Executor**：spec（Subtask.spec）+ brief（planDetail）注入其唯一一条用户消息；
  不再注入 researchNotes 全文或 appendix 摘录（appendix 字段已删）。需要调研结论时 read_file
  `.mederi/plans/{planId}/research.md`；需要某个原文件认知时直接 read_file 该路径——executor 是
  干脏活的便宜模型，自己读盘，模型不必预先转述文件内容。报告完成时落盘到
  `.mederi/plans/{planId}/reports/NN-executor.md`，父上下文只收摘要+路径（尾部 1500 字符 + 路径，捕获
  SPEC_FEEDBACK）。
- **Researcher**：研究报告始终落盘——有活跃 plan 时（spawn 时 planStore.loadBySession 返回非空）
  落 `.mederi/plans/{planId}/research.md`；无活跃 plan 时（分诊阶段调研）经
  `PlanStore.writeStandaloneResearchReport` 落 `.mederi/research/{timestamp}.md`；父上下文都只收摘要
  （头部约 800 字符）+ 路径。

### 工作文件落地（方案A 2026-09-24）

主↔子代理交互只传简介；全量内容落盘到 `.mederi/plans/{planId}/` 下，谁需要谁读全：

| 文件 | 内容 | 谁写、何时 | 谁读、何时 |
|---|---|---|---|
| `plans/{planId}/plan.json` | 聚合根（含全部动态状态：subtasks[].status/spec/verificationResult/executorTouchedFiles/researchNotes） | create_plan 写；后续 spawn/verify/converge 通过 updatePlan 原子改动态字段 | PlanStore.load（代码内部） |
| `plans/{planId}/plan.md` | 人读投影（动态渲染） | save 同步写 | UI 批准卡片、用户查看；主代理按需 read_file 取静态详情 |
| `plans/{planId}/research.md` | researcher 报告全文 | researcher 子代理完成时落盘（有活跃 plan 才落） | executor 需要调研结论时 read_file；主代理按需 |
| `.mederi/research/{timestamp}.md` | researcher 独立研究报告（无活跃 plan 时落盘） | researcher 子代理完成时经 `PlanStore.writeStandaloneResearchReport` 落盘 | 主代理按需 read_file（父上下文收摘要+路径） |
| `plans/{planId}/reports/NN-executor.md` | executor 报告全文（per-subtask） | executor 完成时落盘 | 主代理按需 read_file（摘要+路径默认回灌） |
| `plans/{planId}/walkthrough.md` | 完成总结 | 全部 PASS 时 writeWalkthrough 装配（archive 时随之移到 plans-done/{planId}/） | 用户阅读；AI 可补 Notes 段 |
| `plans-done/{planId}/...` | 归档整个计划目录（同结构复制） | archive(planId) 触发 | 审计留痕 |
| `plans-voided/{planId}/...` | 作废整个计划目录（同结构移动） | voidActivePlans → moveToVoided 触发 | 作废留痕 |

**不落盘的内存字段**：spec 文本（落 Subtask.spec 在 plan.json 内）；executorProgress/executorTouchedFiles（落 plan.json）。

### 子代理主动上报（异步唤醒，2026-09-25）

子代理终态时 `SubagentManager` 向主 `eventBus` 发终态事件**主动通知父会话**，父代理不再需要 WAIT 阻塞拉取：

- **完成即唤醒**：子代理后台协程终态（COMPLETED/ERROR/STOPPED）时，`SubagentManager` 发
  `SUBAGENT_COMPLETED`/`SUBAGENT_ERROR`/`SUBAGENT_STOPPED` 事件到主 `eventBus`（NonCancellable emit，
  payload = agentId/role/planId?/subtaskIndex?/status/reportPath?/result?）。
- **事件 → 内部消息**：TurnExecutor init 订阅 `eventBus`，收到终态事件时 `handleSubagentTerminalEvent`
  组 `<event_message>` 内部消息，入该会话的 `pendingEventMessages` 队列。
- **批量合并**：父 turn 空闲时 `dispatchPendingEventMessages` 把同会话待处理事件合并成一条消息、
  一次 turn 唤起（省 token）；撞上 RUNNING（用户消息恰好进来、会话正在跑）→ 重新入队，等下一轮冲刷。
- **STOPPED 也通知**：否则父代理永远等不到被 stop 的子任务的音讯。
- **abort 清队列**：`abortAndJoin` 清掉该会话的 `pendingEventMessages`——用户中止后不该再被唤起。
- **计划活性校验**：冲刷时校验 `planStore.loadBySession` 仍返回活跃计划；已归档/作废时丢弃消息。
- **WAIT 已删**：`SubagentAction` 枚举不再有 WAIT 值。历史消息中的旧 WAIT 调用不会反序列化失败
  （args 是原始 JSON 字符串不经枚举解码），模型收到错误后自纠。
**已删除的旧字段**：`Subtask.appendix` 与 `AppendixEntry`（2026-09-24）——executor 直接 read_file 原文件，
不再要求模型在 generate_spec 时手工转录文件摘录（输出 token 浪费）。

### 安全网

1. **沙盒（代码强制）**：文件工具只能写项目目录 + `.mederi/` + 全局白名单；命令走 OS 级写沙箱
2. **分诊判断（提示词强制）**：先调研再动手、小改直接改、复杂走 Plan Loop
3. plan 从"写文件通行证"降级为"复杂工作的审批流程"——用户批准点保留（APPROVAL 模式），不再锁所有写

## 3. 审计备忘（暂不修，记录以免重复分析）

### 5.5 审计备忘（2026-09）

下列几项经审计确认为"真实代码特性但符合当前哲学/暂不修"，遇到真问题再说：

1. **小改路径无 spec 落盘**：triage "小改直改"路径不建 plan、无 `Subtask.spec`，spec 只在聊天里。
   按哲学（AI 判断 = AI 责任）可接受。kvstore 事件**未确认**是否为其实例（不知 AI 当时判小改还是判复杂跳过）。
   要修须先定：是否强制所有写都走 plan——目前否。
2. **spec 自动更新只到提示词级**：`verify_subtask` 的 spec-冲突分流（CONTRADICTS→re-generate_spec）是
   提示词引导，无代码强制 re-generate。符合"信任 AI"哲学。要升级为代码强制再动。
3. **spec 每轮挂载范围**：主代理每轮只挂**活跃子任务**的 spec（非全部），executor 在 spawn 时拿一次。
   已满足"todo 开始时加载一次"。删 per-turn spec 挂载是可选优化，暂不做。
4. **triage 声明 gate（update_todo 当小改许可证）**：曾提议让"判了复杂"可观测以强制写 plan。
   kvstore 未证实该失败模式（真根因是 ask_user 缺支，已修，见下）。先搁置。
5. **动态上下文只挂状态、不挂指令**（已落地）：`TurnExecutor` 的 `# Active Plan` 段已砍掉
   "WARNING: ... call converge_plan" 这行每轮说教——它重复静态 Plan Loop 规则且是 gap-D 实例。
   **不要再往动态注入里加指令/警告行**；指令归静态系统提示词，动态段只挂状态事实（status/result/evidence）。
6. **已修的真缺陷（2026-09）**：① `VerifyTools` 按 gapType 分流（CONTRADICTS→re-generate_spec，
   其余→converge_plan），不再对所有 FAIL 笼统说 converge；② spec 内部自相矛盾（不可满足，非"跟代码对不上"）
   时禁止自行修改，强制 `ask_user`——加进 Core Principles 第 7 条 + Plan Loop 第 6 步第 4 分支 +
   executor SPEC_FEEDBACK 5 类：`missing` / `ambiguous` / `vs-reality` / `unsatisfiable` / `compile-blocked`。
7. **Markdown 警示块语言**（已修）：`PromptGuides` Alerts 段教模型用 GitHub 警示块，但原规则没说内容用什么
   语言，模型在中文回复里抄了英文 `[!WARNING]`，红框看起来像系统报错。已补：警示块正文必须用用户输入语言
   （Core Principles 第 6 条）。UI 红色撞车问题后续单独处理。
