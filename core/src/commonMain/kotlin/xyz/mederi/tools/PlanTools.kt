package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.Decision
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.Plan
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.PlannedChange
import xyz.mederi.plan.SpecChange
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerificationChange
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import xyz.mederi.debug.DebugLog

private val json = Json { ignoreUnknownKeys = true }

/**
 * 宽松列表反序列化（List<String> 字段容错，实测两种失败形态）：
 * - 模型把列表写成单个字符串（`"outScope": "xxx"`）→ 包成单元素数组；
 * - 模型给"备选项"这类天然列表语义的字段传 JsonArray，而字段声明为 String → 改为 List<String> 后原样收下。
 * 映射进领域模型处统一 joinToString，领域类型不动。
 */
private object LenientStringList :
    JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

/**
 * 复合对象列表字段形状矫正（List<DecisionArg>/List<PlannedChangeArg> 容错）。
 *
 * 实测两种崩溃形态（sess_f01ec14e）：
 * - 模型把对象列表序列化成 JSON 字符串（`"keyDecisions": "[{...}]"`）→ Expected JsonArray but had JsonLiteral
 * - 模型把单个对象直接当列表（`"decisions": {...}`）→ Expected JsonArray but had JsonObject
 *
 * 做成按字段名单变换的顶层工具函数，供 args 类级别的 JsonTransformingSerializer 调用——
 * 模型错形出现在哪个字段不可预测，类级变换一次覆盖全部复合列表字段。
 */
private fun coerceObjectListField(element: JsonElement, fieldName: String): JsonElement {
    if (element !is kotlinx.serialization.json.JsonObject) return element
    val value = element[fieldName] ?: return element
    val fixed: JsonElement = when (value) {
        is JsonArray -> value
        is kotlinx.serialization.json.JsonObject -> JsonArray(listOf(value))
        is kotlinx.serialization.json.JsonPrimitive -> runCatching {
            when (val parsed = Json.parseToJsonElement(value.content)) {
                is JsonArray -> parsed
                is kotlinx.serialization.json.JsonObject -> JsonArray(listOf(parsed))
                else -> JsonArray(emptyList())
            }
        }.getOrDefault(JsonArray(emptyList()))
        else -> JsonArray(emptyList())
    }
    return kotlinx.serialization.json.JsonObject(element.toMutableMap().apply { put(fieldName, fixed) })
}

/** 复合列表字段名（SubtaskArg + CreatePlanArgs，形状矫正目标） */
private val OBJECT_LIST_FIELDS = listOf("decisions", "keyDecisions", "changes")

/** create_plan 参数整体形状矫正：先矫正子任务列表里每个 subtask，再矫正顶层字段 */
private object LenientCreatePlanArgs :
    JsonTransformingSerializer<PlanTools.CreatePlanArgs>(PlanTools.CreatePlanArgs.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        var result = element
        // subtasks[*].decisions / targetFiles 内层矫正
        val subtasks = result.subelement("subtasks")
        if (subtasks is JsonArray) {
            val fixed = JsonArray(subtasks.map { sub ->
                OBJECT_LIST_FIELDS.fold(sub) { acc, f -> coerceObjectListField(acc, f) }
            })
            result = kotlinx.serialization.json.JsonObject(
                (result as? kotlinx.serialization.json.JsonObject)?.toMutableMap()?.apply { put("subtasks", fixed) }
                    ?: mutableMapOf("subtasks" to fixed)
            )
        }
        // 顶层 keyDecisions / changes 矫正
        return OBJECT_LIST_FIELDS.fold(result) { acc, f -> coerceObjectListField(acc, f) }
    }
}

/** generate_spec/子任务级参数形状矫正 */
private object LenientSubtaskArg :
    JsonTransformingSerializer<PlanTools.SubtaskArg>(PlanTools.SubtaskArg.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        OBJECT_LIST_FIELDS.fold(element) { acc, f -> coerceObjectListField(acc, f) }
}

/** generate_spec 参数形状矫正（appendix 已删除，目前为 no-op fold，保留为扩展预留） */
private object LenientGenerateSpecArgs :
    JsonTransformingSerializer<PlanTools.GenerateSpecArgs>(PlanTools.GenerateSpecArgs.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        OBJECT_LIST_FIELDS.fold(element) { acc, f -> coerceObjectListField(acc, f) }
}

private fun JsonElement.subelement(name: String): JsonElement? =
    (this as? kotlinx.serialization.json.JsonObject)?.get(name)

class PlanTools(
    private val sessionId: String,
    private val agentMode: AgentMode,
    private val planStore: PlanStore,
    private val planApprovalRequester: PlanApprovalRequester,
    private val notebook: Notebook,
    private val eventBus: MutableSharedFlow<MederiEvent>
) {

    @Serializable
    data class DecisionArg(
        @LLMDescription("The question or uncertainty being decided.")
        val question: String = "",
        @LLMDescription("The choice that was made.")
        val choice: String = "",
        @LLMDescription("Why this choice was made.")
        val rationale: String = "",
        @LLMDescription("Other options that were considered (list them as an array).")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val alternatives: List<String> = emptyList()
    )

    @Serializable
    data class PlannedChangeArg(
        @LLMDescription("Module or component grouping label, e.g. 'core', 'cli', 'ui'. Required.")
        val module: String = "",
        @LLMDescription("Action type: MODIFY, NEW, or DELETE.")
        val action: String,
        @LLMDescription("File path to be created or modified.")
        val filePath: String,
        @LLMDescription("What will be changed.")
        val description: String,
        @LLMDescription("Why this change is needed here, and why this approach.")
        val rationale: String
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientSubtaskArg::class)
    data class SubtaskArg(
        @LLMDescription("Subtask name. Concise action-noun.")
        val name: String,
        @LLMDescription(
            "Brief intent (1-3 sentences): what this subtask achieves and why it exists. " +
                "Do NOT write the detailed implementation spec here — the detailed spec is generated " +
                "AFTER plan approval via generate_spec, grounded in the actual codebase."
        )
        val planDetail: String = "",
        @LLMDescription("Exact file paths that will be created or modified (list them as an array).")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val targetFiles: List<String> = emptyList(),
        @LLMDescription("Design decisions resolved during planning this subtask.")
        val decisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Verification: a single executable command (ASCII, assert-style). verify_subtask actually runs it; prose is rejected.")
        val verification: String,
        @LLMDescription("Optional working directory for the verification command (relative to project root). Default = project root.")
        val verificationCwd: String? = null,
        @LLMDescription("Optional timeout for the verification command, in seconds. Default = 30.")
        val verificationTimeoutSeconds: Int? = null,
        @LLMDescription(
            "REQUIRED. Expected result in plain words: what the command must output for this subtask to " +
                "count as passed (e.g. 'pytest reports 3 passed, 0 failed'). Rendered on the approval card " +
                "so the user can judge whether the verification method itself is right. Use the user's language."
        )
        val verificationExpected: String = "",
        @LLMDescription(
            "REQUIRED. Literal strings the command's output MUST contain — " +
                "MACHINE-CHECKED. Exiting 0 is not enough: if any literal here is missing from the real " +
                "output, verification is mechanically judged FAIL. Pin the actual expectation " +
                "(e.g. [\"3 passed\", \"BUILD SUCCESSFUL\"]) so a partially-passing command cannot slip through."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val verificationExpectStdoutContains: List<String> = emptyList(),
        @LLMDescription(
            "Optional. Literal strings the command's output MUST NOT contain — MACHINE-CHECKED " +
                "(e.g. [\"FAILED\", \"Traceback\"])."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val verificationExpectStdoutNotContains: List<String> = emptyList(),
        @LLMDescription("Indices of subtasks this depends on (0-based).")
        val dependsOn: List<Int> = emptyList(),
        @LLMDescription("Whether this can run in parallel with other subtasks.")
        val parallelizable: Boolean = false
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientCreatePlanArgs::class)
    data class CreatePlanArgs(
        @LLMDescription("Plan title.")
        val title: String,
        @LLMDescription(
            "ONE sentence for the approval card: what will change and to what end. " +
                "Must NOT duplicate the overview — the overview explains background and goals, " +
                "this is the single-line takeaway."
        )
        val summary: String = "",
        @LLMDescription(
            "Project context: GREENFIELD (brand-new project built from zero) or BROWNFIELD " +
                "(iterating an existing codebase). GREENFIELD plans must make the FIRST subtask " +
                "a project bootstrap: module init + dependency manifest (go.mod / package.json / ...)."
        )
        val projectContext: String = "BROWNFIELD",
        @LLMDescription("Language and toolchain with version, e.g. 'Go 1.22', 'Kotlin 2.1 + Gradle 8.10'.")
        val languageStack: String = "",
        @LLMDescription(
            "Business logic in coherent, human-readable prose: where the requirement enters " +
                "(UI action, API, command — and which function/call chain receives it), how behavior changes " +
                "(before vs after), and where the new logic hooks into the existing call chain. " +
                "Write flowing sentences that survive being read aloud once — no telegraphic fragments. "
        )
        val businessLogic: String = "",
        @LLMDescription("Goal and background: what to build and why.")
        val overview: String = "",
        @LLMDescription("In scope: what this plan will do.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val inScope: List<String> = emptyList(),
        @LLMDescription("Out of scope: what this plan will NOT do.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val outScope: List<String> = emptyList(),
        @LLMDescription(
            "Optional. Items the user should pay attention to BEFORE approving: breaking changes, " +
                "major trade-offs, risky/irreversible operations. One item per entry, in the user's language. " +
                "Prefix critical items with a GitHub alert tag ([!IMPORTANT]/[!WARNING]/[!CAUTION]). " +
                "Omit entirely when nothing needs special attention."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val userReviewRequired: List<String> = emptyList(),
        @LLMDescription(
            "Optional. Non-blocking defaults you chose WITHOUT asking the user. Each entry reads like " +
                "'Chose X because Y — if you disagree, just say so in chat.' In the user's language. " +
                "Omit when every decision was asked about, or no defaults were taken."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val openQuestions: List<String> = emptyList(),
        @LLMDescription("Key decisions: trade-offs, breaking changes, and rationale.")
        val keyDecisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Proposed changes per module with [MODIFY]/[NEW]/[DELETE] markers.")
        val changes: List<PlannedChangeArg> = emptyList(),
        @LLMDescription(
            "Optional: data sources read or written (tables, files, endpoints, formats) and new or changed " +
                "parameters with their defaults. Omit entirely if the task touches no data or parameters."
        )
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val dataAndParams: List<String> = emptyList(),
        @LLMDescription("Risks and rollback strategy.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val risks: List<String> = emptyList(),
        @LLMDescription("Success criteria: how to know the task is done. Also the verification basis.")
        @kotlinx.serialization.Serializable(with = LenientStringList::class)
        val successCriteria: List<String> = emptyList(),
        @LLMDescription("Optional: Mermaid diagram code (without fence) for architecture visualization. Mermaid is the only diagram format the UI renders; do not use PlantUML/DOT or other DSLs.")
        val architecture: String? = null,
        @LLMDescription(
            "Optional. Research conclusions from your investigation (or a researcher sub-agent's report) " +
                "that the executing sub-agents need to know — key findings, patterns, gotchas, architecture notes. " +
                "This is injected into each executor's briefing so it doesn't re-investigate the same code. " +
                "Omit when no research was done."
        )
        val researchNotes: String = "",
        @LLMDescription("Subtask list (the skeleton). Each must include name, brief intent, targetFiles, and verification. Detailed implementation specs are generated after approval via generate_spec.")
        val subtasks: List<SubtaskArg>
    )

    @KeepGeneratedSerializer
    @Serializable(with = LenientGenerateSpecArgs::class)
    data class GenerateSpecArgs(
        @LLMDescription("Plan ID from the approved plan.")
        val planId: String,
        @LLMDescription("Subtask index (0-based) to generate the spec for.")
        val subtaskIndex: Int,
        @LLMDescription(
            "The detailed implementation spec as an ORDERED CHECKLIST: numbered steps (1. 2. 3.), " +
                "each step concrete and independently verifiable (create X, modify Y, add test Z), " +
                "in execution order. Include exact function names/signatures grounded in the real code " +
                "(read the files first), data structures, edge cases, and error handling. " +
                "The executing subagent works through this checklist top-down, item by item. " +
                "The executor is a cheap model that reads files directly — do NOT transcribe file " +
                "excerpts into the spec; just reference paths and let the executor read them."
        )
        val spec: String,
        @LLMDescription(
            "REQUIRED when the subtask ALREADY has a spec — i.e. you are correcting it (typically after " +
                "verification showed the spec contradicts reality), not generating it for the first time. " +
                "State why the old spec was wrong; it is kept in the append-only spec audit trail " +
                "(the full old spec text is preserved). Omit on first generation."
        )
        val reason: String = ""
    )

    inner class CreatePlanTool : SimpleTool<CreatePlanArgs>(
        argsType = typeToken<CreatePlanArgs>(),
        name = "create_plan",
        description = "Create the implementation PLAN (the WHAT): business logic, scope, decisions, changes, " +
            "and subtask skeletons (name + brief intent + targetFiles + verification). This is what the user " +
            "approves. Do NOT write detailed implementation specs here — after approval, call generate_spec " +
            "per subtask to produce the executable spec grounded in the actual codebase. " +
            "In Approval mode, the plan is saved to a file and the user is notified via event. " +
            "In Autonomous mode, the plan is auto-approved."
    ) {
        override suspend fun execute(args: CreatePlanArgs): String {
            val error = validatePlan(args)
            if (error != null) return "Error: $error"

            // 三条硬规则之二：同一会话只有最新计划可执行——新 create_plan 作废全部非终态旧计划
            // （含执行中），作废旧计划移入 plans-voided/ 留痕。已完成的 COMPLETED 计划保留不受影响。
            val voidedIds = planStore.voidActivePlans(sessionId)
            if (voidedIds.isNotEmpty()) {
                voidedIds.forEach { oldPlanId ->
                    eventBus.emit(MederiEvent(
                        type = EventType.PLAN_PROGRESS,
                        sessionId = sessionId,
                        payload = mapOf(
                            "planId" to oldPlanId,
                            "action" to "voided"
                        ),
                        timestamp = Instant.now().toString()
                    ))
                }
                DebugLog.data("PlanTools", "voided prior non-terminal plans", "count=${voidedIds.size}, ids=$voidedIds")
            }

            val now = Instant.now().atZone(ZoneOffset.UTC)
            val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").format(now)

            val plan = Plan(
                id = "plan_${UUID.randomUUID().toString().take(8)}",
                title = args.title,
                summary = args.summary,
                projectContext = if (args.projectContext.equals("GREENFIELD", ignoreCase = true))
                    xyz.mederi.plan.ProjectContextType.GREENFIELD
                else xyz.mederi.plan.ProjectContextType.BROWNFIELD,
                languageStack = args.languageStack,
                sessionId = sessionId,
                businessLogic = args.businessLogic,
                overview = args.overview,
                inScope = args.inScope,
                outScope = args.outScope,
                userReviewRequired = args.userReviewRequired,
                openQuestions = args.openQuestions,
                keyDecisions = args.keyDecisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                changes = args.changes.map { PlannedChange(it.module.ifBlank { "core" }, it.action.uppercase(), it.filePath, it.description, it.rationale) },
                dataAndParams = args.dataAndParams,
                risks = args.risks,
                successCriteria = args.successCriteria,
                architecture = args.architecture,
                researchNotes = args.researchNotes,
                subtasks = args.subtasks.mapIndexed { i, st ->
                    Subtask(
                        index = i,
                        name = st.name,
                        planDetail = st.planDetail,
                        targetFiles = st.targetFiles,
                        decisions = st.decisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                        verification = VerificationSpec(
                            command = st.verification,
                            cwd = st.verificationCwd,
                            timeoutSeconds = st.verificationTimeoutSeconds,
                            expected = st.verificationExpected,
                            expectStdoutContains = st.verificationExpectStdoutContains,
                            expectStdoutNotContains = st.verificationExpectStdoutNotContains,
                        ),
                        dependsOn = st.dependsOn,
                        parallelizable = st.parallelizable
                    )
                },
                status = if (agentMode == AgentMode.AUTONOMOUS) PlanStatus.APPROVED else PlanStatus.PENDING_APPROVAL,
                createdAt = timestamp,
                agentMode = agentMode
            )

            planStore.save(plan)
            // save 已自愈创建 .mederi；仍拿不到路径说明项目目录异常——明确报错，
            // 绝不带着空 planPath 发批准事件（UI 会渲染成无内容的空壳卡片）
            val planPath = planStore.getPlanAbsolutePath(plan.id)
                ?: return "Error: plan was created but could not be persisted under .mederi/plans/ " +
                    "of the project directories. Check that the project's main directory is writable, " +
                    "then retry create_plan."

            val planContent = runCatching { java.io.File(planPath).readText() }.getOrDefault("")
            DebugLog.data(
                "PlanTools",
                "create_plan executed",
                "planId=${plan.id}, title='${plan.title}', summary='${plan.summary}', contentLen=${planContent.length}"
            )

            return if (agentMode == AgentMode.APPROVAL) {
                val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), plan.subtasks)
                val result = planApprovalRequester.request(
                    planId = plan.id,
                    planPath = planPath,
                    title = plan.title,
                    summary = plan.summary,
                    planContent = planContent,
                    subtaskCount = plan.subtasks.size,
                    subtasksJson = subtasksJson
                )
                if (result.superseded) {
                    "Plan superseded by a newer plan request."
                } else if (result.approved) {
                    planStore.update(plan.copy(status = PlanStatus.APPROVED))
                    notebook.append("## ${Instant.now()} — Plan approved: ${plan.title}")
                    emitPlanProgress(plan.copy(status = PlanStatus.APPROVED), "approved")
                    "Plan approved. Plan ID: ${plan.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
                } else {
                    // 计划无"拒绝"态（只有批准/作废/被无视）。此分支是用户未批准也未作废时的
                    // 兜底：不预设意图、绝不反问"为什么"。让模型回去响应用户最新消息，计划保持
                    // PENDING_APPROVAL，等用户明确批准或下个 create_plan 作废它。
                    "Plan was not approved and not voided; it remains PENDING_APPROVAL. " +
                        "Do not retry create_plan now. Respond to the user's latest message; " +
                        "do not ask why they did not approve."
                }
            } else {
                notebook.append("## ${Instant.now()} — Plan created (auto-approved): ${plan.title}")
                emitPlanProgress(plan, "created")
                "Plan created and auto-approved. Plan ID: ${plan.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
            }
        }

        /**
         * PLAN_PROGRESS 事件：驱动 UI planApprovals 投影表。
         * 四类发射点（create/spawn/verify/converge）经由本函数统一发出，不发各自手拼 payload。
         */
        private suspend fun emitPlanProgress(plan: Plan, action: String) {
            val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), plan.subtasks)
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to plan.id,
                    "action" to action,
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))
        }
    }

    @Serializable
    data class ConvergePlanArgs(
        @LLMDescription("Plan ID to converge.")
        val planId: String,
        @LLMDescription("Remediation subtasks to append. Same structure as create_plan subtasks.")
        val remediationSubtasks: List<SubtaskArg>
    )

    inner class ConvergePlanTool : SimpleTool<ConvergePlanArgs>(
        argsType = typeToken<ConvergePlanArgs>(),
        name = "converge_plan",
        description = "When verify_subtask reports PARTIAL or FAIL, append remediation subtasks to the plan. " +
            "Append-only: never rewrites or renumbers existing subtasks."
    ) {
        override suspend fun execute(args: ConvergePlanArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (args.remediationSubtasks.isEmpty())
                return "Error: Must provide at least one remediation subtask."

            val nextIndex = plan.subtasks.size
            val newSubtasks = args.remediationSubtasks.mapIndexed { i, st ->
                Subtask(
                    index = nextIndex + i,
                    name = st.name,
                    planDetail = st.planDetail,
                    targetFiles = st.targetFiles,
                    decisions = st.decisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                    verification = VerificationSpec(
                        command = st.verification,
                        cwd = st.verificationCwd,
                        timeoutSeconds = st.verificationTimeoutSeconds,
                        expected = st.verificationExpected,
                        expectStdoutContains = st.verificationExpectStdoutContains,
                        expectStdoutNotContains = st.verificationExpectStdoutNotContains,
                    ),
                    dependsOn = st.dependsOn,
                    parallelizable = st.parallelizable
                )
            }

            // 原子 RMW（AGENTS.md §5.5）：converge 与并行工具调度共存时，裸 load→copy→save 会覆盖他人写入
            val updatedPlan = planStore.updatePlan(args.planId) { p ->
                p.copy(subtasks = p.subtasks + newSubtasks)
            } ?: return "Error: Plan not found: ${args.planId}"

            val passed = updatedPlan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
            val failed = updatedPlan.subtasks.count { it.status == SubtaskStatus.FAILED }
            val pending = updatedPlan.subtasks.count { it.status == SubtaskStatus.PENDING }
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "converged",
                    "appendedCount" to args.remediationSubtasks.size.toString(),
                    "totalSubtasks" to updatedPlan.subtasks.size.toString(),
                    "passed" to passed.toString(),
                    "failed" to failed.toString(),
                    "pending" to pending.toString()
                ),
                timestamp = Instant.now().toString()
            ))

            return "Converged: ${args.remediationSubtasks.size} remediation subtasks appended " +
                "(indices $nextIndex..${nextIndex + newSubtasks.size - 1}). " +
                "Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute."
        }
    }

    @Serializable
    data class WriteLogArgs(
        @LLMDescription("Log entry content.")
        val entry: String
    )

    inner class WriteLogTool : SimpleTool<WriteLogArgs>(
        argsType = typeToken<WriteLogArgs>(),
        name = "write_log",
        description = "Append an entry to the work log (.mederi/notebook.md). Use this to record decisions, findings, and important events."
    ) {
        override suspend fun execute(args: WriteLogArgs): String {
            val timestamp = Instant.now().toString()
            val ok = notebook.append("## $timestamp — Log entry\n${args.entry}")
            return if (ok) "Logged." else "Error: no writable .mederi directory; log entry not persisted."
        }
    }

    /**
     * generate_spec：计划批准后，为单个子任务派生可执行 Spec。
     *
     * 分层语义（docs/sandbox-plan.md / AGENTS.md §5.5）：
     * - Plan（批准前）= WHAT：意图级骨架，用户审的是这个
     * - Spec（批准后）= HOW：对照真实代码写函数签名/数据结构/边界情况——
     *   此时计划已定、前序子任务已有产出，spec 天然贴地，不存在"写着写着过期"
     * - spawn_agent 只能按已存在的 Spec 执行（硬保证），发现 spec 与现实矛盾
     *   → 重新 generate_spec 修正（append-only：完整旧 spec 留存 reason 后写入 specChanges）→ 重执行
     */
    inner class GenerateSpecTool : SimpleTool<GenerateSpecArgs>(
        argsType = typeToken<GenerateSpecArgs>(),
        name = "generate_spec",
        description = "Generate the detailed implementation spec for ONE subtask of an APPROVED plan, " +
            "grounded in the actual codebase (read the real files first: function names, signatures, " +
            "data structures must match reality). Call this right before spawning an agent for the " +
            "subtask — and re-call it (with reason=) to CORRECT the spec whenever verification shows " +
            "the spec itself is wrong. Corrections are append-only: the full old spec text is preserved " +
            "in the audit trail (specChanges). The spec is stored per (planId, subtaskIndex); " +
            "subagent(SPAWN) will execute exactly this spec."
    ) {
        override suspend fun execute(args: GenerateSpecArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (plan.status != PlanStatus.APPROVED && plan.status != PlanStatus.IN_PROGRESS)
                return "Error: Plan ${plan.id} is ${plan.status}. Specs can only be generated for an approved plan."
            val st = plan.subtasks.getOrNull(args.subtaskIndex)
                ?: return "Error: Subtask index ${args.subtaskIndex} out of range (0..${plan.subtasks.size - 1})."
            if (st.status == SubtaskStatus.COMPLETED)
                return "Error: Subtask ${args.subtaskIndex} is COMPLETED. Use converge_plan to append new work instead."
            if (args.spec.isBlank())
                return "Error: spec must not be empty."
            // 修正（覆盖既有 spec）必须给 reason：严格 append-only 要求每次修正留痕，
            // 且完整旧 spec 文本会被保存（SpecChange.oldSpec），信息零销毁。
            val isCorrection = !st.spec.isNullOrBlank()
            if (isCorrection && args.reason.isBlank())
                return "Error: subtask ${args.subtaskIndex} already has a spec — you are CORRECTING it, " +
                    "not generating it. Pass reason=<why the old spec was wrong>. " +
                    "The old spec text is preserved in the append-only audit trail (specChanges)."

            // Spec 生效值写 Subtask.spec（spawn_agent 读取的真理源）；brief（planDetail）保留不动，
            // 用户批准时看到的内容永不失真。修正 = 追加一条 SpecChange（含完整旧 spec）+ 更新生效值，
            // 不是覆盖销毁（严格 append-only）。
            // 用 updatePlan 原子写入：工具支持并行调度，同消息多次 generate_spec 时
            // 裸 load→copy→save 会互相覆盖（后写把前写的 spec 恢复成旧值）。
            val updated = planStore.updatePlan(args.planId) { p ->
                p.copy(
                    subtasks = p.subtasks.map { s ->
                        if (s.index == args.subtaskIndex) s.copy(
                            spec = args.spec,
                            specChanges = s.specChanges + SpecChange(
                                oldSpec = s.spec,
                                newSpec = args.spec,
                                reason = args.reason,
                                timestamp = Instant.now().toString()
                            )
                        ) else s
                    }
                )
            } ?: return "Error: Plan not found: ${args.planId}"
            notebook.append(
                "## ${Instant.now()} — Spec generated: Subtask ${args.subtaskIndex} (${st.name})\n"
            )
            val subtasksJson = json.encodeToString(ListSerializer(Subtask.serializer()), updated.subtasks)
            eventBus.emit(xyz.mederi.domain.model.MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "spec-generated",
                    "subtaskIndex" to args.subtaskIndex.toString(),
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))
            return "Spec saved for Subtask ${args.subtaskIndex} (${st.name}). " +
                "Spawn an agent with planId=${args.planId}, subtaskIndex=${args.subtaskIndex} to execute it."
        }
    }

    /**
     * 计划校验（聚合报错）：一次列出全部问题，模型一轮补齐所有缺失项。
     * 逐条报错的台阶实测爬不完（弱模型 6+ 轮都到不了底），聚合后一轮收敛。
     */
    private fun validatePlan(args: CreatePlanArgs): String? {
        val errors = mutableListOf<String>()
        if (args.subtasks.isEmpty())
            errors.add("Plan must contain at least one subtask.")
        if (args.overview.isBlank())
            errors.add("Overview is required: background and goals (1-2 sentences).")
        // Summary 与 Overview 复读拦截：卡片摘要是一句话结论，Overview 是背景与目标，语义不同
        if (args.summary.isNotBlank() && args.summary.trim() == args.overview.trim())
            errors.add(
                "Summary must not duplicate the Overview. Summary = ONE sentence for the approval " +
                    "card (what will change); Overview = background and goals (1-2 paragraphs)."
            )
        val isGreenfield = args.projectContext.equals("GREENFIELD", ignoreCase = true)
        // 二值不变量校验：只检查"有没有"，不限制"写多少"（篇幅交给提示词约束）
        if (args.businessLogic.isBlank())
            errors.add(
                "Business logic is required: entry point, before/after behavior, " +
                    "and where the new logic hooks into the call chain."
            )
        if (args.inScope.isEmpty())
            errors.add("In Scope is required: what will this plan do?")
        if (args.keyDecisions.isEmpty())
            errors.add(
                "Key Decisions is required: surface your assumptions and choices explicitly " +
                    "(storage location, formats, algorithms, libraries) instead of burying them in prose."
            )
        if (args.changes.isEmpty())
            errors.add(
                "Changes is required: list every file with [MODIFY]/[NEW]/[DELETE] markers. " +
                    "Every [NEW] file must justify why an existing file cannot be modified instead."
            )
        if (args.successCriteria.isEmpty())
            errors.add("Success criteria is required: how do we know the whole task is done?")
        if (args.languageStack.isBlank())
            errors.add("Language stack is required, e.g. 'Go 1.22', 'Kotlin 2.1 + Gradle 8.10'.")
        for (c in args.changes) {
            if (!c.action.uppercase().matches(Regex("MODIFY|NEW|DELETE")))
                errors.add(
                    "Change ${c.filePath}: action must be MODIFY, NEW, or DELETE (got \"${c.action}\"). " +
                        "Example: {module: \"core\", action: \"NEW\", filePath: \"main.go\", description: \"...\", rationale: \"...\"}."
                )
            if (c.action.equals("NEW", ignoreCase = true) && c.rationale.isBlank())
                errors.add("Change ${c.filePath}: a [NEW] file must justify why an existing file cannot be modified instead.")
        }
        args.subtasks.forEachIndexed { i, st ->
            if (st.verification.isBlank())
                errors.add("Subtask $i (${st.name}): verification is required.")
            val hasCJK = st.verification.any { it.code in 0x4E00..0x9FFF }
            if (hasCJK)
                errors.add(
                    "Subtask $i (${st.name}): verification 必须是单条可执行命令（ASCII），不能是散文描述。" +
                        "示例：`python3 -c 'assert 1+1==2'`。"
                )
            if (st.verificationExpected.isBlank())
                errors.add(
                    "Subtask $i (${st.name}): verificationExpected is required — state in plain words what the " +
                        "command must output for this subtask to count as passed (e.g. '3 passed, 0 failed'). " +
                        "It is rendered on the approval card so the user can judge whether the verification " +
                        "method itself is right."
                )
            if (st.targetFiles.isEmpty())
                errors.add("Subtask $i (${st.name}): targetFiles required (which files will be changed?).")
            // 规则 1：每个子任务的验证必须钉至少 1 个期望 stdout 字面量（裸 exit-code 验证抓不到"活没干成"）
            checkLiteralRequired(st)?.let { errors.add("Subtask $i (${st.name}): $it") }
            for (dep in st.dependsOn) {
                if (dep >= args.subtasks.size || dep < 0)
                    errors.add("Subtask $i (${st.name}): dependsOn $dep out of range (0..${args.subtasks.size - 1}).")
            }
        }
        if (isGreenfield) {
            // Greenfield 第一个子任务必须做项目骨架：否则后续子任务产出的代码根本无法编译
            args.subtasks.firstOrNull()?.let { first ->
                val bootstrapHint = first.name + " " + first.planDetail
                val isBootstrap = listOf("bootstrap", "init", "scaffold", "go.mod", "package.json",
                    "build.gradle", "cargo.toml", "pyproject", "module", "skeleton", "骨架", "初始化", "脚手架")
                    .any { bootstrapHint.contains(it, ignoreCase = true) }
                if (!isBootstrap)
                    errors.add(
                        "This plan is GREENFIELD: the FIRST subtask must be project bootstrap — " +
                            "module init + dependency manifest (go.mod / package.json / build.gradle.kts / ...), " +
                            "so later subtasks can build. Name it accordingly (e.g. 'Project bootstrap: go mod init + deps')."
                    )
            }
        }
        // 规则 4：两个 parallelizable 子任务若共享 gradle 模块，其验证编译会竞争——同批并行 spawn 直接拒绝
        errors.addAll(checkParallelizableModuleOverlap(args.subtasks))
        if (errors.isEmpty()) return null
        return "Plan validation failed — fix ALL of the following items, then retry create_plan once with the complete arguments:\n" +
            errors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n")
    }
    @Serializable
    data class UpdateVerificationArgs(
        @LLMDescription("Plan ID.")
        val planId: String,
        @LLMDescription("Subtask index (0-based) to amend.")
        val subtaskIndex: Int,
        @LLMDescription("New verification command: single executable command (ASCII, assert-style). Prose is rejected.")
        val command: String = "",
        @LLMDescription("Optional: working directory for the verification command (relative to project root).")
        val cwd: String? = null,
        @LLMDescription("Optional: timeout seconds. Default 30.")
        val timeoutSeconds: Int? = null,
        @LLMDescription("Optional. New expected result in plain words. Omit to KEEP the existing one.")
        val expected: String? = null,
        @LLMDescription("Optional. New machine-checked literals the output MUST contain. Omit to KEEP the existing list.")
        val expectStdoutContains: List<String>? = null,
        @LLMDescription("Optional. New machine-checked literals the output MUST NOT contain. Omit to KEEP the existing list.")
        val expectStdoutNotContains: List<String>? = null,
        @LLMDescription("Required: why the verification contract is being changed (e.g. 'old command counted comments as false-positives'). Human-readable reason for the append-only audit trail.")
        val reason: String = "",
    )

    inner class UpdateVerificationTool : SimpleTool<UpdateVerificationArgs>(
        argsType = typeToken<UpdateVerificationArgs>(),
        name = "update_verification",
        description = "Amend an existing subtask's verification CONTRACT (command/cwd/timeout/expected/machine-checked " +
            "literals) atomically via PlanStore.updatePlan. Allowed for non-COMPLETED subtasks " +
            "(PENDING/IN_PROGRESS/FAILED). Use when the verification method itself was written wrong and needs " +
            "fixing before re-verify. Append-only: the FULL old contract is preserved in the audit trail " +
            "(verificationChanges) — amendments never destroy history."
    ) {
        override suspend fun execute(args: UpdateVerificationArgs): String {
            if (args.command.isBlank()) return "Error: command must not be blank."
            val hasCJK = args.command.any { it.code in 0x4E00..0x9FFF }
            if (hasCJK) return "Error: command 必须是单条可执行命令（ASCII），不能是散文描述。"
            if (args.reason.isBlank()) return "Error: reason must not be blank — the audit trail requires a human-readable explanation of why the contract is being changed."
            var oldSpec: VerificationSpec? = null
            var downgradeRejected = false
            val updated = planStore.updatePlan(args.planId) { p ->
                val st = p.subtasks.getOrNull(args.subtaskIndex) ?: return@updatePlan null
                if (st.status == SubtaskStatus.COMPLETED) return@updatePlan null
                oldSpec = st.verification
                // null = 保留原值：避免"只想改命令"却把既有预期静默清空（数据零销毁原则）
                val newSpec = VerificationSpec(
                    command = args.command,
                    cwd = args.cwd,
                    timeoutSeconds = args.timeoutSeconds,
                    expected = args.expected ?: st.verification.expected,
                    expectStdoutContains = args.expectStdoutContains ?: st.verification.expectStdoutContains,
                    expectStdoutNotContains = args.expectStdoutNotContains ?: st.verification.expectStdoutNotContains,
                )
                // 规则 3 降级检测（引用的是修订前旧契约 st.verification）：
                // 丢命令段 / 减字面量 且 reason 无决策变更关键词 → 拒写（append-only 审计不受影响）
                if (isVerificationDowngrade(st.verification, newSpec) && !reasonIndicatesDecisionChange(args.reason)) {
                    downgradeRejected = true
                    return@updatePlan null
                }
                val change = VerificationChange(
                    oldSpec = st.verification,
                    newSpec = newSpec,
                    reason = args.reason,
                    timestamp = java.time.Instant.now().toString()
                )
                p.copy(subtasks = p.subtasks.map { s ->
                    if (s.index == args.subtaskIndex) s.copy(
                        verification = newSpec,
                        verificationChanges = s.verificationChanges + change
                    ) else s
                })
            }
            if (downgradeRejected)
                return "Error: verification DOWNGRADE rejected — the new contract drops a command clause " +
                    "(./gradlew/assert/test) or reduces expectStdoutContains count without a decision-change " +
                    "justification. Either re-run the original verification when the module is stable " +
                    "(do NOT drop checks to clear a false-fail), or fully restate the contract for a genuinely " +
                    "changed task disposition (and state that in reason with a 'decision/disposition/restated/" +
                    "changed' keyword)."
            if (updated == null)
                return "Error: Plan not found, or subtask ${args.subtaskIndex} missing/COMPLETED (cannot amend)."
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to "verification-updated",
                    "subtaskIndex" to args.subtaskIndex.toString(),
                    "oldCommand" to (oldSpec?.command ?: ""),
                    "newCommand" to args.command,
                    "reason" to args.reason
                ),
                timestamp = java.time.Instant.now().toString()
            ))
            return "Updated verification for subtask ${args.subtaskIndex}.\n" +
                "Old command: ${oldSpec?.command ?: ""}\n" +
                "New command: ${args.command}\n" +
                "Expected: ${updated.subtasks[args.subtaskIndex].verification.expected}\n" +
                "Reason: ${args.reason}\n" +
                "Change #${updated.subtasks[args.subtaskIndex].verificationChanges.size} recorded in plan audit trail (full old contract preserved)."
        }
    }

    companion object {
        /** create_plan 工具名（TurnExecutor 据此刻意写或找到挂起的计划工具调用）。 */
        const val PLAN_TOOL = "create_plan"

        /** 规则 1：每个子任务的验证契约必须钉至少 1 个期望 stdout 字面量（返错误文本，null = 通过）。
         *  调用方（validatePlan）负责补 "Subtask $i (${st.name}): " 前缀。 */
        internal fun checkLiteralRequired(st: SubtaskArg): String? =
            if (st.verificationExpectStdoutContains.isEmpty())
                "verificationExpectStdoutContains is REQUIRED — pin >=1 literal the output MUST contain " +
                    "(e.g. [\"PASS\",\"BUILD SUCCESSFUL\"]). Bare exit-code/compile-only verification is rejected: " +
                    "it cannot catch 'the work was not done' (a glitched executor that compiles would false-pass)."
            else null

        /** 文件路径 → gradle 模块名（如 core/src/... → core，src/ 前缀缺失时取第一级目录）。 */
        private fun moduleOf(path: String): String =
            path.substringBefore("/src/").ifBlank { path.substringBefore('/') }

        /** 规则 4：两个 parallelizable 子任务共享 gradle 模块时，同批并行 spawn 的验证编译会竞争——逐对报错。 */
        internal fun checkParallelizableModuleOverlap(subtasks: List<SubtaskArg>): List<String> {
            val errors = mutableListOf<String>()
            val parallelGroups = subtasks.mapIndexed { i, st -> i to st }.filter { it.second.parallelizable }
            for ((i, a) in parallelGroups) for ((j, b) in parallelGroups) {
                if (j <= i) continue
                val aMods = a.targetFiles.map(::moduleOf).toSet()
                val bMods = b.targetFiles.map(::moduleOf).toSet()
                val shared = aMods.intersect(bMods)
                if (shared.isNotEmpty())
                    errors.add(
                        "Subtasks $i (${a.name}) and $j (${b.name}) are both parallelizable but share gradle module(s) " +
                            "$shared — their verification compiles of that module will race. " +
                            "Set parallelizable=false on one, or split into separate batches."
                    )
            }
            return errors
        }

        /** 规则 3：新验证契约是否较旧契约"降级"——丢命令段（./gradlew/assert/test）或字面量数量减少。 */
        internal fun isVerificationDowngrade(old: VerificationSpec, new: VerificationSpec): Boolean {
            val droppedClause = (old.command.contains("./gradlew") && !new.command.contains("./gradlew")) ||
                (old.command.contains("assert") && !new.command.contains("assert")) ||
                (old.command.contains(" test ") && !new.command.contains(" test "))
            val fewerLiterals = new.expectStdoutContains.size < old.expectStdoutContains.size
            return droppedClause || fewerLiterals
        }

        /** 规则 3 放行词：reason 是否含决策变更关键词（decision/disposition/restated/changed，大小写不敏感）。 */
        internal fun reasonIndicatesDecisionChange(reason: String): Boolean =
            reason.contains(Regex("decision|disposition|restated|changed", RegexOption.IGNORE_CASE))

        /**
         * 用户在有计划待批准时直接继续对话的中性结果语料（非批准、非拒绝、非作废）。
         *
         * 这是三条硬规则之一"用户直接回复 ≠ 拒绝"的落库文本：TurnExecutor 在用户继续对话时
         * 把这条作为 create_plan 的 ToolResult 写入历史，让下一轮 AI 视图无悬空 tool call，
         * 并明确"计划仍 PENDING_APPROVAL、用户只是继续了对话"——绝不引导模型追问为什么没批准。
         */
        const val USER_REPLIED_NEUTRAL =
            "User did not approve or reject the plan; they continued the conversation. " +
            "The plan stays PENDING_APPROVAL — respond to their message, do not ask why."
    }

}