# 01 冻结决策、领域与 API

规范版本 WT-2026-11-02.1。本文件中的名称、默认值、作用域、错误和兼容行为不由执行者自由更换。Kotlin实际包名遵从现有文件；core新文件放 `core/src/commonMain/kotlin/xyz/mederi/workspace/`，JVM能力按core既有组织实现。共享DTO位于 `app/shared/.../core/contract/models/workspace/`，不把java类型传过契约。

## D01 身份与路径

- Project 继续单目录；Worktree不创建新Project。同一projectId共用执行权限与环境设置。
- LOCAL身份按 `(projectId, canonicalCheckoutRoot)` 唯一，多对话可以共用；MANAGED_WORKTREE与一个顶层会话关联，子代理不另占工作副本。
- `checkoutRoot` 是Git整个检出根；`projectRelativePath` 是Project.directory相对原检出根的路径，根项目为空字符串；`workingDirectory=checkoutRoot.resolve(projectRelativePath)`。仓库子目录项目不得丢掉这个偏移。
- `gitCommonDirectory` 为Git返回后canonical的绝对目录，用作共享引用互斥键。不能通过字符串`.git/HEAD`识别linked worktree。
- `artifactDirectory=原Project.directory/.mederi`，不随交接移动。计划、notebook、子代理报告沿原服务定位。它是独立精确产物根，不等于给整个原项目写权限。
- 默认受管根 `<MederiPaths.root>/worktrees`；快照根 `<MederiPaths.root>/workspace-snapshots`；每副本目录 `<root>/<workspaceId>/checkout`。不能存到用户源仓库里面。目录根变更仅影响新副本，不擅自搬旧副本。
- 真实路径仅由Core生成；所有管理输入使用id，不接受客户端任意删除/恢复目标路径。路径外部被替换为symlink或仓库身份变更则停止管理操作。

## D02 数据定义

以下均为纯领域/Serializable DTO可映射形状；id为字符串，Instant为既有ISO时间字符串，Long非负。

```kotlin
enum class WorkspaceKind { LOCAL, MANAGED_WORKTREE }
enum class WorkspaceState { REQUESTED, PREPARING, READY, TRANSFERRING, ARCHIVING, ARCHIVED, RESTORING, RECOVERY_REQUIRED, MISSING }
enum class WorkspaceOperationKind { CREATE, HANDOFF, ARCHIVE, RESTORE, SETUP, ACTION }
enum class WorkspaceOperationState { PENDING, RUNNING, WAITING_APPROVAL, CANCELLING, SUCCEEDED, FAILED, CANCELLED, RECOVERY_REQUIRED }
enum class WorkspacePhase { VALIDATE, QUIESCE, SNAPSHOT, CREATE_CHECKOUT, COPY_CONTENT, SETUP, DETACH_SOURCE, SWITCH_TARGET, MATERIALIZE_TARGET, VERIFY_TARGET, CLEAN_SOURCE, COMMIT_BINDING, REMOVE_CHECKOUT, VERIFY_REMOVAL, COMPLETE }

Workspace(id, projectId, kind, checkoutRoot, projectRelativePath,
          gitCommonDirectory?, baseCommit?, associatedSessionId?,
          state, generation:Long, lastSnapshotId?, createdAt, lastUsedAt)
// checkoutRoot为预定最终路径；REQUESTED尚不存在，不代表READY。
WorkspaceSelection(kind=LOCAL, baseRef:String?=null,
                   carryLocalChanges:Boolean=false, environmentId:String?=null)
// MANAGED_WORKTREE必须可解析基线；UI默认当前分支/HEAD，只在“当前本地修改”明确启用carry。
Session.workspaceId:String?=null
Session.associatedWorktreeId:String?=null
Session.archivedAt:String?=null
Session.pinned:Boolean=false
// null workspaceId仅兼容旧会话，惰性解析/绑定LOCAL；managed缺失绝不当LOCAL。
ExecutionWorkspace(projectId, workspaceId, checkoutRoot, workingDirectory,
                   artifactDirectory, gitCommonDirectory?, generation)
WorkspaceOperation(id, projectId, sessionId?, workspaceId, kind, state, phase,
                   idempotencyKey, requestHash, sourceWorkspaceId?, targetWorkspaceId?,
                   sourceSnapshotId?, targetSnapshotId?, cancelRequested:Boolean,
                   progressCompleted:Long, progressTotal:Long?, error:WorkspaceError?,
                   version:Long, createdAt, updatedAt)
WorkspaceError(code:WorkspaceErrorCode, message:String, retryable:Boolean,
               operationId:String?, diagnostic:String?)
```

新增 `WorkspaceErrorCode`：NOT_GIT_REPOSITORY、NO_BASE_COMMIT、BASE_CHANGED、PROJECT_SUBDIRECTORY_MISSING、WORKSPACE_BUSY、WORKSPACE_MISSING、STALE_GENERATION、STALE_PREVIEW、BRANCH_IN_USE、TARGET_DIRTY、PATH_DENIED、IDENTITY_CHANGED、SNAPSHOT_INCOMPLETE、SNAPSHOT_CORRUPT、EXTERNAL_CHANGE、UNSUPPORTED_REPOSITORY_STATE、UNSUPPORTED_FILE_TYPE、ENVIRONMENT_CHANGED、SETUP_FAILED、SETUP_TIMEOUT、OPERATION_CANCELLED、RECOVERY_REQUIRED、INVALID_REQUEST、IO_ERROR。对外以现有MederiException体系携带结构化code，REST错误DTO同步扩展，不依赖解析英文message。

`WorkspaceOperation`进度只反映已完成阶段/字节，不编造百分比；branch/status从Git查询，不让UI修改缓存作为真理源。associatedWorktreeId在交到LOCAL时仍保留，回去复用同id；归档后也保留供恢复。

## D03 存储与原子提交

data.db新增 `workspaces(id PK, project_id, kind, state, associated_session_id, updated_at, payload)`、`workspace_operations(id PK, idempotency_key UNIQUE, project_id, workspace_id, state, updated_at, payload)`；JSON为完整Serializable对象，独立列只供索引，保存必须一致。sessions新增 `workspace_id TEXT NULL`、`associated_worktree_id TEXT NULL`、`archived_at TEXT NULL`、`pinned INTEGER NOT NULL DEFAULT 0`。

Diff新增origin字段：`TurnDiff.workspaceId:String?`、`checkoutRootAtCreation:String?`、`projectRelativePathAtCreation:String?`、`workspaceGeneration:Long?`。同步Diff payload/存储列需要的映射，旧值null表示历史LOCAL上下文，不表示当前副本。只读旧Diff不要求源目录仍存在；当前 `rollbackToMessage`只回退聊天历史，保持该语义。

新增 `WorkspaceStore`/`InMemoryWorkspaceStore`/`SqliteWorkspaceStore`，Store不得调用Git。工作区Store提供同一data driver上的事务方法 `commitBinding(sessionId, expectedWorkspaceId, expectedGeneration, newWorkspace, associatedWorktreeId, operation)`：原子CAS校验、更新Session绑定、Workspace与operation；失败不得半更新。SessionStore加相应读取字段及archive/pin写入方法；统一事务由data数据库UnitOfWork装配，不能跨独立driver假称原子。

schema真实路径 `core/src/commonMain/sqldelight/data/xyz/mederi/db/data/DataDatabase.sq`；实施时读 `Mederi.kt` 当前 DATA_DB_VERSION，在该值上+1，保留旧迁移。新表CREATE IF NOT EXISTS，新列仅吞“列已存在”；I/O/语法/磁盘故障必须阻止版本推进，不能沿当前runCatching吞全部错误的模式新增危险迁移。迁移成功且字段/表核验后写user_version，旧Session/History/Diff数据完整。配置库无需删重建。

宿主配置 key `workspace.settings`，DTO `WorktreeSettings(rootPath, maxManagedWorktrees=15, automaticCleanup=true, version=0)`；CAS版本更新，不放设备preferences。rootPath必须绝对、不为/或用户源检出目录及其子目录、不为快照库；仅目录可用性失败可返回具体错误，不假造home路径。

## D04 业务边界与生命周期

新增 `WorkspaceManager` 是工作区/操作唯一真理源，`GitWorkspaceDriver`纯Git/I/O，`ExecutionWorkspaceResolver`只从Manager验证后生成快照。Mederi装配一次，SessionManager/TurnExecutor/子代理共享；initialize启动恢复扫描/清理调度，close停止并释放进程。不得在desktop main写业务监听。

同一canonical checkout上的执行租约为共享；管理迁移/归档为独占。进程内按common-dir序列化Git引用写；先取管理意图阻止新租约、再等待旧租约归零，不能一边持引用锁一边无限等AI执行。外部Git不受此锁约束，每次文件/引用改变前检查指纹及预期值。迁移不自动abort无关对话：preflight返回共用源/目标checkout的所有活动会话；用户确认的 `stopAffectedSessions=true` 才通过现有abortAndJoin/stop接口停稳它们，不得杀非Mederi进程。若false且存在活动执行，返回WORKSPACE_BUSY且不改代码。

新增对话 archive/pin业务：归档停止本会话并回收关联受管副本（先快照）；归档成功后默认侧栏隐藏，归档列表可打开/还原；Local目录不回收。固定会话不会自动清理；手动归档固定会话时确认解除固定并继续。删除会话/项目先预检/保护受管工作数据，失败保留记录与恢复引用；不得沿原级联先删元数据。

## D05 AiCore 契约和路由（四处+Mock同步）

原 `createConversation(projectId, agent?)` 保留为默认LOCAL兼容入口，新增 `createConversation(input:CreateConversationInput)`，其中input含projectId/agent/workspaceSelection，沿core CreateSessionRequest映射。预留REQUESTED工作区和选择，**第一条稳定消息持久接纳后**才启动CREATE；不让UI保存队列。待准备用户消息只落一次，重试相同messageId不重复创建或重复模型执行。

接口返回沿AiCore `Result<T>`，下表每个均需bridge/REST/Mock/测试。路径参数URL编码。

| 方法 | DTO结果/输入 | REST |
|---|---|---|
| getRepositoryInfo(projectId) | RepositoryInfo(isGit, checkoutRoot?, projectRelativePath?, headOid?, branch?, branches, dirty, unsupportedReasons) | GET /v1/projects/{id}/repository |
| listWorkspaces(projectId) | List<WorkspaceInfo> | GET /v1/projects/{id}/workspaces |
| getConversationWorkspace(sessionId) | ConversationWorkspaceInfo(active, associated?, operation?) | GET /v1/sessions/{id}/workspace |
| previewWorkspaceHandoff(sessionId,input) | HandoffPreview(token, expiresAt, source,target,fingerprints,affectedSessions,taskBranch,conflicts) | POST /v1/sessions/{id}/workspace/handoff/preview |
| handoffWorkspace(sessionId,input) | WorkspaceOperation | POST /v1/sessions/{id}/workspace/handoff |
| restoreWorkspace(workspaceId,input) | WorkspaceOperation | POST /v1/workspaces/{id}/restore |
| archiveWorkspace(workspaceId,input) | WorkspaceOperation | POST /v1/workspaces/{id}/archive |
| createWorkspaceBranch(workspaceId,input) | WorkspaceInfo | POST /v1/workspaces/{id}/branch |
| getWorkspaceOperation(id) / cancelWorkspaceOperation(id) | WorkspaceOperation | GET /v1/workspace-operations/{id} / POST .../{id}/cancel |
| listWorkspaceOperations(projectId) | List<WorkspaceOperation>（含需恢复和活跃项） | GET /v1/projects/{id}/workspace-operations |
| setConversationArchived(sessionId, archived, requestId) | WorkspaceOperation（成功后Conversation投影更新） | POST /v1/sessions/{id}/archive |
| setConversationPinned(sessionId,pinned) | Conversation | POST /v1/sessions/{id}/pin |
| listArchivedConversations(projectId) | List<Conversation> | GET /v1/projects/{id}/archived-sessions |
| get/updateWorktreeSettings | WorktreeSettings（更新带version） | GET/POST /v1/worktree-settings |
| list/saveProjectEnvironments(projectId, input?) | 环境列表/保存结果 | GET/POST /v1/projects/{id}/environments |
| runWorkspaceAction(workspaceId,input) | WorkspaceOperation | POST /v1/workspaces/{id}/actions |
| getWorkspaceOperationLog(id,afterOffset,maxBytes) | LogChunk(bytesUtf8,nextOffset,eof,truncated) | GET /v1/workspace-operations/{id}/log |
| listWorkspaceFiles(workspaceId,path) | List<WorkspaceFileEntry(name,relativePath,kind,size?)> | GET /v1/workspaces/{id}/files |
| readWorkspaceFile(workspaceId,path,offset,maxBytes) | WorkspaceFileContent(relativePath,text?,mime,truncated,nextOffset) | GET /v1/workspaces/{id}/file |

HandoffInput：targetKind、previewToken、requestId、stopAffectedSessions=false、taskBranchName:String?。Restore/Archive/Action输入必须有requestId；Archive还需 `confirmDiscardIgnored=false`（v1永不使用该值跳过备份；为true也不得删未备份文件，返回INVALID_REQUEST）。创建分支输入 name、expectedHead、expectedGeneration。只允许Git合法且refs/heads下的名字，默认新任务名 `mederi/<sessionId短id>`，重名返回错误并让用户改名，不force。Preview有效60秒，token关联源/目标指纹、设置version、影响会话集合及冻结分支名；实际提交重新校验，不把token当锁。

WorkspaceInfo含workspace、当前branch/head、dirty、capabilities、bytesOnDisk?、error?；ConversationWorkspaceInfo未知归属不得fallback到selectedProject。ConversationState增加workspaceInfo/activeWorkspaceOperation，Conversation增加archivedAt/pinned（默认值兼容旧JSON）。新增事件 WORKSPACE_CHANGED、WORKSPACE_OPERATION_UPDATED、CONVERSATION_ARCHIVE_CHANGED、CONVERSATION_PIN_CHANGED，payload使用Serializable信封（JSON），包含projectId/sessionId/workspaceId/operationId/version；重连由REST快照水合，不依赖事件送达。

## D06 幂等、日志和故障语义

同一宿主内requestId唯一，key=`kind:requestId`；保存规范化请求JSON SHA256。相同key+相同hash返回原操作；不同hash报INVALID_REQUEST。重复HTTP/重连不能重跑setup/复制/cleanup。操作阶段写前日志，副作用完成后写完成标志；重启根据真实Git/文件指纹校对，不单凭DB phase推断副作用。

操作日志UTF-8连续排空，上限每操作8MiB，超出持续排空但记录truncated；afterOffset为字节偏移，响应不要从UTF-8中间解码。后台动作与setup进程进入现有ProcessRegistry，取消按登记进程组收割。操作内部调度有独立scope；客户端请求断开不代表用户取消。


## D07 输入/输出补充（字段类型冻结）

共享AiCore新增方法的参数名称与上表相同，方法均 `suspend ...: Result<对应结果>`。无输入表行用独立方法：`getWorktreeSettings()`、`updateWorktreeSettings(settings:WorktreeSettings)`、`listProjectEnvironments(projectId:String)`、`saveProjectEnvironment(projectId:String,environment:ProjectEnvironment,expectedVersion:Long)`；save结果ProjectEnvironment，list结果List<ProjectEnvironment>。不实现带斜杠的字面方法名。

`CreateConversationInput(projectId:String,agent:AgentOption?=null,workspaceSelection:WorkspaceSelection=WorkspaceSelection())`。`HandoffPreviewInput(targetKind:WorkspaceKind,taskBranchName:String?=null)`。`HandoffInput(targetKind:WorkspaceKind,previewToken:String,requestId:String,stopAffectedSessions:Boolean=false,taskBranchName:String?=null)`。`RestoreWorkspaceInput(requestId:String)`。`ArchiveWorkspaceInput(requestId:String,confirmDiscardIgnored:Boolean=false)`。`CreateWorkspaceBranchInput(name:String,expectedHead:String,expectedGeneration:Long)`。`WorkspaceActionInput(environmentId:String,actionId:String,environmentVersion:Long,requestId:String)`。

`WorkspaceInfo(workspace:Workspace,headOid:String?,branch:String?,dirty:Boolean,capabilities:WorkspaceCapabilities,bytesOnDisk:Long?,error:WorkspaceError?)`；capabilities为可执行动作集合 `Set<WorkspaceCapability>`，enum OPEN_FILES/OPEN_TERMINAL/CREATE_BRANCH/HANDOFF/ARCHIVE/RESTORE/RUN_ACTION，不是UI自猜状态。

`ConversationWorkspaceInfo(active:WorkspaceInfo,associated:WorkspaceInfo?,operation:WorkspaceOperation?)`。RepositoryInfo.branches为`List<GitBranchInfo(name:String,headOid:String,inUseByCheckout:String?)>`，unsupportedReasons为`List<String>`；其它字段类型按D05字段名：isGit/dirty为Boolean，路径/head/branch为String?。HandoffPreview.source/target为WorkspaceInfo，fingerprints为源/目标字符串hash，affectedSessions为`List<AffectedSession(id:String,title:String,running:Boolean)>`，conflicts为`List<WorkspaceConflict(path:String,reason:WorkspaceErrorCode)>`。

`WorkspaceFileEntry(name:String,relativePath:String,kind:WorkspaceFileKind,size:Long?)`，kind FILE/DIRECTORY/SYMLINK；`WorkspaceFileContent(relativePath:String,text:String?,mime:String,truncated:Boolean,nextOffset:Long)`。文件接口area默认PROJECT，path:String（列表默认空字符串）、offset:Long=0、maxBytes:Int=262144，read上限1048576；WorkspaceFileArea PROJECT/ARTIFACT。`LogChunk(text:String,nextOffset:Long,eof:Boolean,truncated:Boolean)`，API名称不用bytesUtf8避免实际ByteArray与UTF8字符串混淆。

operation日志路由参数afterOffset/maxBytes与文件同类型/限制。归档接口返回WorkspaceOperation，不等待长时间快照完成才返回Conversation；Local归档也产生立即完成的ARCHIVE操作。restore会话对Local只清archivedAt，对managed先确保副本READY再清archivedAt。pin接口只改pinned，返回Conversation，不能偷偷unarchive。archive成功事务写archivedAt并解除pinned，失败二者保持。

## D08 持久计划、重试与恢复接口（补足，强制）

D02模型补充字段：Workspace.pendingSelection:`PendingWorkspaceSelection?`、taskBranchName:`String?`；前者保存selection原输入、已解析baseOid、sourceCheckoutId、environmentId/version/hash（创建时冻结，不能从UI重建），后者只标识本会话分配的任务分支名，实际ref/HEAD仍每次查询Git。WorkspaceOperation.parentOperationId:`String?=null`、operationPlan:`WorkspaceOperationPlan`、phaseRecords:`List<WorkspacePhaseRecord>`、recoveryResolution:`RecoveryResolution?`。

`WorkspaceOperationPlan`为Serializable sealed interface，分CreatePlan/HandoffPlan/ArchivePlan/RestorePlan/SetupPlan/ActionPlan，每个都包含规范化原请求、权限version、参与工作区id/path/generation、冻结环境脚本/hash（如有）、预期源/目标指纹、taskBranch、GitRefMutation(ref,expectedOldOid,newOid)列表、snapshot引用。`WorkspacePhaseRecord`包含phase、started/finished时间、受影响路径的pre/post type/hash/mode/exists、createdByOperation、预期HEAD/index/ref归属、阶段后像指纹。请求/计划须在任何副作用前持久化；已完成阶段后像须在下一阶段前持久化。requestHash不能代替原计划。

补充 AiCore / Core API / REST / Mock：

| 方法 | 结果 | REST |
|---|---|---|
| retryWorkspacePreparation(sessionId, failedOperationId, requestId) | WorkspaceOperation | POST /v1/sessions/{id}/workspace/retry |
| retryWorkspaceSetup(workspaceId, failedOperationId, expectedEnvironmentHash, requestId) | WorkspaceOperation | POST /v1/workspaces/{id}/setup/retry |
| previewWorkspaceRecovery(operationId, strategy, snapshotId?, keepWorkspaceId?) | RecoveryPreview | POST /v1/workspace-operations/{id}/recovery/preview |
| recoverWorkspaceOperation(operationId, input:WorkspaceRecoveryInput) | WorkspaceOperation | POST /v1/workspace-operations/{id}/recovery |

`RecoveryStrategy { KEEP_VERIFIED_CURRENT, RESTORE_TO_NEW_MANAGED }`；`RecoveryPreview(token,operationVersion,source/target WorkspaceInfo?,eligibleSnapshots:List<RecoverySnapshotInfo>,conflicts,expiresAt)`；`WorkspaceRecoveryInput(strategy,snapshotId:String?=null,keepWorkspaceId:String?=null,expectedOperationVersion:Long,previewToken:String,requestId:String)`。snapshot必须属于该操作的TRANSFER/PREIMAGE/ACCEPTED_TARGET，不能接受任意lastSnapshotId；keepWorkspaceId只能是本操作源/目标。

KEEP_VERIFIED_CURRENT：用户明确选当前保留并绑定的checkout，Core重查源/目标身份、无活跃执行、全量当前快照成功后，保留所有文件原状，仅修正可验证metadata/绑定/代次；没有可验证身份的另一侧保持MISSING/RECOVERY_REQUIRED，不自动删。RESTORE_TO_NEW_MANAGED：从用户所选操作快照恢复到新注册副本、核验后切绑定，原两边不覆盖，保留恢复引用。恢复操作同样使用持久journal，成功才释放对应工作区gate。恢复预览60秒有效，版本/指纹变化拒绝旧token。

准备重试沿同一pendingSelection和已注册副本续作，失败结果不明时必须走恢复preview，不重复add/覆盖。setup重试必须是新SETUP operation，核expectedEnvironmentHash；不重复原用户消息、不重复Handoff。重试/恢复新请求id幂等，必须记录parentOperationId。


## D09 可版本化配置与运行产物严格区分

artifactDirectory仍为原项目`.mederi`，但运行产物仅为其 `plans/`、`plans-done/`、`plans-voided/`、`research/`、`notebook.md` 及现有Plan/子代理服务实际使用并在R01核对的报告路径。Manager产生 `verifiedArtifactWritablePaths:List<String>` 精确允许这些根，不把`.mederi`整体作为源代码工具额外白名单。新增产物目录需在此清单/架构文档登记，不能由模型任意追加根。

`.mederi/environments/*.json` 是可版本化项目配置，**不在上述排除清单**。允许Git跟踪，正常随checkout/branch/handoff转移，不能因为`.mederi`下存在tracked环境配置就拒绝创建副本。只有Git跟踪了运行产物本身，管理操作才以UNSUPPORTED_REPOSITORY_STATE拒绝，避免移动原项目中的运行数据。

环境列表/保存接口补充可选 `workspaceId:String?=null`（REST query workspaceId）：null在原Project.directory读写，指定时必须属于projectId且在该workspace.workingDirectory读写。已有对话运行setup/action总是读自己的工作区版本；新对话选择冻结源配置并在checkout中核对hash。目标基线配置与选择不一致→ENVIRONMENT_CHANGED并让用户重选，不自动拿原checkout配置覆盖目标。环境version/hash按配置文件内容校验，不是全项目所有分支的一份可写缓存。

## D10 状态转换与代次（强制）

| 场景 | Workspace状态转换 | operation状态/执行gate |
|---|---|---|
| 首次准备 | REQUESTED→PREPARING→READY | PENDING→RUNNING→SUCCEEDED；READY前不调用模型 |
| 准备无副作用失败/取消 | 回REQUESTED，保留终态操作 | FAILED/CANCELLED；必须显式retry，不因UI重组/重连重跑 |
| 准备已建副本后失败/取消 | PREPARING（可判定失败）或RECOVERY_REQUIRED（结果未知） | FAILED/CANCELLED/RECOVERY_REQUIRED；保留目录，重试/恢复可操作，不显示无限加载 |
| Handoff前置拒绝 | 源/目标保持原状态 | FAILED；无Git副作用 |
| Handoff进行中 | 源/目标TRANSFERRING，绑定保持源 | RUNNING/WAITING_APPROVAL/CANCELLING；所有涉及checkout关闭新执行租约 |
| Handoff成功/完整回滚 | 两侧READY，绑定目标/恢复源 | SUCCEEDED/FAILED或CANCELLED；回滚成功后才放行 |
| Handoff未知外部变化 | 涉及侧RECOVERY_REQUIRED | 同名终态，不能强行READY |
| managed回收 | READY→ARCHIVING→ARCHIVED | 先快照后删，成功才归档对话 |
| 回收快照失败且未改checkout | READY | FAILED，保持会话/副本可见可用 |
| 回收部分副作用未知 | RECOVERY_REQUIRED | 不递归补删 |
| 恢复 | ARCHIVED/MISSING→RESTORING→READY | 验证完成后绑定/解除归档；失败保持原可恢复引用 |
| 独立ACTION | workspace保持READY | ACTION持执行租约；迁移/清理需等待或明确停止 |
| 独立SETUP重试 | PREPARING→READY | 仅成功后打开模型gate；失败显示实际错误 |

管理意图被接纳时，持久递增涉及Workspace.generation并撤销旧执行租约/待批；进入READY或切绑定时再次单调递增。完整回滚也不回退代次。operationPlan同时记preview原代次和接纳后代次，事务CAS用接纳后的实际代次；源/目标预览在意图接纳前验证一次，不能在内部把自己的代次递增误判为外部变化。

ExecutionPermissions的grant同时绑定项目权限generation和workspace generation；批准等待后、文件落盘前、命令启动前重查二者。老grant即使项目权限没有变，也不能在Handoff后继续用于旧工作区。该额外字段内部传递，不允许客户端通过JSON伪造VerifiedWorkspaceRoot。

retry准备/SETUP只有原FAILED/CANCELLED且阶段副作用已核验才创建新操作；不确定状态先走recovery。Handoff的setup子操作重试成功可以使父FAILED→RUNNING续作一次，必须CAS父version且重新取得两边管理意图/指纹；最终父操作成功后不再允许重试setup导致第二次清源。此处是显式受控转换，不能把所有FAILED操作通用重放。
