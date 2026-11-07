# 03 运行时接线与界面规范

版本 WT-2026-11-02.1。新增业务全部在AiCore/Core；desktop main只注入IDE/PTY平台能力，共享UI不调用java.io/Git。

## R01 必须接通的源码入口

先读架构对应章节，只打开下列文件的相关段落；行号随开发变化，不按旧行号机械编辑。

| 入口 | 必须改的行为 |
|---|---|
| core/model/Session.kt、api/SessionApi.kt、api/impl/SessionApiImpl.kt | selection、workspace绑定、archive/pin；保持旧create入口LOCAL兼容 |
| core/store/SessionStore.kt、InMemorySessionStore.kt、store/sqlite/SqliteSessionStore.kt | 全部新增字段读写；不因普通compose state更新清掉workspace字段 |
| core/Mederi.kt、session/SessionManagerImpl.kt | Manager/Store/驱动/恢复actor装配；初始化gate；项目/会话删除先资源处置；发送接纳幂等 |
| core/koog/TurnExecutor.kt sendMessageInternal | 取resolver快照，不再用project.directory作为执行目录；先持久接纳消息，等待READY再构建prompt/模型执行 |
| 同文件 runTurn | 第二个directories硬编码同样替换；工具、diffTracker、Agent runtime都用该轮ExecutionWorkspace |
| 同文件 resolvePlanApproval、dispatchPendingEventMessages | PlanStore取稳定artifactDirectory；批准计划自动继续、子代理事件/后台任务唤醒也经过工作区gate |
| core/tools/subagent/SubagentRunnerImpl.kt、SpawnAgentTool/ToolFactory参数链 | 临时Session未必在Store；必须显式继承父ExecutionWorkspace+generation，不能对子代理再按projectId发现目录 |
| core/plan/PlanStore.kt、Notebook.kt、prompt/EnvironmentInfoProvider.kt | 明确接artifactDirectory；源码cwd与计划存储根分开；环境同时描述工作区/产物位置 |
| core/project/AgentsFileLoader.kt | 当前工作区内向上/懒发现；override优先级；禁止跑到宿主原checkout加载旧规则 |
| core/tools/diff/TurnDiff.kt、TurnDiffTracker.kt、ParentDiffRegistry、store/SqliteDiffStore.kt | 保存origin workspace信息；跨工作区不能合并同路径change；历史Diff以origin展示 |
| app/shared core/contract/AiCore.kt、dto/ChatPromptInput.kt、dto/ConversationState.kt、models/ChatModels.kt | 新接口/状态/选择；用户消息稳定id保持；不新增UI待发队列 |
| shared jvm core/bridge/MederiAiCore.kt、MederiEventAggregator、MederiModelMapper | 所有创建/发送/快照/项目刷新/删除路径同步；唯一Core事件源 |
| shared common core/bridge/ServerAiCore.kt、core/mock/MockAiCore.kt、jvm server/Server.kt | 新DTO/URL/异常/SSE、幂等操作与archive/pin均同步；server实际文件在app/shared，不在server薄宿主main |
| shared ui/WorkspaceViewModel.kt、DiffPresenter.kt、Sidebar.kt、MainScreen.kt、components/ChatInputCard.kt和ChatInputSelectors.kt | 工作区选择、已接纳准备状态、归档/固定入口、错误/恢复、手机面板；旧用户输入流程仍同源 |
| shared ui/TerminalViewModel.kt、core/contract/Terminal.kt | key改workspace:<id>#<tab>，cwd由resolver；交接不复用另一位置PTY |
| shared ui/host/ProjectFileTreeProvider.kt及jvm实现 | 以workspaceId绑定/缓存失效；本地打开不能固定启动user.dir。遥控查询新AiCore files接口 |
| shared util/GitUtils各actual | 旧直接读取.git/HEAD的helper不作worktree分支真理源；统一显示Core仓库状态 |

表中core路径前缀 `core/src/commonMain/kotlin/xyz/mederi/`；shared common/jvm前缀分别 `app/shared/src/commonMain/kotlin/xyz/mederi/`、`app/shared/src/jvmMain/kotlin/xyz/mederi/`。新文件遵循现有包声明，不能根据目录猜已有类package。

## R02 执行 gate 与历史

所有能启动模型/工具的入口必须先获取当前generation的ExecutionWorkspace执行租约。REQUESTED/PREPARING/TRANSFERRING/ARCHIVING/RESTORING/RECOVERY_REQUIRED/MISSING不可执行。MISSING不能重新创建空目录冒充原副本。主turn、后台子代理、定时/后台唤醒、计划批准续跑全部受约束。

首次send：既有稳定messageId接纳/持久化成功→工作区准备→只消费一次该待执行输入。失败输入仍存在并显示准备错误；“重试准备”只重试operation，不重新sendMessage。cancel准备不得把用户消息标成AI完整交付，也不能伪造助手最终正文。

Handoff建立迁移意图后拒绝新执行租约，暂停自动续跑；stopAffectedSessions=true时停止所有共用checkout的Mederi任务，包含其它project指向同一仓库checkout的会话。abortAndJoin完成、ProcessRegistry确认组回收、文件写锁/租约释放后才能改Git。不把“请求cancel已发”当作进程已停。

历史Diff读取仍返回存储before/after，能离线查看；点击历史文件显示“来自旧工作区”的来源，不自动把绝对旧链接替换成当前副本相对路径。打开当前对应文件必须显式独立动作且校验路径/黑名单。旧TurnDiff origin=null解释为该Project原LOCAL，不因当前Session.worktree改变。聊天rollbackToMessage保留原聊天回退语义，不附带新文件回滚；未来文件回滚需独立有预览的接口。

## R03 沙盒与文件路径

增加内部不可序列化 `VerifiedWorkspaceRoot`，只能由Manager核对registry/canonical/git身份后产生；UI/REST不能传该token。SandboxPaths/ExecutionPermissions/CommandSandbox消费它。

有效写根：当前workingDirectory + D09的verifiedArtifactWritablePaths（现有计划产物所需）+原有实际temp+用户extraWritablePaths；不是原Project.directory整体。黑名单先canonical化：原项目内规则除原路径外添加当前workingDirectory对应相对路径；项目外规则不重定位。项目内部普通绝对path token不能因迁移变成“任意同名文件”。

宿主controlRoot覆盖worktrees时：文件工具对controlRoot的保护仅给VerifiedWorkspaceRoot.checkoutRoot精确例外，其它数据库/快照/副本仍保护；嵌套`.git/.agents/.codex/.aws`等保护继续生效。

macOS Seatbelt：对controlRoot的write deny使用require-all(subpath controlRoot, require-not(subpath verifiedCheckoutRoot))，再按现有writableRoots给允许规则；对workspace内Git/用户deny追加独立拒绝，拒绝优先。不得留下一个覆盖整个controlRoot的deny后误以为子allow能覆盖它。

Linux：controlRoot只读bind在前，只有当前verified workingDirectory/必要artifact产物子路径精确可写bind在后；工作副本内保护目录ro-bind再后，显式deny遮罩最后。不得把整个worktrees根bind-RW。黑名单内的额外根不得在准备阶段mkdir。

FULL沿既有语义免默认审批，显式deniedPaths/deniedCommands仍强制。内部Git服务操作不通过模型shell解除`.git`保护；没有人类操作意图和有效管理计划，不能使用Driver作为通用文件/Git后门。

文件API：list/read参数加 `area: WorkspaceFileArea=PROJECT`，enum PROJECT/ARTIFACT，分别以workingDirectory/artifactDirectory为根。请求只接受相对path，canonical检查禁止穿越/任一级symlink逃根；列表过滤拒绝条目；read先权限后读取，默认256KiB、上限1MiB；offset字节边界需UTF-8安全切分。二进制返回mime与无text说明，不丢给Text解码替换。遥控UI在宿主上读，不访问手机本地的server路径。log/file/快照读取不能绕过当前权限。

## U01 输入区布局

输入工具条新增 `WorkspaceSelector`（GitBranch + Local/Worktree短标签）。桌面高度跟现有ControlHeight，宽度最多140dp、标签单行省略，tooltip完整环境/分支；权限与计划保留单图标，计划组标题加Feather.CheckSquare装饰图标（无需新语义资源），组内自动/手动文字保留。禁止再引入单独长“自动批准”pill。

新对话workspace选择暂存属于**未创建对话的compose选择**，可在第一次createConversation(input)传入；创建之后状态以Core Session为真理源，不在AppState保存第二份执行路径。切换项目重置选择为LOCAL，清基线/环境；已有会话不能通过输入选择器直接变路径，只能Handoff。

下拉/Sheet顺序：位置(Local/Worktree)→基线分支搜索（Worktree才有，含当前HEAD）→携带当前修改选项/变更数→环境可选→完整路径只读说明。普通分支选择默认不carry，明确选择“当前本地修改”才启用；无Git/无HEAD有具体说明，Local可用。

提交后在对话区显示工作区操作状态条（不冒充工具输出/最终交付）：准备/复制/等批准/初始化/恢复；可取消或失败重试。发送按钮仍走统一用户输入接纳，不在UI排队，不重复提交准备失败的消息。

## U02 已有对话与管理

标题栏显示短工作区身份+分支/Detached，菜单：打开IDE、终端、创建分支、Handoff、项目工作副本管理、固定/取消固定、归档。新branch创建对话显示具体name/head，冲突不自动换名字重试。当前平台无IDE/PTY能力时禁用对应动作并解释；手机仍有文件、Git状态、命令动作日志。

Handoff面板必须有：源/目标完整可复制路径、基线/taskBranch、staged/unstaged/untracked数量、受影响运行会话列表、冲突路径；有活动执行显示“停止列出的任务后交接”勾选默认false。dirty目标不给可强制覆盖按钮；提供关闭/去目标检查/刷新预览。preview过期/外部变化要求重预览，按钮不沿旧token再发。

Handoff进行中，涉及的对话显示迁移状态、阻止新执行；取消按钮按Core state显示“取消并恢复中”，不能一点击就宣称已取消。RECOVERY_REQUIRED给具体两边路径/快照时间/日志，以及“保留现状并处理”与“恢复至新副本”；无核验动作不能强行标READY。

项目设置“工作副本”列表含关联会话、Local/Managed、branch/head、状态、最后使用、实际空间（未知则“未统计”）；提供进入会话/恢复/归档。宿主设置“工作副本”页配置根、15默认限额、自动清理开关及全局实际作用域。项目环境编辑支持平台覆盖/脚本/动作，不把脚本直接放聊天输入。

侧栏：固定会话区来自Session.pinned；默认项目会话列表过滤archivedAt非null，提供归档列表入口；归档列表能读消息/Diff并恢复。不要复用AppState的sidebar.pinned布局偏好作会话固定。归档固定会话确认解除固定；普通归档动作已明确说明会回收受管副本并留恢复快照，不再重复多层确认。项目/会话永久删除必须显示快照保留与Local目录不删除的具体影响。

## U03 窄屏/可访问与状态归属

- 320dp工具条允许FlowRow但发送按钮独立；工作区入口+权限图标不得溢出。280dp、1.6fontScale也可用。
- 手机详细配置直接bottom sheet、分支单独搜索列表；已有对话标题Git菜单；Handoff源→目标一列，主动作固定底部，内容滚动；Diff/文件全屏，不复刻桌面三栏。
- 菜单/列表按钮手机热区≥44dp；文本可换行；键盘焦点、Escape/back dismiss、role/selected/contentDescription齐全。用户可见文案全部中英资源，状态不依赖颜色。
- 文件树/文件预览/Diff cache key含workspaceId和generation；loading旧内容可以显示明确来源，但未知所属project绝不借selectedProjectId权限。异步旧响应用token丢弃，切会话不能改错目标。
- WorkspaceViewModel只调用Core/Presenter，Composable纯表现；新增WorkspacePresenter管理按session/workspace投影，operation只读。配置保存成功才回显，不乐观授予权限。
- 事件可丢/乱到达；按operation.version忽略旧事件，先拉真实快照；命令日志offset续读，重连不重复启动操作。

## U04 UI验收截图与真机步骤

Compose测试固定种子数据，输出：desktop-new-chat、menu、mobile-320-selector、mobile-280-large-font、handoff-busy、handoff-conflict、recovery、archived-restorable。截图必须是实际Composable，不能imagegen画出来冒充实现。输出在temp/worktree-evidence内。

真机按README Gate：desktop通过idea-mcp启动；手机原生或Wasm遥控连接同宿主，创建工作副本→第一条消息真正写文件→查看该副本文件→工作区动作日志→双向Handoff→归档/恢复→断线重连。核验两端展示同workspaceId/HEAD/operationId。缺IDE/MCP/平台必须标BLOCKED，不把组件截图替代整条链路。
