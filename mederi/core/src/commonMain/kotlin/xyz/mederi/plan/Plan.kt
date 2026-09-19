package xyz.mederi.plan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
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
 * 子任务的验证规范：可执行命令 + 可选工作目录 + 可选超时秒数。
 * 替代旧 verification: String（无结构散文被当命令盲执行）。
 * 自定义 KSerializer 兼容旧 JSON：遇纯 String → VerificationSpec(command=string, cwd=null, timeout=null)。
 */
@Serializable(with = VerificationSpecSerializer::class)
data class VerificationSpec(
    val command: String,
    val cwd: String? = null,
    val timeoutSeconds: Int? = null,
)

/**
 * VerificationSpec 自定义序列化器（兼容旧 String 与新 Object 两种 JSON 形态）。
 *
 * - 序列化：始终输出 Object（{"command":"...","cwd":"...","timeoutSeconds":30}）。
 * - 反序列化：遇 JsonPrimitive（纯字符串）→ VerificationSpec(command=string)；
 *   遇 JsonObject → 正常解析各字段。
 *
 * 依赖 JsonDecoder/JsonEncoder——PlanStore 用 Json 格式存储，兼容。
 */
object VerificationSpecSerializer : KSerializer<VerificationSpec> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("VerificationSpec") {
        element<String>("command")
        element<String?>("cwd", isOptional = true)
        element<Int?>("timeoutSeconds", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: VerificationSpec) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("VerificationSpec requires Json format")
        val obj = buildJsonObject {
            put("command", value.command)
            value.cwd?.let { put("cwd", it) }
            value.timeoutSeconds?.let { put("timeoutSeconds", it) }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): VerificationSpec {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("VerificationSpec requires Json format")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> VerificationSpec(command = element.content)
            is JsonObject -> VerificationSpec(
                command = element["command"]?.jsonPrimitive?.contentOrNull ?: "",
                cwd = element["cwd"]?.jsonPrimitive?.contentOrNull,
                timeoutSeconds = element["timeoutSeconds"]?.jsonPrimitive?.intOrNull,
            )
            else -> throw SerializationException("VerificationSpec must be a JSON string or object")
        }
    }
}

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
 * [verification] 结构化验证规范（命令 + 可选 cwd + 可选超时），替代旧无结构 String。
 * [executorTouchedFiles] 由 executor 回写实际改动的文件列表（供 verify 对照 targetFiles）。
 * [executorProgress] 由 executor 回写进度摘要（供主代理轮询活跃子任务状态）。
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
    val verification: VerificationSpec,
    val verificationResult: VerificationResult? = null,
    val executorTouchedFiles: List<String> = emptyList(),
    val executorProgress: String? = null,
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
 * [architecture] 可选，存放 Mermaid 代码块（InkCompose 唯一可渲染的图表格式，其他语法只降级为源码展示）。
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
