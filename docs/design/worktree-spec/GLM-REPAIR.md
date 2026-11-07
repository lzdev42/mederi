# GLM 5.2：Worktree 审查整改交接

> 历史阶段文档。本轮当前执行入口已更新为 [ROUND4-REPAIR.md](ROUND4-REPAIR.md) 和 [round4-spec.md](round4-spec.md)；不要重新实施本文件的初始六项FAIL状态或复录baseline。

版本 **WT-REPAIR-2026-11-02.1**。这是当前执行入口，指定执行者 **GLM 5.2**；不需要聊天上下文，不需要调用Gemini或另一模型替你判断是否安全。任务是修复当前实现并完成原目标，不是重新设计一个缩水版worktree。

## 1. 事情经过与当前真实状态

Mederi是Kotlin/Koog应用框架，core为JVM宿主引擎，app/shared为Compose KMP UI和AiCore契约。桌面直连core，手机/Wasm通过ServerAiCore遥控同一宿主。业务在AiCore/Core，宿主main只做平台能力注入。

用户先要求macOS/Linux执行沙盒和项目级权限（需要批准/FULL_ACCESS），没有AI安全自动审查；路径黑名单禁止读写，允许命令/拒绝命令为确定性前缀规则；计划自动批准只决定计划审批，和执行权限独立。这部分早于worktree，必须保留。

之后用户要求完整对标Codex公开worktree流程，并兼顾窄屏。设计者编写了WT-2026-11-02.1规范包，交给Gemini 3.8 Flash实施。Gemini新增了一批工作区模型、服务、API、组件及测试，在delivery.md声称多数WT任务PASS。

随后设计者审查当前**真实源码**，结论不通过：存在10项P1，生产接线、权限、快照和恢复都有问题。新的6个独立回归测试实际调用生产方法，**6项全部失败，0跳过**。原来113个测试名称/报告的绿灯没有覆盖这些生产缺陷，不能作为保留错误实现的理由。

审查时源码指纹 `5dbc1355322505f687c20bd5eee01bd1812668e588ccb2756dddbb8c25ff072c` 与Gemini收据匹配：当时的saved regular报告core 690项零失败5跳过，shared 578项1项既有失败；Linux新增原生2项是跳过，最终验收脚本还缺Linux和桌面/手机/Codex人工证据。本交接新增文档/测试资产会使当前全树指纹变化，这是正常的，旧收据仅是历史记录；修复后重做同一份新源码的验证，不伪造或沿用旧指纹。

当前仓库有大量未提交改动，混有前批沙盒与Gemini worktree实现，很多新文件也未跟踪。**不能按git diff整个回退，不能reset/clean/覆盖用户已有修改，不能删除真实数据库或在本仓库试Handoff/清理。**本轮用户授权修复实现、测试和相应文档，不代表批准删除任何真实项目数据。

## 2. 阅读顺序与真理源

1. 读取根AGENTS.md，按问题域读docs/architecture；它们指导源码定位。但工作区架构段有对未接通能力的过度描述，必须用实际源码核验，不能把文档里的“已支持”当运行证据。
2. 读本文件，再读 [review-2026-11-02.md](review-2026-11-02.md)：10项问题、确切入口、触发影响和为什么旧测试漏检。
3. 按当前整改任务读取原 [01-contract.md](01-contract.md)、[02-lifecycle.md](02-lifecycle.md)、[03-runtime-ui.md](03-runtime-ui.md) 对应章节。原README/04-tasks中的Gemini身份与“产品未实施”是上一轮历史；本轮已部分实施，执行者是GLM，不能从零重建/重复旧baseline。
4. 原 [acceptance.json](acceptance.json) 的113项验收继续有效，新增 [repair-acceptance.json](repair-acceptance.json) 的19项整改用例同样必须通过。设计语义仍按原冻结spec；本文件约束如何修缺陷与验证。

优先级：用户当前要求/AGENTS → 冻结spec与本整改要求 → 实际源码事实 → 历史delivery/架构文字。发现规范自相矛盾或不能实现，记SPEC_CONFLICT（最小复现/候选修订），不静默改成较弱行为；独立任务继续做。不要为通过检查删测试、改验收manifest、伪造收据或新增一层无意义AI批准。

## 3. 第一步：可重复复现，保留原证据

正式回归资产已从temp保存到 `scripts/worktree-review/`，不依赖上一模型的临时上下文/脚本：

```bash
python3 scripts/verify-worktree-repair.py --docs-only
./gradlew -I scripts/worktree-review/init.gradle :core:jvmTest \
  --tests xyz.mederi.workspace.WorkspaceReviewRegressionTest --console=plain
```

6项测试目前预期失败，失败说明正确安全行为尚未实现。它们只在repo temp下建假仓库/假密钥，不读写真实用户项目。原历史结果见审查报告；无需为了确认已知失败反复跑全量。

将 `scripts/worktree-review/src/xyz/mederi/workspace/WorkspaceReviewRegressionTest.kt` 纳入 `core/src/jvmTest/kotlin/xyz/mederi/workspace/` 的常规回归，保持类名与六个方法名；只允许为接口重构调整fixture/调用，不得弱化断言。init.gradle发现已纳入常规源集后不会重复注入。纳入后不带-I的全量Gradle也必须运行这六项，不能永远靠临时source set才能看到它们。

首次修改前备份旧 `temp/worktree-evidence/delivery` 到另一个temp目录并记录起始HEAD/已有改动。保留原baseline；**不要把新增六项缺陷造成的失败录成“既有baseline可忽略”**。默认继续用该目录收集新结果，旧regular/native先备份后覆盖；baseline不可改写。如旧baseline不存在，明确记录环境缺口，不能事后冒充实施前基线。

## 4. 整改任务与顺序

按RF-01至RF-10串行推进，相关Manager/TurnExecutor/Server文件禁止并发竞争编辑。已有正确实现可复用，禁止为赶工整包重写。每项提交代码、真实测试与架构同步后，向delivery追加记录；不要每项小改都向用户问许可。

### RF-01 文件API边界与脚本执行（审查2/6）

- 修 `WorkspaceManager.listWorkspaceFiles/readWorkspaceFile`：禁止String.startsWith判目录，改Path组件包含；拒绝穿越和任一级symlink逃根；查询/应用该项目当前有效黑名单与工作区映射，列表过滤拒绝条目。PROJECT/ARTIFACT区域各有真实根，不能失败fallback到其它目录。
- 环境SETUP/ACTION必须复用当前ShellTools/CommandSandbox准备、批准、黑名单、超时和ProcessRegistry，任何需要批准上下文的生产入口不得以permissions=null裸跑。default受限无网络，network仅开网络，off/FULL仍保留显式deny。
- 不能只先调用authorize再直接ProcessBuilder裸执行；实际启动argv必须是统一后端包装结果。子进程组/取消与已有shell一致。
- 通过新增回归的前三项文件读/列表与动作禁止写用例，并补真实SETUP路径、默认网络拒绝、network保留文件限制、取消/拒绝不产生副作用。Mock响应“不含密钥”不算安全断言。

### RF-02 生产工作区准备、解析、凭证与租约（审查1/7）

- 从Mederi装配唯一WorkspaceManager、ExecutionWorkspaceResolver、执行协调/租约；注入SessionManager→TurnExecutor两个目录入口→子代理/verify/自动续跑，不能保留managed缺解析时回退Project.directory。
- 首次send必须沿**既有统一消息接纳**持久落一次稳定messageId，随后准备REQUESTED副本/等待setup/审批，READY才构建并执行模型工具。WorkspaceManager现有acceptUserMessage测试辅助若另造消息，不可与真实send各append一次；抽出无历史写入的工作区准备方法供统一发送入口调用，消息持久化继续只有一个真理源。
- 补齐VerifiedWorkspaceRoot实际生产创建与传递。ExecutionPermissions、ShellTools重建CommandSandbox时都携带同一工作区凭证；每个grant重查项目与workspace代次。嵌在宿主controlRoot的当前checkout可写，兄弟副本/数据库/快照受保护，产物只放行精确子路径。
- 所有主turn、临时子代理、verify、setup/action及后台命令持有真实执行租约；管理意图阻止新租约，停稳/排空后才能动Git。后台进程没停不能因父turn结束就释放保护。按canonical checkout枚举受影响对话，明确确认后才abortAndJoin；不得强停无关会话或宿主进程。
- 避免Manager↔SessionManager循环装配：将停止/列举执行者封装为共享的窄 `WorkspaceExecutionCoordinator` 接口或现有等效协调器，由装配层完成注入；操作开始前初始化完毕，不能用nullable/no-op默认绕过协调。业务不挂desktop main。
- 新增WorkspaceRepairIntegrationTest前三项，测试真实create/send装配。模型服务可用确定性fake LLM输出一次write_file；WorkspaceManager/SessionManager/TurnExecutor/ToolFactory必须是真实对象，不能手工把两个目录传FileSystemTools就算端到端隔离。

### RF-03 NOFOLLOW快照与原位置回滚（审查3/4）

- 枚举改为不跟随symlink的文件树访问；symlink作为叶子记录link文本，不递归其target。PREIMAGE不采集link/child内容。
- manifest验证拒绝重复路径、父文件/父symlink与子路径冲突、穿越/.git注入/类型不一致；落盘每一步检查祖先不被symlink替换。正常tracked symlink仍需保留，不能以禁用全部symlink让测试“安全通过”。
- rollback先全量核对当前type/hash/mode/exists与journal postimage；任一未知变化→RECOVERY_REQUIRED、零覆盖。只删除确由本操作创建且后像仍匹配的路径；空目录逐级处理，不对未知树deleteRecursively。
- 六项回归中的symlink恢复和rollback必须通过；再补用户改了“本操作新建文件”、symlink父级竞态、混合合法/非法manifest均拒绝且不半写的测试。

### RF-04 真正的写前日志、事务与崩溃恢复（审查5）

- Handoff/Archive/Restore在任何副作用前持久PENDING/RUNNING、完整operationPlan、phase意图和基线；每阶段完成后持久已核验后像，再进入下一阶段。不能事后只造一条SUCCEEDED。
- 工作区generation变更、Session绑定、associatedWorktree、操作状态使用已有同库commitBinding/UnitOfWork CAS。内存Store也保证对应失败语义；SQLite异常不能留下“成功操作+旧Session绑定”。
- 重启扫描从真实持久记录和文件/Git状态推导，按spec完成/回滚/隔离，不直接把TRANSFERRING改READY。请求幂等、取消安全点和preview失效都走实际路径。
- 新增 `handoffJournalSurvivesEveryRealMutationBoundary` / `archiveRecoveryHandlesRemoveBeforeDatabaseCommit`：在生产Driver/Store副作用前后注入故障，关闭/重开Manager与真实SQLite；测试不能手工save一个生产根本没保存的理想RUNNING操作来代替。

### RF-05 清源期间的外部变更保护（审查9）

- 清源前重新核源HEAD/index及相关tracked路径与捕获后像，包含hash/type/mode/删除态。仅处理实际转移的精确路径集，任何变化停止进入RECOVERY_REQUIRED，保留双方快照。
- 检查每个Git退出码，失败不得继续绑定SUCCEEDED。ordinary untracked也保持后像核验，不把用户后来编辑的同名文件删除。
- 新增 `sourceEditAfterSnapshotIsNotDiscardedAtCleanup`：在真实Handoff snapshot之后、CLEAN_SOURCE之前写源tracked文件，断言新内容保留且迁移未假成功；同时测stage变化与外部ref推进。

### RF-06 恢复后仍是原仓库linked worktree（审查8）

- 正式Manager恢复必须在原common-dir注册detached worktree，再把快照文件/index/blob恢复进去，核验Git注册与common-dir后READY。
- SnapshotService独立`git init`恢复只可用于自包含快照验证/明确导出，不可直接用于正式受管恢复并沿用旧identity。
- 原Git仓库真的消失时明确MISSING/IDENTITY_CHANGED，保留快照，不冒充新仓库是原项目。
- 新增 `archiveRestoreRemainsRegisteredAndCanBeArchivedAgain`：真实创建→归档→恢复→检查common-dir/worktree list→commit并从原仓库解析对象→再次归档/恢复，包含二进制/staged/untracked/ignored；不是只断言恢复文件内容。

### RF-07 归档/清理与执行并发验证（审查7的验收闭环）

- 使用RF-02真实租约和协调器，固定/运行中/待批/迁移/结果未知的副本不得自动回收；快照完成后到remove前再次核身份/指纹。不可把“取消请求已发”当进程已停。
- 新增 `archiveWaitsForRealExecutionAndIncludesLateWrites`：真实长运行脚本持续写入，触发归档/限额回收，验证等待/拒绝或明确停稳之后最后写入被快照；原始checkout/外部自建worktree从不删除。并发测试用可控latch/pipe，不靠碰运气sleep。

### RF-08 真实UI路由与API闭环（审查10）

- 实现WorkspaceViewModel.openHandoffDialog，挂MainScreen/窗口route，实际读取preview/显示源目标及受影响任务，确认后提交同token，处理失败/过期/取消。
- 管理、归档列表、固定、恢复策略、设置和环境动作接入实际页面/菜单；不能只留公开纯组件供测试直接调用。所有按钮结果由Core快照回显，错误可见，旧异步响应不污染新对话。
- 桌面单紧凑workspace入口+权限与计划图标；计划组可用CheckSquare辅助。手机320dp/280dp大字体沿原spec；Handoff/恢复用可滚动面板，主动作可触达，不把全长路径/分支/说明平铺输入框。
- 新增WorkspaceRepairUiIntegrationTest五项（repair-acceptance.json列明）。测试从实际MainScreen/Sidebar/Input入口点击驱动；可以fake AiCore，但必须记录并断言调用顺序、真实workspaceId、requestId、preview token。不能直接setContent(HandoffPanelContent,onExecute={clicked=true})替代APP接线测试。

### RF-09 补强旧113项测试，防止同名空验收

- 保留原manifest全部能力，检查旧用例是否真实断言其名称所述行为。特别修WorkspaceRuntimeTest/RecoveryTest/FileUiTest/MobileTest的覆盖缺口，避免只是new DTO/assert自身字段/比较两个Mock。
- 六个独立回归必须进入常规core源集；新增8个Core集成和5个UI路由集成，即本包19个整改用例。允许合理fixture重构，不得用ignore/assume/fake success绕过。
- 对权限、迁移/恢复、幂等使用真实Manager/Store/Driver/Git；只替代外部LLM、OS不可用平台或测试故障注入边界。平台替代不等于原生验收。
- 对之前改过的无关共享测试/依赖检查是否必要，保留已有用户改动，不能为“清理”擅自删除；发现放宽断言掩盖回归需恢复有行为意义的断言并说明。

### RF-10 文档、回归、原生/实机证据与交付

- 按真实实现同步architecture00/01/02/04；目前工作区段落含不存在/未接通的结构，不允许照抄声称完成。代码中仍空实现/stub的必需能力全部补齐。
- 在原delivery.md末尾追加“GLM整改记录”，保留Gemini历史表，明确旧PASS被本次review否定的范围。逐个RF写实际结果/命令/报告/偏差；该文件已在原指纹脚本中排除，可在测试后补记录，不改产品源码指纹。
- 执行下面的两层验收；旧baseline失败仍逐项披露，新失败不得通过改baseline豁免。Linux原生与人工实机凭据缺失则只报告代码/自动化阶段结果，最终状态未验收，不把缺环境伪装成功。

## 5. 测试入口与最终验收

本轮扩展清单固定三类：

- `xyz.mederi.workspace.WorkspaceReviewRegressionTest`：已有6项独立复现，正式来源见scripts/worktree-review，纳入core常规测试。
- `xyz.mederi.workspace.WorkspaceRepairIntegrationTest`：RF02/04/05/06/07的8项真实Core链路测试，放core/src/jvmTest/kotlin/xyz/mederi/workspace。
- `xyz.mederi.ui.workspace.WorkspaceRepairUiIntegrationTest`：RF08的5项真实UI路由测试，放app/shared/src/jvmTest/kotlin/xyz/mederi/ui/workspace。

方法名和模块详见repair-acceptance.json，新增测试是你必须实现的资产，不是假称现在已有。任务内运行相关测试，失败先修实现；最终命令：

```bash
# 全量回归/平台编译和新增19项（普通测试证据阶段，不等于完整最终通过）
python3 scripts/verify-worktree-repair.py --run --evidence-dir temp/worktree-evidence/delivery

# 对应主机分别执行，必须同一份最终源码；只有macOS主机不能验证Linux
python3 scripts/verify-worktree-delivery.py --native macos --evidence-dir temp/worktree-evidence/delivery
python3 scripts/verify-worktree-delivery.py --native linux --evidence-dir temp/worktree-evidence/delivery

# 按原README/manual-template.json完成桌面与手机真实闭环、对照并填真实证据后
python3 scripts/verify-worktree-repair.py --evidence-dir temp/worktree-evidence/delivery
```

扩展脚本复用原验收，不替换/放宽它，额外检查19项用例存在、PASS、非skip、与当前源码指纹一致。仅执行--run成功仍不能宣称完整对标；最终无参数检查还要求原113项、macOS/Linux、四平台编译及人工对照齐全。验收脚本只查报告，测试质量仍须按本包生产场景实现。

APP/server按AGENTS必须idea-mcp，工具不可用就明确该项待环境。测试和编译可用Gradle，缓存错误可-Pkotlin.incremental=false，不得删真实数据库或重建用户配置来“修测试”。所有Git破坏性验证仅在temp自建仓库。

## 6. 最后交给用户的结果格式

1. RF01–RF10逐项状态和修复位置；不能只说“tests all pass”。
2. 原113项、新19项的实际结果、全量baseline差异、编译/原生/实机证据路径与最终源码指纹。
3. 六项原复现如何从FAIL变为PASS；生产装配/真正故障注入/UI入口证据说明。
4. 仍缺的环境、未解冲突和Codex对照差异。缺任一必需项就写未完成，不以增加测试数量替代实现完整性。

用户已授权这轮整改，可以自主修代码/测试/文档；无需逐任务要求用户再决定架构。需要真实用户数据破坏性操作则按AGENTS先明确批准；本任务本身不需要对真实项目执行它们。
