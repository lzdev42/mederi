# 02 Git、快照、交接与恢复算法

版本 WT-2026-11-02.1。所有破坏性算法只在repo temp自建真实仓库验证。本文件冻结默认冲突策略：**脏目标拒绝、绝不自动合并或覆盖**。用户可在原位置处理后重新preview，或继续留在worktree；不依赖模型临场决定安全性。

## G01 可信 Git 执行

Driver用ProcessBuilder argv及git --no-pager，不经bash/cmd。环境移除继承的GIT_DIR/GIT_WORK_TREE/GIT_INDEX_FILE/GIT_OBJECT_DIRECTORY/GIT_ALTERNATE_OBJECT_DIRECTORIES等改变仓库身份的GIT_*变量，仅保留已显式构造的必要变量；GIT_TERMINAL_PROMPT=0、GIT_OPTIONAL_LOCKS=0用于只读探测。写操作不能关闭Git自身锁。LC_ALL=C用于诊断稳定，但机器逻辑只读稳定协议。每次管理操作禁用hooks（`-c core.hooksPath=<宿主只读空目录>`）、fsmonitor、外部diff/textconv；不可借worktree add/switch自动运行项目脚本。检测到管理操作会触发自定义clean/smudge/process filter时先报UNSUPPORTED_REPOSITORY_STATE，不在受信管理服务裸执行任意filter；后续支持必须让filter走项目执行权限并补spec。

常规Git命令默认30秒，bundle/大文件阶段300秒，无超时无限等；超时收割本命令并记录阶段，不假定未发生副作用。stdout二进制按字节持续排空，stderr独立截取8KiB，不将截断stdout用于解析。Git管理进程也登记为Mederi进程组。

发现使用：`rev-parse --show-toplevel`、`rev-parse --path-format=absolute --git-common-dir`、`rev-parse --show-prefix`、`rev-parse --verify HEAD^{commit}`、`symbolic-ref -q HEAD`、`status --porcelain=v2 -z --untracked-files=all`、`worktree list --porcelain -z`。基线ref先验证为refs/heads候选或完整commit oid，再解析为commit；不把输入当选项，不接受`--...`。解析之后后续只用固定oid。分支列表使用for-each-ref显式format+NUL分隔，原始文件名不按空格/换行split。

命令版本/能力最低要求以实际探测为准：不支持所需NUL协议/restore/switch则返回具体unsupported，不用脆弱文本拼凑fallback。Git管理无push/pull/fetch远端，不修改用户全局Git配置。

所有路径以仓库相对路径记录；拒绝绝对、`..`组件、NUL、跨根；不要把文件名作为未经`--`隔离的选项。POSIX测试覆盖空格、换行、tab、非ASCII和以`-`开头文件名；Windows测试路径保留平台能力边界。

## G02 稳定输入与支持矩阵

捕获指纹 F：HEAD oid+symbolic branch、index字节SHA256及引用sharedindex SHA256、全部纳入操作文件的类型/模式/原始字节hash、Git状态、相关权限version；排序以原始路径字节序，不依赖显示排序。SHA256 JSON用Serializable确定性字段顺序与显式路径排序。读取文件用NOFOLLOW及lstat；前后stat/hash与第二轮状态一致，最多重试2次，仍变化报EXTERNAL_CHANGE，不静默采到混合版本。

不能取得OS全局文件写锁，不声称阻止任意外部进程；保证Mederi执行租约停止、所有已观测变化均不覆盖。每一步修改前重查预期指纹，发现外部变化保留双方快照并进入RECOVERY_REQUIRED；恢复不能盲目覆盖外部新写入。

正常支持：普通Git仓库/linked worktree、子目录项目、已有提交、常规index、staged/unstaged/untracked、二进制/删除/重命名/可执行位、tracked symlink（只保存link target，不跟随）。Git不追踪的空目录不作Git变更，但cleanup快照保留它们。

前置拒绝且不发生用户数据变更：unborn HEAD→NO_BASE_COMMIT；merge/rebase/cherry-pick/bisect进行中、unmerged index、sparse/split index、skip-worktree/assume-unchanged/intent-to-add语义无法原样保存、submodule、partial/shallow对象不足、需外部filter的LFS→UNSUPPORTED_REPOSITORY_STATE。对上述明确显示原因，不禁用普通Local对话；若实际Codex对照把某项列为必需支持，必须补实现与对应测试才能通过人工对标gate，不允许把此表当“已对标”的免责条款。只存在大文件不允许直接拒绝；逐流处理、配额失败保留原目录。特殊FIFO/socket/device不读取阻塞，拒绝cleanup并解释具体路径。

## G03 快照格式与算法

实现 `WorkspaceSnapshotService`；格式版本1。快照目录 `<snapshots>/<snapshotId>/`：`manifest.json`、`head.bundle`、`index.bin`（无index明确null）、`blobs/<sha256>`。manifest使用@Serializable：schemaVersion、snapshotId、workspaceId、checkoutRoot、projectRelativePath、gitObjectFormat、headOid、headRef?、baseCommit?、indexSha256?、entries、indexBlobs、reason、createdAt、contentFingerprint。entry包含repoRelativePath、exists:Boolean、kind(FILE/SYMLINK/DIRECTORY)、unixMode?、size、sha256?、linkTarget?、class(TRACKED/UNTRACKED/IGNORED/ARTIFACT_EXCLUDED)。indexBlob包含gitOid、gitMode、sha256，用于保活仅暂存且不在HEAD的blob。

manifest不能包含`.git`自身或指向Git目录的内容；`.git`只是管理标识，不备份为普通文件。普通文件原字节保存，不经文本解码/行尾转换；流式hash+复制。symlink用readSymbolicLink存link文本，恢复前检查是否能创建link，绝不复制target内容。拒绝恶意manifest路径穿越、重复路径、file/dir父子冲突、hash/size不匹配。

两类捕获集合：

- TRANSFER/CREATE：HEAD与index tracked路径并集（含工作树删除记录）、普通nonignored untracked，以及匹配include的ignored文件；只排除D09列出的可变运行产物路径；Git若追踪这些运行产物则preflight失败，禁止restore破坏运行数据；`.mederi/environments`不是运行产物，允许正常跟踪/转移。源ignored文件不因普通创建而全盘复制。
- PREIMAGE：managed checkout中全部普通文件/空目录/symlink（包括ignored残留），排除`.git`；LOCAL的PREIMAGE额外排除D09中原位置的可变运行产物（它们不参与操作且可能被其它副本使用），其余ignored也保留。ARCHIVE仅发生在managed，必须全量保存其残留。只有全量可验证才能删目录；不能以node_modules“像缓存”为由丢弃未知用户内容。黑名单阻止读取或文件特殊/空间不足→SNAPSHOT_INCOMPLETE，保留原目录，回收不成功。不得偷偷增加丢弃忽略文件的默认选项。

步骤：

1. 独占checkout租约并取F0；建宿主控制目录临时`<id>.partial`，禁止符号链接父目录。
2. 执行 `git -C <checkout> bundle create <partial>/head.bundle HEAD`；`bundle verify`并`bundle list-heads`确认HEAD oid。bundle不能只留对源对象库的外部前提；完整HEAD历史对象须可在独立临时裸库导入验证。Git bundle的引用与前提语义以[官方文档](https://git-scm.com/docs/git-bundle)为准。
3. 保存普通index原字节，`ls-files --stage -z`枚举stage0条目；对所有引用blob经`cat-file blob <oid>`导出原字节，记录Git oid与独立SHA256。若stage非0拒绝捕获。不能只保存index文件却忘了GC后消失的暂存blob。普通index恢复维持staged/unstaged区别，不用`git add -A`代替。
4. 按捕获集合生成entry与blobs；记录被删除tracked路径（type tombstone用manifest中存在性字段`exists=false`），按路径重查当前权限，不用受信内部I/O绕过项目显式拒绝。
5. 取F1，必须F0=F1；对每个blob、index、bundle独立读取验证。写manifest并fsync文件/目录，原子rename为正式快照目录，再记录DB已验证快照引用。DB提交失败留下可识别孤立快照待恢复，不删除原checkout。

恢复算法：新建受管detached checkout到headOid（对象若已GC先从bundle导入；不改用户分支）→ 对indexBlob用 `hash-object -w --stdin`写回并核对oid → materialize entry原字节/link/mode/删除状态 → 临时写index.bin并核对hash后原子替换目标Git实际index路径 → 对Git状态与内容manifest交叉核验。Git目录路径必须通过Git查询，不能假定checkout/.git是目录。只恢复到空的新checkout或本操作已经核验的目标，永不覆盖未知路径。

恢复tracked symlink须先建立父目录，禁止沿已存在symlink父级落盘；模式/文件类型不支持必须报错。bundle和index.blob不得被普通文件预览API暴露。恢复快照含被后来新增黑名单禁止的路径时，PATH_DENIED并保持快照；用户改规则后可重试。

## G04 创建流程

WorkspaceState REQUESTED→PREPARING；操作PENDING→RUNNING。第一条消息已由现有统一接纳逻辑持久化，不能因CREATE重试多写一次。

1. VALIDATE：发现仓库与projectRelativePath，解析baseRef为oid，核对选择/权限，确保新路径不存在；carryLocalChanges只允许基线是源当前HEAD，否则BASE_CHANGED并让用户重选，不自动把另一分支dirty patch硬套过去。
2. QUIESCE/SNAPSHOT：carry=true时按checkout锁核对影响会话，捕获TRANSFER快照；false时不复制源工作状态，不清源。
3. CREATE_CHECKOUT：`git worktree add --detach <registeredNewPath> <baseOid>`（禁hooks）。注册目录与Git记录两者必须匹配。若创建中断先查真实存在性，不重复add到同路径。
4. COPY_CONTENT：carry=true按快照materialize index/工作树，源完全不改；执行include复制。确认projectRelativePath在基线中存在且是目录，否则PROJECT_SUBDIRECTORY_MISSING，不自动回退仓库根。
5. SETUP：执行冻结选定环境脚本，规则见G08。失败可保留副本及日志重试，不调用模型。相同CREATE的自动恢复不得重复setup；不确定是否执行过时等待用户明确重试setup，不能假装幂等脚本。
6. COMMIT_BINDING：核验checkout身份/内容与准备完成；事务写Session.workspaceId/associatedWorktreeId、READY、generation+1、操作SUCCEEDED；release gate后统一消费原用户消息一次。不能在用户消息仍等待时再生成新UI发送调用。

取消在CREATE副作用前直接CANCELLED；已有副本则保留可恢复记录（不可无快照直接recursiveDelete），用户可重试或按归档算法回收。无local fallback。

## G05 Handoff 算法

preflight与执行共享同一`HandoffPlan`结构。源是当前绑定checkout；目标LOCAL是项目原检出根，目标WORKTREE是associatedWorktreeId（不存在则先准备一个，不创建第二个关联id）。managed归档则先restore；不能把被删的managed当LOCAL。

目标可以是：无独立修改的clean checkout；或与本操作已核验target后像完全一致的幂等续作目标。其它任何dirty目标（包括untracked路径碰撞）都返回TARGET_DIRTY，不自动stash/merge/清理；冲突列表给出路径与原因。Ignored包含文件若目标已存在且内容不同，不覆盖，返回具体冲突；源码路径拒绝优先。

分支规则：

- source为managed且已命名分支：taskBranch就是该分支，移动检出归属前先detach source；其它worktree占用则BRANCH_IN_USE。
- source为managed detached：以preview中显示的默认/用户名称创建taskBranch，expected old oid为全零，不能覆盖重名。
- source为LOCAL且正在使用本会话之前创建的taskBranch：转移该分支，先detach source。
- source为LOCAL且为普通原分支（如main）：保留原分支，另建taskBranch指向sourceHEAD；不把用户main锁到后台副本。
- target成功后检出taskBranch；原LOCAL分支ref不删除。源停留其原HEAD并清除本次已转移dirty内容；若为了转移分支而detach，则在UI明确显示Detached，不擅自切其它分支。

事务步骤（所有输入和期望指纹入journal）：

| 阶段 | 动作 | 崩溃/失败处理 |
|---|---|---|
| VALIDATE | 重新核preview/settings/F/分支与受影响会话集合；token过期/变化直接STALE_PREVIEW | 无文件变更，可重preview |
| QUIESCE | 取得源与目标迁移意图，stopAffected=true才停Mederi执行；所有受影响checkout租约归零、撤销旧待批；暂停自动续跑 | 停稳失败则释放意图，不迁移 |
| SNAPSHOT | 源TRANSFER快照+目标全量PREIMAGE，源完整PREIMAGE用于回滚保护；均独立验证 | 任一失败则保留原状态，不进入修改 |
| DETACH_SOURCE | 仅在转移已占用taskBranch时`switch --detach <sourceHead>`，核对HEAD/index与计划 | 记录ref归属变化；未改文件也须按journal恢复原headRef |
| SWITCH_TARGET | CAS创建必要taskBranch，然后在已核clean目标`switch <taskBranch>` | 失败只在当前匹配本操作后像时回滚；外部变化转人工恢复 |
| MATERIALIZE_TARGET | 从TRANSFER恢复工作树/index与include，保留目标独有ignored环境，碰撞不覆盖 | 从目标PREIMAGE恢复前检查无外部变化；否则RECOVERY_REQUIRED |
| VERIFY_TARGET | raw bytes/modes/HEAD/index/staged区别与源快照相同 | 未过不清源、不绑定 |
| CLEAN_SOURCE | 仅对已转移tracked路径执行`restore --source=<sourceHead> --staged --worktree --pathspec-from-file=<精确NUL清单> --pathspec-file-nul`；仅删除已转移ordinary untracked且hash仍匹配的路径；不删ignored环境/产物 | source校验不匹配时停止；target已有完整副本与两个PREIMAGE，不让新AI写；按恢复表处理 |
| COMMIT_BINDING | 源clean/目标ACCEPTED_TARGET指纹都通过，data.db原子CAS绑定目标/generation+1、保留associated id、操作SUCCEEDED | DB失败不放行新执行；恢复扫描依据真实后像幂等完成或恢复 |
| COMPLETE | 通知快照/文件树/终端；释放迁移意图，让现有消息生命周期继续 | 通知丢失由读取快照修复，不重复Git步骤 |

`git restore`是本已批准转移算法的一步，前提是源可恢复且目标核验成功；不是任意失败时的清空兜底。不得执行`reset --hard`、`clean -fd`，也不得宽泛restore整个仓库。引用改动用预期old oid的CAS，不撤回外部新提交。

重启恢复的唯一规则：若target精确等于已验证ACCEPTED_TARGET后像且source精确等于计划clean后像，完成COMMIT_BINDING一次；若尚未CLEAN_SOURCE且双方匹配本操作已记录后像，可回滚到PREIMAGE（文件/index/head归属），标FAILED；若任何一侧有未知外部变更，RECOVERY_REQUIRED、保持快照、禁止自动覆盖，UI展示“保留现状并在当前副本继续”/“恢复到新的工作副本”动作。新副本恢复不得覆盖原位置，绑定更新仍CAS。

取消：第一个Git/文件写副作用前→CANCELLED；之后→CANCELLING并在安全点恢复/完成，不能简单cancel coroutine遗留半迁移。COMMIT_BINDING已成功则取消返回SUCCEEDED（太晚，操作已完成）。所有终态通知在权威状态更新后发送。

## G06 归档、自动清理和删除

用户归档会话：先停止该会话→managed按以下算法回收→归档会话；Local只归档记录、不删目录。失败会话保持未归档并显示回收错误，不能消失。固定会话手动归档需UI明确确认解除固定；自动策略永远跳过固定。

自动回收候选：MANAGED+READY，非当前活动执行/待批/迁移，非pinned，按lastUsedAt升序/id稳定排序。计数按存在的受管checkout（包括PREPARING/RECOVERY_REQUIRED计入占用但不作候选），LOCAL不计。达到上限且无安全候选时不删保护项，允许用户选择关闭自动策略/提高限额/手动处理，再试创建；不得静默突破限额。

回收步骤：持管理意图→再次核身份/影响集合→ARCHIVE_PREIMAGE完整快照→验证→将操作写REMOVE_CHECKOUT→对**仅属本操作、已验证快照、无活跃租约且指纹未变的受管路径**执行 `git worktree remove --force <path>`（dirty允许force仅因已有完整快照，严禁 --force --force越过Git锁）→验证Git无记录且路径不存在→DB写ARCHIVED/lastSnapshotId。不是对任意路径执行递归删除。Git记录已无但目录存在、目录只删一半等任何不一致均RECOVERY_REQUIRED，不继续“补rm”。

重启REMOVE_CHECKOUT：如果目录/Git记录都不存在且快照完整，补写ARCHIVED；若两者都仍在且指纹未变可重试；其它情况停止并给出路径/日志。快照GC不在v1自动执行，空间不足明确报错，不能边清理边删唯一恢复资产。

restore复用同workspaceId；原注册路径若空可复用，否则分配同受管根下新operation子目录并在成功后原子更新checkoutRoot。用独立HEAD bundle与blob恢复，核验后READY。历史对话可在ARCHIVED时查看消息/Diff；发送先明确恢复并等待READY，不fallback。

deleteConversation/deleteProject必须先返回资源影响预览并由明确用户删除动作提交；默认保留可恢复快照记录（workspace关联以tombstone项目/会话标识保留，不级联删）。源代码Local目录永远不删。快照的永久删除另行明确用户授权，不在这套自动清理范围。

## G07 包含文件与指令文件

`.worktreeinclude`位于repo根，UTF-8，采用Git ignore语义（含注释、顺序、`!`否定、`/`锚定、`**`）；不要自行实现不完整glob。使用Git本身执行两次只读枚举后按原始路径求交集：A=`ls-files --others --ignored --exclude-standard -z`（真实ignored），B=`ls-files --others --ignored --exclude-from=<冻结的worktreeinclude文件> -z`（本次仅include规则，不加exclude-standard或exclude-per-directory）。Git的ls-files标准ignore规则不会自动启用，因此B不会把原.gitignore中的所有ignored都误当匹配。结果A∩B才复制。文件规则及其hash冻结到操作记录；未跟踪但未ignored路径不因include而复制，carry=true的普通untracked另按TRANSFER处理。语义以[git-ls-files官方文档](https://git-scm.com/docs/git-ls-files)为准。

只复制regular file，不跟随任一级symlink，目标存在一律不覆盖；不复制`.git`、控制目录、快照及D09可变运行产物。忽略的`AGENTS.override.md`无论是否在include均作为独立候选，仍受路径拒绝/无symlink/不覆盖规则约束。保存复制结果（copied/skipped及原因），失败不能伪成功；关键环境文件冲突上报，用户可以取消或调整include。

AgentsFileLoader支持每一级目录 `AGENTS.override.md`优先于同目录`AGENTS.md`，只加载其中一个；层次和懒发现沿当前工作区，黑名单过滤先于去重。这个优先级须独立测试，不能只复制却仍不读取override。

## G08 环境、动作与批准

文件固定 `<当前项目工作目录>/.mederi/environments/<id>.json`（D09区分可版本化配置与运行产物）；Serializable `ProjectEnvironment(id,name,version,setup:PlatformScript?,actions:List<EnvironmentAction>)`；PlatformScript含default/macos/linux/windows:String?、sandbox=default|network|off、timeoutSeconds默认300/上限3600；Action含id/name/icon（允许Feather键，不存在时标准Terminal图标）/script。选择平台覆盖：对应项非null优先，空字符串表示该平台不执行，不回退default；无覆盖才用default。

setup使用用户选择时冻结的脚本/配置hash。首次CREATE可执行一次；REST重试不能再执行。脚本失败、主机重启后执行结果不明则SETUP_FAILED/RECOVERY_REQUIRED，由用户按“重试初始化”产生新SETUP operation，不声称任意脚本可自动幂等。禁止管理命令隐式下载依赖；安装等均在脚本内受正常权限控制。

ACTION/SETUP复用ShellTools执行权限、命令deny规则与ProcessRegistry；批准归所属对话/operation，UI需能在工作区准备尚未启动模型时显示请求。requestApproval目前只按session取消，需把operation执行身份作为executionSessionId，既不借模型自动批准，也不借plan自动批准。settings变更撤销旧批准，配置hash变化ENVIRONMENT_CHANGED，执行之前重新选择/确认。

输出通过operation log查询与事件刷新，读取日志也需访问所属project/session。取消终止本操作进程组，不能误杀同项目其它动作。环境命令可能主动访问网络或写项目外，network/off仍申请用户一次批准，不能把环境初始化等同完全访问。

### G05补充：为Handoff首次准备目标

尚无关联副本时，在HANDOFF父操作内执行CREATE_CHECKOUT/COPY_CONTENT的空基线准备分支，不绑定会话、不消费消息、不重复运行CREATE自动setup；目标关联id在父操作开始时预留并持久化。目标准备完成后仍回到父Handoff步骤，只有最终COMMIT_BINDING才能切会话。环境若需初始化，在目标内容已转移并VERIFY_TARGET后、CLEAN_SOURCE前执行一次SETUP子操作；结果未知/失败时保留source未清状态与两个快照，父操作不得完成。

### G03/G05补充：setup后像和原位置回滚（强制）

SnapshotEntry增加 `exists:Boolean`；tombstone为false，sha256/linkTarget为null。原位置恢复与新空checkout恢复是两个方法，不共用“覆盖所有路径”的捷径。

首次Handoff目标setup若会执行：TRANSFER核验后运行，成功后捕获并验证 `ACCEPTED_TARGET` 完整目标快照，将其指纹作为后续CLEAN_SOURCE/COMMIT_BINDING和重启完成条件；不再要求setup后目标等于TRANSFER。setup改动可保留且下次交接可转移；其显式路径/命令拒绝仍由正常执行策略约束。setup失败/结果不明时源未清、会话未切，保留两个PREIMAGE与目标，父操作FAILED/RECOVERY_REQUIRED；用户重试setup成功后生成新的ACCEPTED_TARGET再继续父操作，不能重复复制和另建任务分支。没有setup时ACCEPTED_TARGET就是已验证TRANSFER后像。

回滚到既有checkout的固定顺序：

1. 对journal中所有已影响路径比较当前状态是否等于已记录postimage；任何不符或未知会影响恢复的新增路径都停止为RECOVERY_REQUIRED，不覆盖外部变化。
2. 先detach当前目标释放taskBranch；使用预期oid CAS恢复本操作改变的ref；源再恢复原headRef（或原detached），最后目标恢复原headRef。回退HEAD只改引用归属不checkout文件：Driver在common-dir管理锁下检查当前HEAD/ref与预期后，已attached先用`switch --detach <当前HEAD>`释放归属（同oid须保留当前文件/index）；恢复原attached用`symbolic-ref HEAD <原headRef>`，恢复原detached用`update-ref --no-deref HEAD <原oid> <当前oid>`。symbolic-ref前后再次核分支占用与ref期望值；外部变化立刻停止，不使用switch到旧分支强行覆盖脏文件。任何分支此时被其它checkout占用/外部推进都停止，不force。每次HEAD操作后记录实际文件/index后像，不让switch覆盖未知修改。
3. 引用归属正确后，仅删除 `createdByOperation=true`、PREIMAGE不存在且当前仍匹配postimage的文件/link；目录仅在空且由本操作创建时删除。按深→浅处理，不跟随symlink；不得宽泛删除全部untracked。然后按PREIMAGE原字节/type/mode/exists恢复文件与index。
4. 本操作新建且没人检出的taskBranch，只在ref还等于本操作newOid时CAS删除；用户原有ref从不删除。重新校验两边PREIMAGE内容/HEAD/index后才标回滚完成。故障注入覆盖每个ref/路径恢复边界。

Handoff源/目标同canonical checkout直接INVALID_REQUEST；准备阶段carry时若源有其它活动执行，返回WORKSPACE_BUSY且不自动停止。归档/恢复的幂等续作必须使用持久operationPlan，不能从当前UI字段重新猜原操作。


G07补充：用户明确选择的环境配置文件若为ignored，即使未列入`.worktreeinclude`也作为“本次选定环境”显式复制候选（仅该配置文件、同样拒绝symlink/黑名单/已有不同目标）；源配置为tracked则随正常checkout/TRANSFER处理，不另覆盖。配置路径/内容hash写入CreatePlan。目标分支已有同id不同配置必须ENVIRONMENT_CHANGED，不默默覆盖。这保证配置可入Git，也允许应用的运行产物整体忽略时使用所选环境。

G03补充：前置枚举base tree/捕获集合中的路径时，若创建/转移/快照将实际读写项目显式拒绝的逻辑路径，先返回PATH_DENIED，不运行bundle/cat-file/worktree add去绕过该拒绝。对TRACKED敏感路径，受信Git checkout也不是允许复制的后门。metadata路径枚举可用于发现拒绝，不返回内容。默认保护（.git等只读）和用户显式deny是不同规则，Driver仅按被批准管理计划操作Git元数据。

“无源仓库恢复”用例验证SnapshotService的自包含性：在独立临时Git对象库从bundle/blobs恢复内容与index。正式Manager若原项目git common directory也被外部删除，则报告WORKSPACE_MISSING/IDENTITY_CHANGED，不能未经授权把另一个新仓库冒充原项目身份；此时保留快照供明确恢复流程/导出。
