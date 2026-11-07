# 04 逐任务实施计划

版本 WT-2026-11-02.1。本任务列表是用户已授权目标的执行顺序，不需要每任务重新请用户批准。依赖必须满足再执行，不能为“并行”同时编辑Session/TurnExecutor/Server同一文件。没有可用子代理时串行完成。每任务先阅读引用spec与实际目标文件，禁止重新发明需求；源码与spec不一致到无法满足时记SPEC_CONFLICT。

## 每任务固定交付

- 实现代码、所列真实行为测试、对应架构文档；清除该任务临时stub/TODO。
- `delivery.md` 写任务ID、改动入口、实际命令、报告路径、失败/阻塞、下一任务。不要清理已有用户修改，不擅自commit/切分支。
- core测试新文件统一 `core/src/jvmTest/kotlin/xyz/mederi/workspace/<Class>.kt`，package `xyz.mederi.workspace`；shared统一 `app/shared/src/jvmTest/kotlin/xyz/mederi/ui/workspace/<Class>.kt`，package `xyz.mederi.ui.workspace`，跨平台纯测试可拆commonTest但最终JVM报告类名保持本manifest。
- 测试方法名严格使用acceptance.json的camelCase名称；断言本文指定的外部行为，不测试“字符串能grep到实现”。fixture用temp真实Git、单独作者配置、不联网、不依赖用户Git配置。
- 本文件给出的**新测试**是要求创建的资产，不是假称仓库已存在；现有源码入口见03-R01。实现者不得在测试中读取manifest后生成PASS报告。

任务测试命令模板：`./gradlew :core:jvmTest --tests 'xyz.mederi.workspace.<Class>' --console=plain`；shared对应`:app:shared:jvmTest --tests 'xyz.mederi.ui.workspace.<Class>'`。编译缓存错误可加`-Pkotlin.incremental=false`，不能因此skip测试。只运行与当前任务相关测试；最终统一跑全量回归。

## WT-01 数据模型、迁移、事务与基线

依赖：无。读01-D01/D02/D03/D08和03-R01；修改Session/SessionStore三实现、DataDatabase.sq、Mederi迁移装配，新增workspace领域模型、Store/UnitOfWork。

开始前运行 `python3 scripts/verify-worktree-delivery.py --baseline --evidence-dir temp/worktree-evidence/delivery`，保存旧core/shared全量报告和失败键（联网403等也如实记）。此时baseline可以非零测试但必须有完整测试报告；编译失败不算baseline。记录原git HEAD与已有工作树修改摘要，不覆盖它们。

实现：新增表/列、默认值兼容、持久operationPlan/selection/phase后像；原子CAS绑定；archive/pin字段但此任务不触发回收；旧Diff origin迁移只读语义。动态读当前DB版本+1，不删旧迁移。新增WorkspaceStoreTest所有manifest用例，尤其事务注入失败不留下半绑定、原payload数据保留、关闭重开能读完整选择。

通过：WorkspaceStoreTest全部成功；新旧Session JSON、旧data.db升级与新库均测。文档01-core模型/表/Store同步。

## WT-02 Git发现、驱动与仓库锁

依赖：01。读02-G01/G02、01-D01/D04。实现Driver/稳定NUL解析/身份校验/超时回收，工作区执行租约与管理意图；不在UI或host main加Git逻辑。

通过：GitWorkspaceDriverTest全部成功；真实linked worktree/子目录/特殊名字、同common-dir跨Project互斥、hooks不执行、基线ref无法注入参数、能力不足无写操作。Git外部修改的注入点应为测试Driver/文件系统接口，不用生产sleep竞态测试。

## WT-03 无损快照与两种恢复

依赖：01/02。读02-G03及最后补充。实现bundle/indexBlobs/raw filesystem manifest、hash验证/原子发布、独立对象库恢复、空checkout恢复和受保护原位置回滚。

通过：WorkspaceSnapshotTest全部成功：暂存与工作树不同内容、二进制/模式/删除、仅stage中的blob在源GC后恢复、无源仓库bundle恢复、symlink不跟随、恶意manifest拒绝、ignored残留快照、损坏/空间不足不删源。实现指纹原语供后续任务复用，不用文本diff假装二进制备份。

## WT-04 工作区权限策略

依赖：01/02。读03-R03与01-D01/D04。实现VerifiedWorkspaceRoot及SandboxPaths/ExecutionPermissions/CommandSandbox/LinuxCommandSandbox接线、项目内黑名单映射、产物根，保留两档及命令规则。

通过：WorkspacePermissionTest全部成功；当前工作区写、其它副本/数据库保护、原checkout不自动可写、FULL黑名单、管理身份被替换、extra黑名单不mkdir。原生测试骨架可在WT16建立，不得把模拟profile单测当原生通过。

## WT-05 包含规则与AGENTS override

依赖：02/04。读02-G07。实现Git两次枚举交集与复制报告、每级override加载优先、懒发现黑名单先过滤再去重；不引入自制半套glob。

通过：WorkspaceIncludeTest全部成功；否定/顺序/根锚定、只复制ignored命中、ordinary untracked无carry不复制、symlink父级/已有目标不覆盖、override无include可复制且真实加载、拒绝规则不漏。

## WT-06 环境脚本与操作日志

依赖：01/02/04。读02-G08、01-D06/D08。实现ProjectEnvironment配置序列化/版本、平台覆盖、SETUP/ACTION、正常权限批准/取消、冻结hash、操作日志分页。

通过：WorkspaceEnvironmentTest全部成功；setup等批准时无模型启动、变更配置撤销、重试HTTP不重跑脚本、失败不假READY、进程组取消、UTF8分页/截断仍排空。新setup重试API的Manager方法现在实现，bridge在WT12。

## WT-07 工作区创建与持久消息接纳

依赖：01–06。读02-G04、03-R02。实现CreateConversationInput兼容、REQUESTED预留、第一条消息durable-first、CREATE阶段续作、空/脏/基线变化处理、setup前后gate。为Handoff提供**不绑定会话**的目标准备子流程。

通过：WorkspaceCreationTest全部成功；断线/重复stable messageId只有一份用户消息和副本，失败后只重试准备不重发，模型在READY前零调用，已有消息不冒充最终交付，重启不fallback。

## WT-08 主代理/子代理/续跑路径统一

依赖：04/07。读03-R01/R02。按表逐个替换两个directories入口、resolvePlanApproval和后台续跑gate、子代理临时Session继承、PlanStore/Notebook/EnvironmentInfoProvider独立artifact根、TurnDiff origin。

通过：WorkspaceRuntimeTest全部成功；A/B同路径并行实际写到不同副本；模型看到cwd等于工具cwd；子代理/verify和计划续跑都继承；迁移中所有内部续跑被阻止；旧聊天rollback语义未变；计划在交接前后可查。

## WT-09 双向 Handoff

依赖：02/03/06/07/08。读02-G05所有补充、01-D08。实现preview/hash/60秒过期、受影响会话确认、taskBranch算法、分阶段journal与CAS、目标核验后才清源、POST_SETUP接受后像。

通过：WorkspaceHandoffTest全部成功；Local↔Managed往返及关联副本复用、main不被锁到后台、已命名任务分支检出归属迁移、dirty目标零变更、其它Local会话不偷跑、staged/二进制/untracked保真、setup产生新文件后可成功提交、外部修改不覆盖。

## WT-10 崩溃恢复、取消与人工恢复动作

依赖：09。读02-G05恢复/取消表、01-D08。实现初始化扫描、阶段幂等核验、KEEP_VERIFIED_CURRENT/RESTORE_TO_NEW_MANAGED、preparation/setup retry；不得只把状态改READY。

通过：WorkspaceRecoveryTest全部成功；对每个已定义写阶段前/后注入异常并关闭重开新Manager；确认ref/index/文件/绑定至少有可验证快照且没有双消费；取消前/中/后结果明确；旧preview/他人snapshot不可恢复。未知外部修改不自动rollback。

## WT-11 归档、固定、限额和删除

依赖：03/08/10。读02-G06、01-D04。实现Conversation archive/pin真业务、LOCAL不删、MANAGED完整快照回收、恢复/自动清理默认15/设置CAS、删除会话和项目的资源保护。

通过：WorkspaceArchiveTest全部成功；ignored内容也恢复；固定/运行中/待批/迁移均不自动清；快照损坏/拒绝/空间不足不删；外部worktree不删；目录/Git半清理停止；删除保留恢复引用；更改root不搬旧副本。

## WT-12 API、桥、Server、Mock、事件/快照

依赖：07–11。读01-D05/D07/D08。同步所有接口/DTO/异常/URL、Mapper/EventAggregator、pending批准操作身份、archive/pin侧栏投影；Mock实现同scope、版本、幂等和错误，不能固定return success。

通过：WorkspaceApiTest、WorkspaceContractTest、WorkspaceHttpTest、WorkspaceReducerTest全部成功。HTTP测试用测试serverModule/fixture，不启动正式APP/server；两个项目隔离、未知id、URL编码、重试幂等、SSE丢失重连、旧JSON默认值、版本乱序必须断言。更新architecture02路由/字段/事件、04时序。

## WT-13 文件树、预览、Git状态和终端归属

依赖：08/12。读03-R01/R03/U03。桌面与遥控文件读取统一AiCore workspace files接口；已有宿主provider仅保留平台能力或改为以workspace root绑定的适配，不作另一业务真理源。GitUtils本地猜分支退出状态展示；PTY key/cwd工作区化，移动端动作日志真实查询，未提供远程交互PTY就明确能力限制。

通过：WorkspaceFileUiTest全部成功；桌面与遥控同id/path内容、切换异步旧结果不污染、origin Diff只读、黑名单与穿越、旧PTY不会突然变成新工作区。必要Core读文件行为也计入WorkspaceApiTest。

## WT-14 新对话UI、输入图标、操作状态

依赖：12/13。读03-U01/U03。新增WorkspacePresenter/选择器/分支搜索/环境选择/准备状态条、权限与计划图标与CheckSquare辅助，不改统一发送生命周期；双语资源与共享原子。

通过：WorkspaceControlsTest、WorkspaceSessionLifecycleTest全部成功；不能只测纯VM而不实际点击Composable；准备失败/重试/等批准/取消、未知会话归属、成功才回显、重复发送对账有断言。

## WT-15 Handoff、管理、归档/恢复和手机UI

依赖：11/14。读03-U02/U03/U04。完成标题身份/分支/菜单、受影响会话与冲突preview、恢复选择、归档列表/固定、全局根/限额与项目环境设置、完整窄屏适配。

通过：WorkspaceMobileTest全部成功，输出U04截图并人工目检；320dp和280dp+1.6倍字体可独立完成所有关键动作，无横向溢出、按钮可触达；状态有明确来源，不把截图当实机链路。

## WT-16 原生权限与平台编译

依赖：04/08/15。为WorkspaceNativeMacTest、WorkspaceNativeLinuxTest各建真实后端测试：当前副本可写、其它副本/控制库仍保护、FULL显式黑名单仍禁止读写、artifact精确根可用。required模式缺宿主/后端必须失败，不skip。

本机：`python3 scripts/verify-worktree-delivery.py --native macos --evidence-dir temp/worktree-evidence/delivery`（Linux用linux）。在另一平台同一源码指纹运行对应命令，复制native子目录汇总；脚本核验平台与源码指纹，不接受模拟platformName单测作为原生证据。

shared JVM/Android/wasmJs必须编译，iOS Simulator Arm64在Mac编译；缺工具链需修环境或明确未验收，不可删平台目标。

## WT-17 全量回归、架构同步和证据

依赖：01–16（其它主机native可最后汇总，但最终不能省）。运行 `python3 scripts/verify-worktree-delivery.py --run --evidence-dir temp/worktree-evidence/delivery`：core/shared全量、预定case报告、适用平台编译、相对baseline新增失败检查；已有失败仍逐项披露。修复本次新增失败后重跑相应用例并最终重新跑验证收集，不能改测试预期迁就错误实现。

更新architecture00/01/02/04，AGENTS按新不变量同步，原worktree-plan保持链接到最终规范。README/交付清单写所有真实结果，不提前宣布完成。

## WT-18 Codex对照和实机闭环

依赖：17。使用idea-mcp启动APP/server；执行03-U04步骤并记录桌面/手机宿主、版本、实际id/截图/日志路径。逐项比较官方公开基线；未公开细节标为Mederi明确策略，不伪称Codex内部一致。若发现公开能力差异，补实现和测试，不能勾完清单就忽略差异。

人工证据 `manual.json` 结构见acceptance.json；固定场景不能空写PASS，必须有具体证据路径和观察。最后执行 `python3 scripts/verify-worktree-delivery.py --evidence-dir temp/worktree-evidence/delivery`，exit0才能报告“本规范完整验收通过”。缺native/人工证据时只报告代码完成和未验收项；把需要的环境一次性告知用户，不循环索要无关确认。
