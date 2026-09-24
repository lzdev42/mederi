package xyz.mederi.plan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import xyz.mederi.domain.model.AgentMode

@Serializable
enum class PlanStatus { PENDING_APPROVAL, APPROVED, IN_PROGRESS, COMPLETED, VOIDED }

@Serializable
enum class SubtaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

@Serializable
enum class VerifyStatus { PASS, PARTIAL, FAIL }

/**
 * 验证失败的根因轴（2026-09-24 重构，替代旧现象轴 MISSING/PARTIAL/CONTRADICTS/UNREQUESTED）。
 *
 * 判定顺序是硬性的：**先核实现、实现无误再核计划**（见 PromptGuides 的验证纪律）。
 * - [IMPLEMENTATION]：spec 说得清楚，执行没做到 → 追加补救子任务（converge_plan）后重执行
 * - [PLAN]：实现照 spec 做到了，但计划本身的逻辑/验证方法/预期站不住 → 追加修订
 */
@Serializable
enum class RootCause { IMPLEMENTATION, PLAN }

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
 *
 * [evidence] 是模型的人工解读（它核对了什么、发现什么）；[commandOutput]/[commandExitCode]
 * 是**机器即写即真**的真实执行证据，两者分开存——审计时"到底跑了什么、输出是什么"
 * 不经过模型转述（与 walkthrough 的装配哲学一致）。
 *
 * [machineMismatch] 为 true 表示机器判定与模型判定矛盾（机器证明预期已达成、模型仍坚持
 * 未通过，或反之）。这是异常态：必须由主代理如实告知用户（见 VerifyTools 返回文本）。
 */
@Serializable
data class VerificationResult(
    val status: VerifyStatus,
    val evidence: String,
    val rootCause: RootCause? = null,
    val remediation: String? = null,
    /** 验证命令真实输出（截断存储，审计用；未经模型转述）。 */
    val commandOutput: String? = null,
    /** 验证命令真实退出码；超时未退出时为 null。 */
    val commandExitCode: Int? = null,
    /** 机器判定与模型判定是否矛盾（异常态，需告知用户）。 */
    val machineMismatch: Boolean = false
)

/**
 * 验证契约变更记录（审计留痕，严格 append-only）。
 * 每次 update_verification 修正时追加一条，**保存完整的旧/新契约**（含预期字面量），
 * 信息零销毁——生效值 = Subtask.verification（最新），历史全在记录里。
 */
@Serializable
data class VerificationChange(
    val oldSpec: VerificationSpec,
    val newSpec: VerificationSpec,
    val reason: String,
    val timestamp: String
)

/**
 * Spec 变更记录（审计留痕，严格 append-only，2026-09-24）。
 *
 * spec 允许被修正（verify 发现 spec 与现实矛盾 → 重新 generate_spec），但修正不是
 * 覆盖销毁：每次修正追加一条记录并保存**完整旧 spec 文本**，生效值 = Subtask.spec（最新）。
 * [reason] 首次生成为空；覆盖既有 spec 时必填（修正需留痕）。
 */
@Serializable
data class SpecChange(
    val oldSpec: String?,
    val newSpec: String,
    val reason: String = "",
    val timestamp: String
)

/**
 * 子任务的验证契约（2026-09-24 扩展：从"一个命令"升级为"命令 + 可机器校验的预期"）。
 *
 * 分层设计：
 * - [command]：单一可执行命令（ASCII，assert-style）——机器执行的主判据（exit 0 = 通过）
 * - [expectStdoutContains] / [expectStdoutNotContains]：**机器硬校验**的字面量。
 *   命令 exit 0 但输出缺失预期字面量 → 机器直接判 FAIL。这抓的是"命令通过但计划预期没达到"
 *   （例：`pytest` 只跑通 1 条也 exit 0，计划预期 3 条）。
 * - [expected]：人类可读的预期结果描述（"命令跑出什么算通过"）——机器无法通用解析的部分，
 *   由模型对照输出判断；**批准时对用户可见**，让用户在批准阶段就能审"验证方法对不对"。
 */
@Serializable(with = VerificationSpecSerializer::class)
data class VerificationSpec(
    val command: String,
    val cwd: String? = null,
    val timeoutSeconds: Int? = null,
    val expected: String = "",
    val expectStdoutContains: List<String> = emptyList(),
    val expectStdoutNotContains: List<String> = emptyList(),
)

/**
 * VerificationSpec 自定义序列化器（兼容旧 String 与新 Object 两种 JSON 形态）。
 *
 * - 序列化：始终输出 Object；新增字段（expected / expectStdout*）仅在非空时输出。
 * - 反序列化：遇 JsonPrimitive（纯字符串）→ VerificationSpec(command=string)；
 *   遇 JsonObject → 正常解析各字段（新增字段缺失时取默认值，旧 JSON 兼容）。
 *
 * 依赖 JsonDecoder/JsonEncoder——PlanStore 用 Json 格式存储，兼容。
 */
object VerificationSpecSerializer : KSerializer<VerificationSpec> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("VerificationSpec") {
        element<String>("command")
        element<String?>("cwd", isOptional = true)
        element<Int?>("timeoutSeconds", isOptional = true)
        element<String>("expected", isOptional = true)
        element<List<String>>("expectStdoutContains", isOptional = true)
        element<List<String>>("expectStdoutNotContains", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: VerificationSpec) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("VerificationSpec requires Json format")
        val obj = buildJsonObject {
            put("command", value.command)
            value.cwd?.let { put("cwd", it) }
            value.timeoutSeconds?.let { put("timeoutSeconds", it) }
            if (value.expected.isNotBlank()) put("expected", value.expected)
            if (value.expectStdoutContains.isNotEmpty()) {
                put("expectStdoutContains", buildJsonArray { value.expectStdoutContains.forEach { add(it) } })
            }
            if (value.expectStdoutNotContains.isNotEmpty()) {
                put("expectStdoutNotContains", buildJsonArray { value.expectStdoutNotContains.forEach { add(it) } })
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    /** 从 JsonObject 读一个字符串数组字段（缺失/类型不符 → 空列表）。 */
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    override fun deserialize(decoder: Decoder): VerificationSpec {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("VerificationSpec requires Json format")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> VerificationSpec(command = element.content)
            is JsonObject -> VerificationSpec(
                command = element["command"]?.jsonPrimitive?.contentOrNull ?: "",
                cwd = element["cwd"]?.jsonPrimitive?.contentOrNull,
                timeoutSeconds = element["timeoutSeconds"]?.jsonPrimitive?.intOrNull,
                expected = element["expected"]?.jsonPrimitive?.contentOrNull ?: "",
                expectStdoutContains = element.stringList("expectStdoutContains"),
                expectStdoutNotContains = element.stringList("expectStdoutNotContains"),
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
 * 与 brief 分离——spec 可被修正重写，用户批准过的 brief 永不失真；
 * spawn_agent 以 [spec] 非空为派工前提。
 * [specChanges] 由 generate_spec 回写——严格 append-only：每次修正追加一条记录并保存
 * **完整旧 spec 文本**，信息零销毁；生效值 = [spec]（最新）。
 * [targetFiles] 在 CODE 模式必填，WORK 模式不用。
 * [verification] 结构化验证契约（命令 + 字面量预期 + 人类可读预期），替代旧无结构 String。
 * [executorTouchedFiles] 由 executor 回写实际改动的文件列表（供 verify 对照 targetFiles）。
 * [executorProgress] 由 executor 回写进度摘要（供主代理轮询活跃子任务状态）。
 * [verificationResult] 由 verify_subtask 工具回写（含机器真实输出，未经模型转述）。
 * [verificationChanges] 由 update_verification 工具回写——严格 append-only：每次修正追加一条
 * 变更记录并保存**完整旧/新契约**，人类可事后查证。
 */
@Serializable
data class Subtask(
    val index: Int,
    val name: String,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val planDetail: String,
    val spec: String? = null,
    val specChanges: List<SpecChange> = emptyList(),
    val targetFiles: List<String> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val verification: VerificationSpec,
    val verificationResult: VerificationResult? = null,
    val verificationChanges: List<VerificationChange> = emptyList(),
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
    /**
     * 需要用户拍板/注意的点（破坏性变更、重大取舍、高风险操作）——审批卡片置顶渲染，
     * 是用户做知情决策的第一落点。条目可带 GitHub alert 标签（[!WARNING]/[!CAUTION]）。
     * 可选：无特殊注意点时为空。
     */
    val userReviewRequired: List<String> = emptyList(),
    /**
     * 非阻塞默认决策（Open Questions）：AI 未询问用户而采用的默认选择，
     * 语义 = "选了 X，因为 Y——不同意直接在对话里说即可"。审批卡片置顶渲染，
     * 给用户低成本否决点。可选：无默认决策时为空。
     */
    val openQuestions: List<String> = emptyList(),
    val keyDecisions: List<Decision> = emptyList(),
    val changes: List<PlannedChange> = emptyList(),
    val dataAndParams: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val architecture: String? = null,
    /**
     * 计划级调研结论（来自主 agent 或 researcher 的调研）——主 agent 备忘用，存 plan.json。
     * 不再注入 executor briefing（避免每个 executor 重复收全文）；executor 需要调研结论时
     * read_file .mederi/plans/{planId}/research.md（researcher 报告落盘文件）。
     * 可选：无调研结论时为空。
     */
    val researchNotes: String = "",
    val subtasks: List<Subtask>,
    val status: PlanStatus = PlanStatus.PENDING_APPROVAL,
    val createdAt: String,
    val agentMode: AgentMode
) {
    /** 是否处于终态：已归档（COMPLETED）或已作废（VOIDED）。终态计划不再是被"活跃"的候选。 */
    val isTerminal: Boolean get() = status == PlanStatus.COMPLETED || status == PlanStatus.VOIDED

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
