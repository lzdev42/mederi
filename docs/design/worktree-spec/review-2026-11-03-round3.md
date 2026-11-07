# Worktree 第三轮审查（2026-11-03）

**结论：不通过。** 上一轮五个独立复现已修复，但“R01–R15 全部修复”仍不成立。本轮发现权限配置被改写、成功操作历史回放错误、忽略文件数据丢失及回滚写穿等问题。不能以旧用例变绿代替完整逻辑审查。

审查源码指纹：`2ea0cc2de3215d3547a5ef6d49628f83119876549aca244f8ab8617089123ccb`。regular 收据与该指纹匹配，19 项报告通过。最终无参数验收仍因旧原生凭据的指纹不匹配退出 1；macOS/Linux/人工未完成这一点与交付声明一致，不将其冒充本轮新发现。

本文在审查后新增，会改变文档参与的全树指纹；没有改产品源码、原验收 manifest、baseline 或既有测试断言。未启动 APP/server，也未在真实项目执行管理操作。

## 独立测试结果

运行上一轮已提升到常规源集的两个类，共 **5 项 PASS**：

- WorkspaceProductionSecondReviewTest：2 项通过。
- WorkspaceLifecycleSecondReviewTest：3 项通过。

新增四个独立测试类，共 **7 项 FAIL，0 errors，0 skipped**，全部失败于行为断言：

| 用例 | 真实结果 |
|---|---|
| managedActionMustNotRewritePersistedProjectBlacklist | 原项目 secret.txt 黑名单被持久改为某个 managed checkout 的 secret.txt |
| rejectedBackgroundLeaseMustPreventProcessStart | 工具返回租约拒绝错误，但命令已启动并写出文件 |
| startupMustNotReplayOlderSucceededHandoffOverLatestBinding | 正常 Local→Worktree→Local 后扫描，把会话改回旧 Worktree |
| archiveRecoveryMustPreserveIgnoredChangesAfterSnapshot | 快照后改写 ignored.log，恢复扫描仍强删 checkout，新内容丢失 |
| environmentActionMustNotBypassAnotherManagementIntent | 已有管理独占意图时，动作仍启动并写文件 |
| materializeCrashMustNotMarkUnrolledTargetReady | 文件已复制但阶段日志未写入时中断，扫描宣称回滚并 READY，目标残留文件未撤销 |
| rollbackMustNotWriteThroughMatchingPostimageSymlink | 合法匹配 journal 的末级 symlink 被 writeBytes 跟随，覆盖工作区外文件 |

命令：

```bash
./gradlew -I temp/worktree-review-round3/init.gradle :core:jvmTest \
  --tests xyz.mederi.workspace.WorkspaceProductionThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceLifecycleThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceProductionSecondReviewTest \
  --tests xyz.mederi.workspace.WorkspaceLifecycleSecondReviewTest --console=plain

./gradlew -I temp/worktree-review-round3/init.gradle :core:jvmTest \
  --tests xyz.mederi.workspace.WorkspaceRollbackThirdReviewTest \
  --tests xyz.mederi.workspace.WorkspaceJournalThirdReviewTest --console=plain
```

新测试源码：`temp/worktree-review-round3/src/xyz/mederi/workspace/`。日志：`temp/worktree-review-round3/gradle.log`、`gradle-extra.log`。已保存 XML：`temp/worktree-review-round3/results-run1/`、`results-run2/`。测试全在 temp 自建仓库和假文件内完成，未触碰真实数据库。

未重复跑全部 Core/shared 和四平台编译；保存的 regular 收据仍保留，常规 build/test-results 会被本轮筛选测试覆盖，不能拿最新筛选报告冒充全量。

## 已复现的必须修复项

### T01 [P1] 构造执行权限时篡改项目持久配置（R01/R04）

`core/.../workspace/WorkspaceManager.kt:2667-2684` 中 buildWorkspacePermissions 把 deniedPaths 映射到当前 checkout 后，调用 `manager.updateSettings(ws.projectId, remappedSettings)` 写回项目唯一设置。

运行一个 managed action 就会把用户设的 Local secret.txt 替换为特定副本路径；此后原项目和其他副本失去原保护，其他会话待批/权限版本还被无关操作改变。项目黑名单不能随当前执行工作区持久变换。

**修复要求：** 保留配置原值，按原项目根、当前工作区及精确产物区计算执行期投影；原始拒绝路径仍受保护，不能用临时复制 SettingsStore 或写回全局设置做映射。通过上述真实 Mederi API 回归，并补 Local/两个 sibling checkout/产物区的交叉读写验证。

### T02 [P1] 重启重放所有成功 Handoff，覆盖最新合法绑定（R08/R09）

`WorkspaceManager.kt:1358-1384` 对所有 SUCCEEDED HANDOFF 调 repairSucceededHandoffBinding，判断只有“当前不等于本条目标”；不核本条是不是最后生效操作、不核绑定 generation/当前是否仍为本条源。

Store 按 updatedAt 倒序返回。真实 Local→Worktree→Local 两次正常成功后，启动扫描先看到最新回迁，无需修复；再看到旧去程，把当前 Local 强行改回旧 Worktree。R09 将扫描接入 initialize 后，该问题成为真实每次启动可能触发的行为。

**修复要求：** 成功历史是审计事实，不能当作需要重放的指令。仅凭持久事务/CAS/明确未完成恢复意图处理坏账，不能遍历旧 SUCCEEDED 强制纠正。补多次往返、多个副本、已归档、SQLite reopen 的最新绑定保持测试。

### T03 [P1] 后台进程先启动后取租约，拒绝时进程已经执行（R11）

`core/.../tools/ShellTools.kt:159-175` 先 builder.start/register，再 acquireExecutionLease。已有独占管理意图时，acquire 抛错，工具返回 Error，但进程已运行且没交给 BackgroundTaskManager，仍能写盘。

**修复要求：** 启动前先取租约并校验授权；启动/登记/交接任一步失败都必须回收进程并释放租约。attachTerminalHook 返回 false 也需要可靠处理，不能遗失 lease。测试必须断言拒绝发生时没有子进程副作用。

### T04 [P1] 环境脚本把别人的独占意图当作自己的保护（R11）

`WorkspaceEnvironmentService.kt:126-133` 捕获任意 WORKSPACE_BUSY 后将 lease=null，继续执行。代码无法证明当前调用拥有管理意图；外部 action 和归档并发时也会绕过互斥。

**修复要求：** 常规 action 忙时拒绝。Handoff 内部 POST_SETUP 若需复用管理临界区，应传不可伪造的管理所有权/内部执行许可，不能用“拿不到共享租约”推断自己拥有独占。

### T05 [P1] 归档恢复指纹忽略 ignored 内容，仍会强删新数据（R06）

`WorkspaceManager.kt:1522-1536` 以 computeFingerprint 判无新变化；`WorkspaceSnapshotService.kt:122-135` 只解析不带 --ignored 的 git status，不覆盖 ignored 文件，也跳过 symlink 内容指纹。

真实 archive 快照日志持久后中断，用户改 ignored.log，当前指纹与旧指纹仍相同，扫描继续 remove --force；新内容不在快照且随 checkout 删除。指纹计算失败/快照指纹缺失时 hasPostSnapshotChanges 也被视为 false，属于同一失败放行问题。

**修复要求：** 对归档 PREIMAGE 的完整范围做 NOFOLLOW 文件/目录/链接/模式/暂存区/新增条目的后像核验，未知状态失败即停，核验与删除持管理意图。Git status 的“没有变化”不能代替完整归档范围未变化。

### T06 [P1] 只在副作用后写阶段日志，崩溃后伪装回滚完成（R10）

`WorkspaceManager.kt:922-957` 在 materialize/copyIncludeFiles 之后才写 affectedPaths；中断在实际文件已写、saveOperation 尚未持久时，日志没有该阶段路径。恢复把空/不完整 affectedPaths 传给 rollback 后标双方 READY/操作 FAILED，实际目标文件仍留在原处，ref 也缺少完整恢复证明。

**修复要求：** mutation 前持久完整意图、基线与将影响路径，mutation 后持久已验证后像；不能在缺少可证明所有权时宣称成功回滚。测试注入在真实副作用与持久回执之间，覆盖 SQLite 重开，而不只保存阶段记录之后的理想状态。

### T07 [P1] 普通恢复修了末级链接，rollback 路径仍会写穿（R05）

`WorkspaceSnapshotService.kt:727-730` preimage 为 FILE 时直接 currentFile.writeBytes，没有替换当前末级 symlink；noSymlinkAncestor 只查父级。

源的合法 symlink 替换了目标原普通文件，journal 准确记录 symlink 后像且验证匹配，回滚原普通文件时便覆盖链接指向的外部文件。独立回归外部内容从 external-original 变成 fake protected content。

**修复要求：** 正式 restore、transfer、rollback 共用有 NOFOLLOW 语义的类型转换/落盘原语，避免只修一个调用点。链接本身应被替换，合法链接仍可保存；父级竞态、目录类型变换同样需要验证。

## 源码审查确认的其余未完成项

下列依据生产调用链确认，未计入上述七个独立运行失败数。

### T08 [P1] Handoff POST_SETUP 仍能走 permissions=null 裸跑（R01）

`WorkspaceManager.executeHandoff` permissions 默认 null；真实 WorkspaceApiImpl/MederiAiCore 两参数调用不传权限。`:987-993` POST_SETUP 把 null 原样传给 EnvironmentService，而该 Service 仍保留 null→ProcessBuilder 裸跑分支。新 buildWorkspacePermissions 仅接 prepareSessionWorkspace/runWorkspaceAction，没有覆盖此路径。

补齐真实 Handoff 的权限、审批归属和管理所有权；生产入口缺上下文应拒绝，不能依靠 null 裸跑兼容测试。

### T09 [P1] 生产快照没有工作区数据，UI 新目标推导和恢复按钮仍接不上（R13/R14）

`app/shared/.../jvmMain/.../MederiAiCore.kt:857-891` getSnapshot、`MederiEventAggregator.kt:74-104` 初始快照均没有水合 workspaceInfo/activeWorkspaceOperation。生产 core/桥层没有发射 WORKSPACE_CHANGED/WORKSPACE_OPERATION_UPDATED，UI 也没有额外读取 getConversationWorkspace 补取。

因此真实 Local 会话 sourceKind=null，`WorkspaceViewModel.kt:415-419` 推导目标 LOCAL，仍被真实 Manager 的同类型约束拒绝；已有工作区不能正确回显。恢复按钮依赖的 operation 同样没有数据；Manager.getConversationWorkspace 还排除了 RECOVERY_REQUIRED，即使补水合也会遗漏最需要恢复的状态。

补初次打开/重连/实时事件的同一数据链，以及异常操作查询；使用真实桥/真实 Manager 的契约集成测试。Mock 主动种下这些字段不能验证生产水合。

### T10 [P1] 动态 provider 用临时子代理 ID 解析宿主 Store，失败后静默降级（R03）

`TurnExecutor.kt:461-468` 始终 resolver.resolve(sessionId)，resolver 来自父宿主 Store；SubagentRunnerImpl 的子 session 只在独立 InMemorySessionStore，实际执行依靠 inheritedWorkspace。动态 provider 用子 sessionId 查宿主 Store 失败，被 runCatching.getOrNull 吞掉，不能得到工作区凭证。

受管 checkout 位于宿主 controlRoot 时，子代理工具无法获得当前 checkout 的精确例外，可能等待本不需要的越界批准；workspace generation 也不能验证。不能以 resolve 失败返回 null 当作安全降级。应以真实父会话/绑定重验继承身份，并保持子代理批准归父会话。

### T11 [P1] SETUP 返回 FAILED 却被 Manager 当作执行完成（R01/准备门禁）

EnvironmentService 在 permissions 非空时失败返回 FAILED operation，不抛异常。`WorkspaceManager.kt:456-463` 不检查返回状态，直接 setupExecuted=true，后面继续 READY；Handoff POST_SETUP 也忽略结果。默认宿主目录中 buildWorkspacePermissions 本身又未提供 VerifiedWorkspaceRoot，脚本正常写 checkout 可能被控制目录保护拒绝，却继续放行模型。

统一处理 SUCCEEDED/FAILED/WAITING_APPROVAL/CANCELLED，失败不能构建 READY/清源，等待批准不能被当作完成；验证真实 API 的 exit 非零、权限拒绝、超时场景。

### T12 [P2] 对话框 owner 仍是可变字段互相比，旧响应可污染新面板（R15）

`WorkspaceViewModel.kt:501/598/483` 等只比较当前 dialogOwnerSessionId 与当前 conversationId。A 请求 recovery→切 B→B 打开管理（owner=B）→A 响应回来，B==B 通过，A 面板出现在 B。同会话关闭后打开其他面板也可被旧响应替换。

必须捕获请求 owner，加单调 request generation/operation owner；切换、dismiss、新开面板使旧请求失效。当前 openHandoff 特例捕获 sessionId 仅解决直接 A→B 的部分情况。

### T13 [P1] 环境/动作/宿主设置/创建分支仍缺真实 UI（R14）

Sidebar pin 菜单已经接上，状态条也新增 recovery callback，这些修复应保留。但生产 UI 对 saveProjectEnvironment/runWorkspaceAction/updateWorktreeSettings/createWorkspaceBranch 仍无调用方，没有原 spec 要求的环境编辑/动作、宿主 Worktree 设置、创建分支闭环。

这不是原生环境凭据缺失，而是未实施功能。必须在现有 UI 架构里补实际入口与窄屏交互。当前 phone 测试仍直接调 VM、没有 viewport/fontScale/实际按钮触达；状态条新增按钮 30dp 也不满足原 spec 的 44dp 触达要求。

### T14 [P1] RESTORE 成功核验仍不完整（R07）

`WorkspaceManager.kt:1586-1595` 只检查 manifest 的 FILE hash，没有检查 index、symlink/linkTarget、墓碑、mode、HEAD/common-dir 注册及多余路径。工作树字节匹配不意味着暂存区已恢复：在恢复 index 前崩溃可以文件全对、index 仍为 HEAD，扫描仍宣告 READY。原五项回归之一仅固定了普通文件缺失这一种情况。

应按持久阶段与完整快照语义核验，或继续幂等恢复；不能以 FILE 子集代替恢复完成。

## 下一轮应如何验收

1. 保留本轮已通过的五项回归，新增七项先提升为常规回归；不要改断言、白名单或 baseline 将缺陷豁免。
2. 测试覆盖实际生产装配，不仅是新方法存在/编译成功。优先验证项目权限配置不变、历史不重放、独占在进程启动前生效、完整 PREIMAGE、日志中断的真实后果。
3. 补真实 getSnapshot/observe/重连与 Manager 的契约测试。UI 测试必须从实际入口点击并读取真实水合数据，不能让 Mock 提供生产缺失的状态来证明接线正确。
4. 同步架构和 delivery 到实际状态；逐项区分已修复的具体回归、仍缺的同类场景、未实施 UI 和外部环境证据。
5. 最终代码确定后再重新收集 regular/macOS/Linux/实机证据。本轮没有通过最终验收，也没有替实施者修产品实现。
