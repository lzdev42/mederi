# 第四轮实施 spec：统一规则与可反驳的行为验证

版本 WT-REPAIR-2026-11-03.2，配合 [ROUND4-REPAIR.md](ROUND4-REPAIR.md)。这里只明确已承诺行为如何可靠实施，不扩大原 Worktree 范围。类名建议不是强制外部契约；优先复用现有类，避免层层空包装。

## 1. 先完成真实入口审计

在 delivery.md 追加一张本轮调用链表，每行写**真实生产文件/方法**和真实测试，不能只列类名。至少覆盖：

- createConversation→稳定用户消息接纳→prepareSessionWorkspace→SETUP→READY→模型工具。
- 主turn→ToolFactory→文件/bash/verify；SubagentRunner→临时session→父身份重验→同工具工厂。
- 环境action、Handoff POST_SETUP、失败setup重试；无权限上下文入口如何拒绝。
- bash后台：授权→租约→start→注册→track→退出/超时/取消→释放。
- Handoff/Archive/Restore：写前日志→文件/Git副作用→后像→绑定事务→事件。
- 启动恢复：哪些操作扫描、最新绑定如何保持、未知外部变化如何隔离。
- getSnapshot/observe/SSE/重连→SnapshotReducer→WorkspaceViewModel→实际组件。
- UI环境编辑/动作/根及限额/建分支/固定/归档/恢复入口，点击到AiCore的路径。

审计时沿架构文档定位代码。重点文件：WorkspaceManager、WorkspaceSnapshotService、WorkspaceEnvironmentService、ExecutionWorkspaceResolver、VerifiedWorkspaceRoot、ExecutionPermissions、SandboxPaths、ShellTools、ToolFactory、BackgroundTaskManager、TurnExecutor、SubagentRunnerImpl、WorkspaceStore及SQLite实现、Mederi、MederiAiCore、MederiEventAggregator、SnapshotReducer、WorkspaceViewModel/Workspace/Sidebar/状态条与设置页面。实际包路径以源码为准，旧行号会变化。

## 2. N01：原始权限不可变，执行身份不可静默丢失

### 2.1 只读权限投影

建立一条可复用的投影计算链，显式输入：当前项目原目录、当前workingDirectory、稳定artifactDirectory、原始项目设置、已验证工作区身份。可以是现有 SandboxPaths 的明确输入扩展或一个纯内部策略函数，不要保存第二份权限偏好。

规则：

1. settings原值从SandboxManager读取；执行/查询不调用updateSettings，也不改变设置版本或使其他会话待批失效。
2. deniedPaths = 原始绝对拒绝路径 ∪ 原项目内拒绝项在当前workingDirectory的相对映射；项目外拒绝项原地保留。映射是附加保护，不替换原路径。
3. PROJECT与ARTIFACT区域各自检查真实根。原项目 `.mederi` 内的拒绝项不能因按workingDirectory投影而让稳定产物区漏检。不能放开整个原项目作为产物根。
4. 不依赖directories同时代表原项目和执行目录。主工具 directories 已是workingDirectory，不能拿它计算原项目路径偏移。
5. 相对路径、canonical、symlink与同名前缀边界沿既有规范；拒绝优先于FULL与单次批准。文件API和模型工具、shell实际OS后端使用同一策略语义。

黑名单本身可拒绝原项目根；映射后整个副本也被拒绝，此时失败即停，不能借临时目录例外绕过。

### 2.2 同一执行身份贯穿工具生命周期

一次执行持有不可变预期身份：projectId/workspaceId/generation/canonical checkout/workingDirectory/gitCommonDirectory/artifactDirectory，以及真实批准会话和执行者ID。

- 授权前、等待批准后、实际写入/启动前，从唯一Store/Manager重验预期绑定、代次、状态和磁盘/Git身份。
- 当前绑定改变时拒绝旧执行，**不能用新绑定生成凭证然后让旧工具悄悄转向新目录**。
- 子代理临时Session只在自己的内存Store，不得用子ID向宿主SessionStore解析身份。继承父身份，用真实父绑定重验；执行者ID与审批会话ID分别保留。
- 解析异常、缺记录、checkout失踪、symlink替换不能getOrNull降级成无凭证普通路径。
- 不在同步provider里偷偷runBlocking查询Store后吞错。可将内部核验接口改为suspend，或把核验安排在已有suspend授权/执行入口；后端包装使用同一次已核验凭证。相关方法签名与调用方、架构同步。
- ShellTools构造CommandSandbox时传同一凭证；主/子/verify均测试宿主controlRoot中的真实managed路径。

### 2.3 环境脚本与审批归属

所有SETUP/ACTION/Handoff POST_SETUP/retry入口走统一授权+沙盒执行路径。生产缺权限上下文必须报错，不能保留null→ProcessBuilder裸跑。旧单测fixture也装配必要上下文，而非用它作为保留后门的理由。

优先使用关联的真实会话作为批准owner；Local多会话时采用明确的发起会话，并验证其项目/工作区匹配。若DTO需要携带发起会话，AiCore→两桥→server→Mock和文档四处同步。不能用 `ws_op_<id>` 假会话让审批卡无处显示；没有真实owner的提升请求应给可见错误，不永久挂起，也不自动FULL。

脚本结果必须显式分支：

| 结果 | 工作区/后续行为 |
|---|---|
| SUCCEEDED | 记录已核验结果，才允许下一阶段 |
| WAITING_APPROVAL/PENDING/RUNNING | 保持门禁，显示真实可操作状态；不开始模型/清源 |
| FAILED | 保存错误与日志，准备不可READY；Handoff保留恢复依据 |
| CANCELLED/取消异常 | 停稳进程，按安全点恢复或隔离，不吞成成功 |

不得仅靠try/catch判断setup成功，因为服务可能以FAILED operation返回。取消异常不得被通用catch吞掉后继续执行。

首次输入仍沿既有统一稳定messageId持久接纳一次；准备重试/批准继续只消费原输入一次，不用UI待发队列。

## 3. N02：执行权在启动之前建立，管理所有权明确

### 3.1 进程生命周期顺序

`解析身份/策略→授权→取执行租约→最终重验→start→ProcessRegistry→BackgroundTaskManager接管→退出/超时/取消并join→释放租约`。

- 前台命令也必须有明确生命周期保护；可在真实turn持租约期间复用，但不能让非turn的动作漏保护。
- 每个后台进程有独立租约，父turn结束不释放它。很快退出、启动抛错、track抛错、hook安装失败、取消均恰好释放一次。
- start之前拿不到租约，绝不能有进程副作用；start之后接管失败，必须收割自己启动的进程组再释放。不得只返回Error遗留进程。
- 不能把job结束直接等同进程组已经退出；沿现有ProcessRegistry/BackgroundTaskManager实际清理后释放。只回收Mederi自己启动的组。
- 不忽略attachTerminalHook false。建议把“接管进程+持有资源+终态释放”作为BackgroundTaskManager的一个可靠操作，而非外部事后挂钩；内部实现保持窄，不重写后台任务系统。

### 3.2 管理租约与内部SETUP

withManagementIntent先阻新执行、排空旧执行再改Git。常规action遇BUSY必须拒绝。

Handoff已拥有管理临界区时，其POST_SETUP可以使用**由lease manager在该临界区内创建**的内部permit。permit绑定operationId、canonical checkout/common-dir、作用域/有效期，离开block即失效，不可序列化，不可由UI/模型传入。不能用布尔“ignoreBusy”、捕获BUSY、线程名或任意operationId字符串代替所有权验证。

具体类名自定；必须有测试证明另一个协程/操作无法借同一个busy状态执行。setup也不能以“有管理意图”为理由解除OS沙盒/黑名单。

## 4. N03：统一完整文件状态与安全类型转换

### 4.1 文件状态比较

快照、归档后像、清源、恢复完成核验和回滚共用明确的NOFOLLOW状态表示：相对路径、exists、kind、普通文件SHA256、symlink原始link文本/hash、必要mode。目录枚举记录条目集合，保留ignored与普通untracked；忽略的只允许是原spec明确排除的运行时产物，不能临时新增排除规则让测试绿。

- PREIMAGE要覆盖整个契约范围，不只Git status。新增/删除ignored、symlink目标改变、执行位改变、类型改变、缺项、读取失败都能检测。
- I/O/解码/枚举/身份失败是UNKNOWN/错误，不能被解释为“没有外部变化”。
- Git HEAD/ref/index为另外明确核验的状态，不以工作树文件hash代替。index的行为完整性以Git stage记录/内容为主要依据，避免仅依赖会被Git无害刷新影响的原始index字节指纹。
- 管理操作比较和删除/清源持同一意图，操作前再次确认关键身份和后像；发现未知外部变化转RECOVERY_REQUIRED，零覆盖未知数据。

### 4.2 安全物化原语

restore、transfer、rollback复用同一类底层落盘操作。先验证manifest合法性与路径冲突，再逐项处理；父级不能跟随symlink。FILE目标本身若为symlink，替换链接本身；同目录临时普通文件完成内容/mode后原子替换，不能writeBytes跟随叶子。类型互换需处理FILE↔DIRECTORY↔SYMLINK，不能假设HEAD与快照类型一致。

正常Git symlink必须可保存，不以禁用全部symlink过关。删除只能涉及本操作已证明拥有且后像匹配的条目；目录有未知子项时不能deleteRecursively清掉。缺少后像所有权不能“尽力回滚”后READY。

处理全量预检和每次落盘的祖先检查/路径锁/原子替换；如文件系统不支持所需原语，要明确失败或限定可证明范围，不能在注释里声称无条件消除了所有外部进程TOCTOU竞态。

## 5. N04：恢复是验证状态，不是重放成功历史

### 5.1 写前意图与持久后像

每个Git/文件副作用前持久：操作、参与路径、预期身份/代次/ref/index、完整前像引用、将执行阶段/将影响路径及所有权。执行后采集并验证后像，持久完成记录，再走下一阶段。

阶段开始/完成是不同事实；不要在副作用后第一次保存affectedPaths。对SETUP无法事先枚举的潜在写范围，应保留完整允许范围前像并记录执行所有权，结束采集差异；崩溃时未知变更隔离，不将外部文件混作本操作拥有。

故障注入必须覆盖：意图写入前后、每次真实mutation前后、后像持久失败、绑定事务失败、完成记录失败。不能仅在saveWorkspace某一次调用前失败，声称every boundary。

### 5.2 事务与历史

会话绑定、associatedWorktree、相关generation和操作终态按已有同data driver事务/CAS完成；若现有commitBinding不足，窄范围扩展Store事务参数及两实现，不让Manager写一半后自己补另一半。

SUCCEEDED记录是审计历史，不是启动命令。删除/替换无条件repairSucceededHandoffBinding逻辑。若要修旧版坏账，必须有可证明的限定条件/明确迁移版本/最新提交代次，无法证明则隔离报告；不得猜目标覆盖当前绑定。

至少真实 Local→A→Local→A/或B，启动多次后最新绑定/关联副本/generation不变。测试真实SQLite重开，不能仅复用内存对象并手动调用scanner证明重启接线。

### 5.3 恢复判定

- ARCHIVE：只有当前完整后像与快照捕获状态一致，且身份/管理权有效，才可继续删除。快照/指纹读失败保持数据、转恢复，不放行。
- RESTORE：linked注册属于原common-dir；HEAD/ref及index stage内容、工作树FILE/SYMLINK/DIRECTORY/mode/墓碑/多余条目符合契约，才READY。工作树字节相同但index还在HEAD也不算完成。
- HANDOFF：能证明目标accepted状态与源清理状态时事务提交；能证明回滚所有权时安全回滚；中间有未记后像/外部修改时RECOVERY_REQUIRED，不能空journal回滚后READY。
- 不重复递增generation，不覆盖之后合法提交；重复启动和requestId重试幂等。
- 初始化恢复异常不得只记录日志就把受影响工作区放行。允许未受影响项目继续初始化，但异常对象保持不可执行并可见；两个interface同样生效。

## 6. N05：真实桥层水合与事件

Core/Manager保存事实并在提交后发相应工作区/操作事件，事件归真实会话（共享Local影响多个会话时正确刷新受影响者）。事件丢失不改变真理源。

MederiAiCore.getSnapshot与MederiEventAggregator初始水合使用同一查询/映射逻辑，包含workspaceInfo和activeWorkspaceOperation；ServerAiCore REST/SSE消费同语义。禁止只在Mock或UI本地补一份状态。

getConversationWorkspace/list操作要包含需要恢复的异常态，不只“运行中”；RECOVERY_REQUIRED在首次打开、切会话、重连和启动扫描后都可操作。失败/取消状态按产品规则显示，不用任意firstOrNull碰运气选旧操作，明确关联当前workspace/关联副本/最新未解决操作。

既有会话工作区数据尚未到达时UI显示loading/禁用迁移并补取，不把null猜成Local或Managed。真实Local和Managed均从Core事实推导目标，与预览token绑定，不能点击时重猜。

## 7. N06：实际UI入口与请求归属

沿现有共享UI/MVVM/资源字符串体系实现，不在Composable写业务/读Git。必须完成原03-runtime-ui的入口：

- 现有迁移、工作副本管理、固定/归档/恢复；恢复状态条真实可触达。
- 当前工作区建分支：name/expectedHead/generation真实提交，冲突可见，不自动改名。
- 项目环境编辑：平台覆盖、setup、actions及版本保存；动作从实际工作区执行、日志/运行/失败/审批可见。
- 宿主工作副本设置：根、max/默认15、自动清理、宿主全局作用域；调用既有API，不放设备preferences。
- 无Git/无HEAD/暂不可用平台能力明确禁用并解释；不要摆一个永远不回调的菜单项。

不必另造第二套设置框架，复用现有项目/宿主设置路由；可拆小纯Content组件，但集成测试从真实入口点击。

每次面板请求捕获(ownerSessionId, projectId, dialogKind, requestGeneration)。attach/dismiss/打开另一面板/刷新预览使旧代次失效；完成回调必须同时匹配捕获身份和当前请求代次。判断两个当前可变字段相等没有意义。执行中的Handoff/recovery也要归原操作，旧结果不能覆盖另一个会话或新面板。

宽屏popover/dialog、窄屏sheet按既有布局规范。测试320dp、280dp及fontScale1.6：滚动能触达每项、主按钮可见、错误/长路径不横向溢出、点击区域至少44dp。模型/权限/计划图标布局保持现有产品语义。

## 8. 二十项新增测试的具体行为

类名/方法名冻结在round4-acceptance.json。下面定义测试内容，不能用“同名方法跑绿”替代。

Core类 `xyz.mederi.workspace.WorkspaceInvariantIntegrationTest`，常规core jvmTest。fixture使用真实Git+Store/Manager/Resolver/SessionManager/ToolFactory；SQLite测试真实文件位于temp，重开driver。外部LLM可用确定性fake产生真实工具调用。

| # / 方法 | 必须执行并断言 |
|---|---|
| 1 projectPolicyProjectionPreservesSettingsAcrossLocalAndTwoWorktrees | 同项目Local/A/B，原项目内外deny；经真实动作/准备与查询，设置内容/version始终不变；三个位置各自deny有效，互不改变其他会话待批 |
| 2 artifactAndSourceBlacklistRemainEffectiveAfterHandoff | 真Handoff前后对源码/稳定产物区及额外白名单读写；原deny及映射deny优先FULL，产物实际在原项目`.mederi`，兄弟副本/宿主DB不因精确例外可写 |
| 3 setupActionAndHandoffShareReachableApprovalOwnership | 真实API分别触发需要network/off的setup/action/POST_SETUP；pending卡归真实可访问会话，拒绝/取消无副作用，批准只该操作生效；无owner报错，不永久等假会话 |
| 4 failedWaitingOrCancelledSetupNeverOpensExecutionGate | exit非零、超时、拒绝、等待批准、取消逐场景：模型调用计数=0，workspace非READY，真实日志错误可读；成功后才恰好执行一次 |
| 5 realSubagentAndVerifyUseLiveWorkspaceCredentialUnderControlRoot | fixture受管路径真在protected controlRoot；主与临时子session经真实工具工厂write_file/bash/verify，落当前checkout；计划/notebook/report真落原项目产物根；拒绝兄弟与秘密，不只拼路径断言 |
| 6 realStoreGenerationChangeInvalidatesPendingToolGrant | 从真实发送/工厂取到待批工具，改变真实Store绑定或generation，再批准：原新两个目录都无未授权写入，返回失效；不得自造计数provider模拟代次 |
| 7 backgroundLeaseCoversFastExitFailureAndTurnCompletion | 真后台命令在父turn结束后继续写；归档等待最终写入并含快照；快退出、start/track故障、取消逐场景计数归零且无遗留进程，busy拒绝无marker |
| 8 managementPermitIsOwnedAndCannotBeInferredFromBusy | 持管理意图的内部POST_SETUP合法执行；另一协程action/伪造或过期permit被拒绝且无写；退出临界区permit失效 |
| 9 archivePreimageComparisonIncludesIgnoredLinksModesAndReadFailures | archive快照后分别改ignored/新ignored/link文本/mode，或注入枚举/manifest读取失败；重开恢复，未知变更保留且不ARCHIVED、不强删 |
| 10 safeMaterializationHandlesAllFileTypeTransitions | restore/transfer/rollback逐入口覆盖FILE/DIR/LINK转换、父链/叶链及目录未知子项；外部目标字节保持，合法链接文本正确，未知项零删，mode正确 |
| 11 everyMutationBoundaryRecoversWithSqliteReopen | 对Handoff/Archive/Restore的每个真实mutation与日志/事务间隙注入故障；关闭重开SQLite并走生产恢复；精确完成/安全回滚或隔离，不能半恢复READY；每个case输出阶段标识 |
| 12 latestCommittedBindingSurvivesRepeatedHandoffsAndStartup | 真Local↔A多次及合法新副本切换后重开SQLite、初始化多次；最新绑定/generation/关联副本不被旧成功历史覆盖，旧记录不改写 |
| 13 restoreCompletenessIncludesIndexTombstonesLinksModesAndIdentity | dirty staged/unstaged分离、symlink/删除/mode；注册已完成而index未恢复等中断；文件匹配但index/common-dir/ref不匹配不得READY；完整恢复与原Git stage语义一致 |
| 14 bindingTransactionFailureLeavesAllRecordsUnchanged | 真SQLite事务在各写入边界失败，比较Session、参与Workspace、operation的前后；全提交或全不提交，重复requestId无重复副作用；不只测试内存store |
| 15 preparationRetryConsumesAcceptedMessageExactlyOnce | 真create/send，stable messageId先落一次，setup失败→重试/审批→成功；输入历史只有一个该ID，模型只消费一次，没有伪最终答复/第二UI队列 |

Shared类 `xyz.mederi.ui.workspace.WorkspaceProductionRoutesTest`，常规shared jvmTest。允许给真实桥注入fixture Mederi/外部服务替身所需的窄测试装配入口，不允许手工给snapshot加生产查不到的字段。APP启动仍须idea-mcp；Gradle中的Compose测试不等于启动APP/server。

| # / 方法 | 必须执行并断言 |
|---|---|
| 1 realBridgeHydratesWorkspaceAndOperationForSnapshotAndEvents | 真实MederiAiCore fixture查询getSnapshot/observe、真实Server桥REST/SSE；新建、迁移、归档/恢复操作后两侧DTO同义，初始与重连均有真实workspace/operation；不只Mock |
| 2 localAndManagedHandoffAreDrivenFromRealWorkspaceState | 真实Workspace界面依次Local/Managed，实际菜单→preview→确认；与真实Manager契约一致的源目标与token，执行后回显正确；loading时不猜目标 |
| 3 recoveryRequiredRemainsActionableAfterRemoteReconnect | 通过真实Core故障生成RECOVERY_REQUIRED，遥端重连/切会话后状态条恢复按钮可见，点击打开真实preview并提交选定策略；恢复后可读快照和状态一致 |
| 4 dialogRequestsAreInvalidatedOnSwitchDismissAndReplacement | 可控延迟API：A→B、A→B→A、关闭后重开同会话另一面板、刷新预览、旧执行失败晚到；旧响应均不改新面板、不拿旧token给新会话；错误可见且允许正确重试 |
| 5 phoneCanConfigureEnvironmentRunActionSetRetentionAndCreateBranch | 从真实MainScreen/Sidebar/设置路由在320/280dp、fontScale1.6点击环境编辑/保存/运行/日志、根与限额保存、建分支/冲突、恢复；断言实际API参数与持久回读、44dp触达/无溢出；不直接调VM代替点击 |

每个宽泛矩阵可以拆内部helper/参数化场景，但保留manifest指定入口，失败输出具体case，不能只跑一种情形后默认其他通过。原113/19中的相关错断言也要按这些真实语义补强，不删除历史覆盖。

## 9. N07：有限逆向验证和交付证据

常规通过后，对三条关键保护做有限逆向验证，证明测试真的依赖保护：

1. 在temp隔离源码副本把“拒绝路径检查”移除/失效，对应真实读写测试必须行为断言FAIL。
2. 把后台start放到lease之前，`rejectedBackgroundLeaseMustPreventProcessStart` 必须FAIL。
3. 把恢复完成判据放宽为“是Git仓库”，不完整恢复/index测试必须FAIL。

只在temp隔离副本做，不改主工作树再靠git checkout还原（会覆盖用户改动）。可以复用可注入的故障实现来做明确等价的反例，但必须让同一生产入口的同一断言检测到失效，不能在测试里硬写throw制造失败。不是要求引入全仓mutation工具或大规模穷举。

交付每项的最小逆向patch/替身说明、隔离源码标识、实际失败XML/命令、匹配用例和断言；编译失败/MISSING/skip不算敏感性证明。正常最终源码必须仍通过。门禁只检查报告，不替代审查这三份证据。

最终在delivery.md提供：

- 入口调用链表；每个不变量的唯一实现位置，列出已经消除的重复危险入口。
- T01–T14→N任务→行为测试→报告的映射；未覆盖项明确标未完成。
- 实际故障矩阵（operation/phase/before或after/Store类型/重开/预期与实际/是否有外部变更）。不能只一句“子代理验证”。
- 全量报告、baseline差异、平台/人工凭据和源码指纹；三项逆向验证。
- 未完成UI、未接通链路、环境缺口分别说明。即使32项全绿，仍需审查者核验生产逻辑与测试真实性。
