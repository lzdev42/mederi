package xyz.mederi.plan

import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.WorkType

@Serializable
enum class PlanStatus { PENDING_APPROVAL, APPROVED, IN_PROGRESS, COMPLETED }

@Serializable
enum class SubtaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

@Serializable
enum class VerifyStatus { PASS, PARTIAL, FAIL }

@Serializable
enum class GapType { MISSING, PARTIAL, CONTRADICTS, UNREQUESTED }

/**
 * 决策记录：问题 → 选择 → 理由 → 备选方案。
 */
@Serializable
data class Decision(
    val question: String,
    val choice: String,
    val rationale: String,
    val alternatives: String
)

/**
 * 结构化验证结果。替代旧 verificationEvidence: String?。
 */
@Serializable
data class VerificationResult(
    val status: VerifyStatus,
    val evidence: String,
    val gapType: GapType? = null,
    val remediation: String? = null
)

/**
 * 计划改动项。对应 Implementation Plan 模板的 §4 详细改动方案。
 */
@Serializable
data class PlannedChange(
    val module: String,
    val action: String,      // MODIFY / NEW / DELETE
    val filePath: String,
    val description: String,
    val rationale: String
)

/**
 * 子任务。
 *
 * [planDetail] 是批准时用户看到的 brief intent（1-3 句：这个子任务做什么、为什么存在），恒定不变。
 * [spec] 是批准后 generate_spec 派生的执行清单（编号步骤 + 真实代码里的函数签名），
 * 与 brief 分离——spec 可反复覆盖重写，用户批准过的 brief 永不失真；
 * spawn_agent 以 [spec] 非空为派工前提。
 * [targetFiles] 在 CODE 模式必填，WORK 模式不用。
 * [verificationResult] 由 verify_subtask 工具回写。
 */
@Serializable
data class Subtask(
    val index: Int,
    val name: String,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val planDetail: String,
    val spec: String? = null,
    val targetFiles: List<String> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val verification: String,
    val verificationResult: VerificationResult? = null,
    val dependsOn: List<Int> = emptyList(),
    val parallelizable: Boolean = false
)

/**
 * 项目上下文：计划是新建项目还是迭代现有工程。
 * [GREENFIELD] 从零新建——第一个子任务必须是项目骨架初始化（模块声明 + 依赖清单）。
 * [BROWNFIELD] 基于现有工程迭代——计划须评估对现有调用链的影响与兼容性。
 */
@Serializable
enum class ProjectContextType { GREENFIELD, BROWNFIELD }

/**
 * 执行计划。
 *
 * 包含两部分：
 * - Part 1（人读）：[businessLogic]、[overview]、[inScope]、[outScope]、[keyDecisions]、[changes]、
 *   [dataAndParams]、[risks]、[successCriteria]、[architecture]
 * - Part 2（子任务）：每个 [Subtask] 含批准时可见的 [Subtask.planDetail]（brief）与
 *   批准后派生的 [Subtask.spec]（执行清单）
 *
 * [businessLogic] 是连贯散文描述的业务逻辑（入口 → 调用链 → 前后行为对比），
 * 人审 Plan 时验证"业务逻辑是否正确"的第一落点；WORK 模式留空。
 * [dataAndParams] 可选：读写的数据源 + 新增/变更的参数及默认值；不涉及数据/参数的任务为空。
 *
 * [sessionId] 将 plan 与对话记录关联，TurnExecutor 按 sessionId 加载活跃计划。
 * [architecture] 可选，存放 Mermaid/PlantUML 代码块，UI 用 InkCompose 渲染。
 */
@Serializable
data class Plan(
    val id: String,
    val title: String,
    val summary: String = "",
    val sessionId: String,
    val projectContext: ProjectContextType = ProjectContextType.BROWNFIELD,
    val languageStack: String = "",
    val businessLogic: String = "",
    val overview: String,
    val inScope: List<String> = emptyList(),
    val outScope: List<String> = emptyList(),
    val keyDecisions: List<Decision> = emptyList(),
    val changes: List<PlannedChange> = emptyList(),
    val dataAndParams: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val architecture: String? = null,
    val subtasks: List<Subtask>,
    val status: PlanStatus = PlanStatus.PENDING_APPROVAL,
    val createdAt: String,
    val agentMode: AgentMode,
    val workType: WorkType
) {
    val isAllCompleted: Boolean get() =
        subtasks.isNotEmpty() &&
        subtasks.all { it.status == SubtaskStatus.COMPLETED } &&
        subtasks.none { it.status == SubtaskStatus.FAILED }

    val needsConvergence: Boolean get() =
        subtasks.any { it.status == SubtaskStatus.FAILED } &&
        subtasks.none { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.IN_PROGRESS }

    val currentSubtask: Subtask? get() = subtasks.firstOrNull { it.status == SubtaskStatus.IN_PROGRESS }
    val nextPending: Subtask? get() = subtasks.firstOrNull { it.status == SubtaskStatus.PENDING }
}
