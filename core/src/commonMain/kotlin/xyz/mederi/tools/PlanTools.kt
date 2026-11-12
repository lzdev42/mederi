package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.AcceptanceCase
import xyz.mederi.plan.Decision
import xyz.mederi.plan.encodeSubtasksWire
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

/**
 * write_plan 参数完整 JSON 结构（全字段 + 空值/类型说明，无示例数据）。
 *
 * 挂在 write_plan 工具描述里防「模型不知道格式」（AGENTS.md §5 不兜底原则：解码层不做
 * 错形矫正，错形由 kotlinx 原生 SerializationException 报错回喂模型自纠；能在源头减少
 * 错形的唯一位置是工具描述）。字段较多、嵌套较深，给一份字段穷尽的结构让模型照抄形状。
 */
internal val WRITE_PLAN_ARGS_STRUCTURE: String = """
{
  "planId": "",
  "title": "",
  "summary": "",
  "projectContext": "",
  "languageStack": "",
  "businessLogic": "",
  "background": "",
  "problemAnalysis": "",
  "approach": "",
  "expectedOutcome": "",
  "testMethod": "",
  "inScope": [],
  "outScope": [],
  "openQuestions": [],
  "keyDecisions": [
    {"question": "", "choice": "", "rationale": "", "alternatives": []}
  ],
  "changes": [
    {"module": "", "action": "", "filePath": "", "description": "", "rationale": ""}
  ],
  "dataAndParams": [],
  "risks": [],
  "successCriteria": [],
  "architecture": null,
  "researchNotes": "",
  "appendix": null,
  "subtasks": [
    {
      "name": "",
      "planDetail": "",
      "targetFiles": [],
      "decisions": [
        {"question": "", "choice": "", "rationale": "", "alternatives": []}
      ],
      "verification": "",
      "verificationCwd": null,
      "verificationTimeoutSeconds": null,
      "verificationExpected": "",
      "verificationExpectStdoutContains": [],
      "verificationExpectStdoutNotContains": [],
      "dependsOn": [],
      "parallelizable": false,
      "appendix": null,
      "acceptanceCases": [
        {"id": "", "objective": "", "setup": "", "input": "", "steps": "", "observationSource": "", "expected": "", "detects": "", "manual": false}
      ],
      "acceptanceFiles": [],
      "overallAcceptance": false
    }
  ]
}
""".trimIndent()

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
        val alternatives: List<String> = emptyList()
    )

    @Serializable
    data class PlannedChangeArg(
        @LLMDescription("Module or component grouping label, e.g. 'core', 'cli', 'ui'. Required.")
        val module: String = "",
        @LLMDescription("Action type: MODIFY, NEW, or DELETE. Required.")
        val action: String = "",
        @LLMDescription("File path to be created or modified. Required.")
        val filePath: String = "",
        @LLMDescription("What specifically changes in this file — not just 'Add X' but 'Add X that does Y, called from Z'. For MODIFY: what exists now and what it becomes.")
        val description: String = "",
        @LLMDescription("Why this change is needed here, and why this approach. Required.")
        val rationale: String = ""
    )

    @Serializable
    data class SubtaskArg(
        @LLMDescription("Subtask name (concise action-noun). Required.")
        val name: String = "",
        @LLMDescription(
            "Brief: what this subtask achieves, the approach (how), and which files are touched. " +
                "The user reviews this to understand HOW the subtask will be done before approving. " +
                "Do NOT write the detailed implementation spec here — that is generated AFTER approval " +
                "via generate_spec."
        )
        val planDetail: String = "",
        @LLMDescription("Exact file paths that will be created or modified (list them as an array).")
        val targetFiles: List<String> = emptyList(),
        @LLMDescription("Design decisions resolved during planning this subtask.")
        val decisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Verification: a single executable command (ASCII, assert-style). verify_subtask actually runs it; prose is rejected.")
        val verification: String = "",
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
                "(e.g. [\"3 passed\", \"BEHAVIOR_CHECK_PASS\"]) so a partially-passing command cannot slip through."
        )
        val verificationExpectStdoutContains: List<String> = emptyList(),
        @LLMDescription(
            "Optional. Literal strings the command's output MUST NOT contain — MACHINE-CHECKED " +
                "(e.g. [\"FAILED\", \"Traceback\"])."
        )
        val verificationExpectStdoutNotContains: List<String> = emptyList(),
        @LLMDescription("Indices of subtasks this depends on (0-based).")
        val dependsOn: List<Int> = emptyList(),
        @LLMDescription("Whether this can run in parallel with other subtasks.")
        val parallelizable: Boolean = false,
        @LLMDescription("Optional. Subtask-level technical appendix for reviewers/controllers: current logic, code evidence, root cause, fix preview (pseudo-code or real code), business logic. High information density — no full source dumps, only key code lines + core contradiction + key pseudocode. Leave empty for simple subtasks (new files, mechanical changes). This is the argument version (why), distinct from spec (how).")
        val appendix: String? = null,
        @LLMDescription("Structured acceptance cases. Collector outputs one JSON object with scalar actual values keyed by case ID. Include setup, concrete input, actual entry point, expected value, evidence source and defect detected.")
        val acceptanceCases: List<AcceptanceCase> = emptyList(),
        @LLMDescription("Existing project-relative collector, assertion and fixture files read by the verification command. Inline-only collectors can leave this empty.")
        val acceptanceFiles: List<String> = emptyList(),
        @LLMDescription("True for the final task validating the overall outcome through its actual entry point. It depends on every implementation task.")
        val overallAcceptance: Boolean = false,
    )

    @Serializable
    data class WritePlanArgs(
        @LLMDescription("Optional. Empty = create a new plan. Non-empty = update an existing PENDING_APPROVAL plan in place — only provide fields you want to change, blank/empty fields keep their existing values.")
        val planId: String = "",
        @LLMDescription("Plan title. Required for new plans.")
        val title: String = "",
        @LLMDescription(
            "ONE sentence for the approval card: what will change and to what end. " +
                "Must NOT duplicate the Goal/Approach content; this is the single-line takeaway for the approval card."
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
            "Optional. Business logic: where the requirement enters (UI/API/command — which " +
                "function/call chain receives it), how behavior changes (before vs after), where new " +
                "logic hooks into the call chain. If included, also weave the key business logic into " +
                "the approach field so the user sees it at a glance."
        )
        val businessLogic: String = "",
        @LLMDescription("Optional. Background context; omit when the task has no distinct background. Empty fields are not rendered in plan.md.")
        val background: String = "",
        @LLMDescription("Optional. What is wrong or what is needed; omit when redundant with the Goal narrative.")
        val problemAnalysis: String = "",
        @LLMDescription("Required. Approach: how you will fix/implement it, including the business logic (rules, data flow, key algorithms). Not line-level implementation — the 'how it works' level.")
        val approach: String = "",
        @LLMDescription("Optional. What will be different after; omit when folded into the Goal.")
        val expectedOutcome: String = "",
        @LLMDescription("Optional. Verification approach; rendered as '验证计划（Verification Plan）'. Omit for tasks with no verification needs.")
        val testMethod: String = "",
        @LLMDescription("Optional. What this plan will do; omit when scope is obvious from the Goal.")
        val inScope: List<String> = emptyList(),
        @LLMDescription("Out of scope: what this plan will NOT do.")
        val outScope: List<String> = emptyList(),
        @LLMDescription("Legacy compatibility field. Leave empty: resolve all substantive decisions with the user via ask_user before writing a plan.")
        val openQuestions: List<String> = emptyList(),
        @LLMDescription("Key decisions: trade-offs, breaking changes, and rationale.")
        val keyDecisions: List<DecisionArg> = emptyList(),
        @LLMDescription("Proposed changes per module with [MODIFY]/[NEW]/[DELETE] markers. For each file, describe what specifically changes — not just the file name.")
        val changes: List<PlannedChangeArg> = emptyList(),
        @LLMDescription(
            "Optional: data sources read or written (tables, files, endpoints, formats) and new or changed " +
                "parameters with their defaults. Omit entirely if the task touches no data or parameters."
        )
        val dataAndParams: List<String> = emptyList(),
        @LLMDescription("Risks and rollback strategy.")
        val risks: List<String> = emptyList(),
        @LLMDescription("Optional. How to know the whole task is done; completion criteria is primarily carried by Approach + per-subtask verification contracts.")
        val successCriteria: List<String> = emptyList(),
        @LLMDescription("Optional: Mermaid diagram code (without fence) for architecture visualization. Mermaid is the only diagram format the UI renders; do not use PlantUML/DOT or other DSLs.")
        val architecture: String? = null,
        @LLMDescription(
            "Optional. Research conclusions from your investigation (or a researcher sub-agent's report) " +
                "— key findings, patterns, gotchas, architecture notes. Kept in plan.json as the parent's " +
                "memory aid. NOT injected into executor briefings — if executors need this info, put it " +
                "in the spec. Omit when no research was done."
        )
        val researchNotes: String = "",
        @LLMDescription("Optional. Plan-level technical context: cross-subtask shared background, plan-level key decisions/changes/data. Omit when there is no cross-subtask content.")
        val appendix: String? = null,
        @LLMDescription("Subtask list (the skeleton). Required for new plans; in update mode keep empty — subtasks cannot be edited in place, submit a new draft instead. Each subtask must include name, brief intent, targetFiles, and verification. Detailed implementation specs are generated after approval via generate_spec.")
        val subtasks: List<SubtaskArg> = emptyList()
    )

    @Serializable
    data class GenerateSpecArgs(
        @LLMDescription("Plan ID from the approved plan.")
        val planId: String,
        @LLMDescription("Subtask index (0-based) to generate the spec for.")
        val subtaskIndex: Int,
        @LLMDescription(
            "The detailed implementation spec as an ORDERED CHECKLIST: numbered steps (1. 2. 3.), " +
                "each step concrete and independently verifiable, in execution order. The executor " +
                "has NO project context and does NOT guess — the spec is its sole source of " +
                "implementation requirements. Follow the Spec Writing Guide: state modification " +
                "scope (files, anchors), per-file steps (types, signatures, wiring), applicable build/check " +
                "command, execution boundary, and all behavior decisions (branches, edge cases, " +
                "error handling). Translate project rules into concrete actions. Do NOT leave " +
                "decisions as 'handle reasonably' or 'follow existing patterns'. Specs are " +
                "implementation only — do NOT include test-running steps. Supply an applicable compile/build " +
                "command, or state why compilation is inapplicable and give a non-test validation check."
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

    inner class WritePlanTool : SimpleTool<WritePlanArgs>(
        argsType = typeToken<WritePlanArgs>(),
        name = ToolName.WRITE_PLAN.value,
        description = "Software-development workflow only; not for browser, research, office, or document tasks, " +
            "even when temporary scripts are used. Resolve substantive decisions via ask_user first; " +
            "leave openQuestions empty. Create or update a PENDING_APPROVAL plan. Without planId: " +
            "creates a new plan (voids prior active plans in this session). With planId: updates the existing " +
            "plan in place — only provide fields you want to change, blank/empty fields keep their existing " +
            "values. Only a legacy PENDING_APPROVAL draft can be edited in place. Structured drafts use a " +
            "fresh ID for each revision. Replacing an approved active plan requires explicit user approval. " +
            "Autonomous mode auto-approves initial plans. " +
            "Arguments format: " + WRITE_PLAN_ARGS_STRUCTURE
    ) {
        override suspend fun execute(args: WritePlanArgs): String {
            if (args.planId.isBlank()) validatePlan(args)?.let { return "Error: $it" }
            val acceptanceError = validateAcceptance(args.subtasks)
            if (acceptanceError != null) return "Error: $acceptanceError"
            val pinnedFiles = try {
                args.subtasks.map { st -> planStore.pinAcceptanceFiles(st.acceptanceFiles) }
            } catch (failure: IllegalArgumentException) {
                return "Error: ${failure.message}"
            } catch (failure: java.io.IOException) {
                return "Error: could not read acceptance files: ${failure.message}"
            }
            // ===== UPDATE MODE =====
            if (args.planId.isNotBlank()) {
                val existing = planStore.load(args.planId)
                    ?: return "Error: Plan not found: ${args.planId}"
                if (existing.sessionId != sessionId || existing.status != PlanStatus.PENDING_APPROVAL)
                    return "Error: Cannot update plan — status is ${existing.status}. " +
                        "Only PENDING_APPROVAL plans can be updated. " +
                        "APPROVED plans: use converge_plan to append subtasks. " +
                        "Spec corrections: use generate_spec with reason= (append-only)."

                if (existing.subtasks.any { it.acceptanceCases.isNotEmpty() } || args.subtasks.isNotEmpty())
                    return "Error: submit a new draft with a new plan ID so the approval refers to the exact version."
                if (args.changes.isNotEmpty()) {
                    val changeErrors = validateChanges(args.changes)
                    if (changeErrors.isNotEmpty())
                        return "Error: Plan validation failed — fix ALL of the following items, then retry write_plan once with the complete arguments:\n" +
                            changeErrors.mapIndexed { i, e -> "${i + 1}. $e" }.joinToString("\n")
                }

                // 应用非空字段，blank/empty = 保持原值
                val updated = existing.copy(
                    title = args.title.takeIf { it.isNotBlank() } ?: existing.title,
                    summary = args.summary.takeIf { it.isNotBlank() } ?: existing.summary,
                    projectContext = if (args.projectContext.isNotBlank() && args.projectContext.equals("GREENFIELD", ignoreCase = true))
                        xyz.mederi.plan.ProjectContextType.GREENFIELD else existing.projectContext,
                    languageStack = args.languageStack.takeIf { it.isNotBlank() } ?: existing.languageStack,
                    businessLogic = args.businessLogic.takeIf { it.isNotBlank() } ?: existing.businessLogic,
                    background = args.background.takeIf { it.isNotBlank() } ?: existing.background,
                    problemAnalysis = args.problemAnalysis.takeIf { it.isNotBlank() } ?: existing.problemAnalysis,
                    approach = args.approach.takeIf { it.isNotBlank() } ?: existing.approach,
                    expectedOutcome = args.expectedOutcome.takeIf { it.isNotBlank() } ?: existing.expectedOutcome,
                    testMethod = args.testMethod.takeIf { it.isNotBlank() } ?: existing.testMethod,
                    inScope = args.inScope.takeIf { it.isNotEmpty() } ?: existing.inScope,
                    outScope = args.outScope.takeIf { it.isNotEmpty() } ?: existing.outScope,
                    openQuestions = args.openQuestions.takeIf { it.isNotEmpty() } ?: existing.openQuestions,
                    keyDecisions = args.keyDecisions.takeIf { it.isNotEmpty() }?.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) } ?: existing.keyDecisions,
                    changes = args.changes.takeIf { it.isNotEmpty() }?.map { PlannedChange(it.module.ifBlank { "core" }, it.action.uppercase(), it.filePath, it.description, it.rationale) } ?: existing.changes,
                    dataAndParams = args.dataAndParams.takeIf { it.isNotEmpty() } ?: existing.dataAndParams,
                    risks = args.risks.takeIf { it.isNotEmpty() } ?: existing.risks,
                    successCriteria = args.successCriteria.takeIf { it.isNotEmpty() } ?: existing.successCriteria,
                    architecture = args.architecture ?: existing.architecture,
                    researchNotes = args.researchNotes.takeIf { it.isNotBlank() } ?: existing.researchNotes,
                    appendix = args.appendix ?: existing.appendix
                )

                planStore.save(updated)

                val planPath = planStore.getPlanAbsolutePath(updated.id)
                    ?: return "Error: plan was updated but plan.md could not be read."
                val subtasksJson = encodeSubtasksWire(updated.subtasks)

                return if (updated.status == PlanStatus.PENDING_APPROVAL) {
                    val result = planApprovalRequester.request(
                        planId = updated.id,
                        planPath = planPath,
                        title = updated.title,
                        summary = updated.summary,
                        subtaskCount = updated.subtasks.size,
                        subtasksJson = subtasksJson
                    )
                    if (result.superseded) "Plan superseded by a newer plan request."
                    else if (result.approved) {
                        planStore.update(updated.copy(status = PlanStatus.APPROVED))
                        notebook.append("## ${Instant.now()} — Plan approved: ${updated.title}")
                        emitPlanProgress(updated.copy(status = PlanStatus.APPROVED), "approved")
                        "Plan updated and approved. Plan ID: ${updated.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
                    } else {
                        "Plan updated; remains PENDING_APPROVAL. Respond to the user's latest message."
                    }
                } else {
                    "Plan updated. Plan ID: ${updated.id}."
                }
            }

            // ===== CREATE MODE（现有逻辑，原封不动）=====
            // 三条硬规则之二：同一会话只有最新计划可执行——新 write_plan 作废全部非终态旧计划
            // （含执行中），作废旧计划移入 plans-voided/ 留痕。已完成的 COMPLETED 计划保留不受影响。
            val previous = planStore.loadBySession(sessionId)
            val replacesPlanId = previous?.takeIf { it.status != PlanStatus.PENDING_APPROVAL || it.replacesPlanId != null }?.id
            val needsUserApproval = agentMode == AgentMode.APPROVAL || replacesPlanId != null
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
                background = args.background,
                problemAnalysis = args.problemAnalysis,
                approach = args.approach,
                expectedOutcome = args.expectedOutcome,
                testMethod = args.testMethod,
                inScope = args.inScope,
                outScope = args.outScope,
                openQuestions = args.openQuestions,
                keyDecisions = args.keyDecisions.map { Decision(it.question, it.choice, it.rationale, it.alternatives.joinToString("; ")) },
                changes = args.changes.map { PlannedChange(it.module.ifBlank { "core" }, it.action.uppercase(), it.filePath, it.description, it.rationale) },
                dataAndParams = args.dataAndParams,
                risks = args.risks,
                successCriteria = args.successCriteria,
                architecture = args.architecture,
                researchNotes = args.researchNotes,
                appendix = args.appendix,
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
                        parallelizable = st.parallelizable,
                        appendix = st.appendix,
                        acceptanceCases = st.acceptanceCases,
                        acceptanceFiles = pinnedFiles[i],
                        overallAcceptance = st.overallAcceptance
                    )
                },
                status = if (needsUserApproval) PlanStatus.PENDING_APPROVAL else PlanStatus.APPROVED,
                createdAt = timestamp,
                agentMode = agentMode,
                replacesPlanId = replacesPlanId
            )

            planStore.save(plan)
            // save 已自愈创建 .mederi；仍拿不到路径说明项目目录异常——明确报错，
            // 绝不带着空 planPath 发批准事件（UI 会渲染成无内容的空壳卡片）
            val planPath = planStore.getPlanAbsolutePath(plan.id)
                ?: return "Error: plan was created but could not be persisted under .mederi/plans/ " +
                    "of the project directories. Check that the project's main directory is writable, " +
                    "then retry write_plan."

            val planContent = planStore.planMarkdown(plan)
            DebugLog.data(
                "PlanTools",
                "write_plan executed",
                "planId=${plan.id}, title='${plan.title}', summary='${plan.summary}', contentLen=${planContent.length}"
            )

            return if (needsUserApproval) {
                val subtasksJson = encodeSubtasksWire(plan.subtasks)
                val result = planApprovalRequester.request(
                    planId = plan.id,
                    planPath = planPath,
                    title = plan.title,
                    summary = plan.summary,
                    subtaskCount = plan.subtasks.size,
                    subtasksJson = subtasksJson
                )
                if (result.superseded) {
                    "Plan superseded by a newer plan request."
                } else if (result.approved) {
                    val approvedPlan = planStore.updatePlan(plan.id) { current ->
                        if (current != plan || current.subtasks.any { !planStore.acceptanceFilesMatch(it) }) null
                        else current.copy(status = PlanStatus.APPROVED)
                    }
                    if (approvedPlan == null) return "Error: the draft or its verification files changed before approval. Submit a fresh draft."
                    notebook.append("## ${Instant.now()} — Plan approved: ${plan.title}")
                    emitPlanProgress(approvedPlan, "approved")
                    "Plan approved. Plan ID: ${plan.id}. Use subagent(SPAWN, planId=..., subtaskIndex=...) to execute subtasks."
                } else {
                    // 计划无"拒绝"态（只有批准/作废/被无视）。此分支是用户未批准也未作废时的
                    // 兜底：不预设意图、绝不反问"为什么"。让模型回去响应用户最新消息，计划保持
                    // PENDING_APPROVAL，等用户明确批准或下个 write_plan 作废它。
                    "Plan was not approved and not voided; it remains PENDING_APPROVAL. " +
                        "Do not retry write_plan now. Respond to the user's latest message; " +
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
            val subtasksJson = encodeSubtasksWire(plan.subtasks)
            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to plan.id,
                    "action" to action,
                    "subtasks" to subtasksJson
                ) + if (action == "created") mapOf(
                    "title" to plan.title,
                    "summary" to plan.summary,
                    "planPath" to planStore.getPlanAbsolutePath(plan.id).orEmpty()
                ) else emptyMap(),
                timestamp = Instant.now().toString()
            ))
        }
    }

    @Serializable
    data class ConvergePlanArgs(
        @LLMDescription("Plan ID to converge.")
        val planId: String,
        @LLMDescription("Remediation subtasks to append. Same structure as write_plan subtasks.")
        val remediationSubtasks: List<SubtaskArg>
    )

    inner class ConvergePlanTool : SimpleTool<ConvergePlanArgs>(
        argsType = typeToken<ConvergePlanArgs>(),
        name = ToolName.CONVERGE_PLAN.value,
        description = "When verify_subtask reports PARTIAL or FAIL, append remediation subtasks to the plan. " +
            "Append-only: never rewrites or renumbers existing subtasks."
    ) {
        override suspend fun execute(args: ConvergePlanArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (plan.sessionId != sessionId || plan.subtasks.any { it.acceptanceCases.isNotEmpty() })
                return "Error: approved scope is fixed. Repair and retry with the same cases, or submit a replacement for approval."
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
                    parallelizable = st.parallelizable,
                    appendix = st.appendix
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
        name = ToolName.WRITE_LOG.value,
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
        name = ToolName.GENERATE_SPEC.value,
        description = "Generate the detailed implementation spec for ONE subtask of an APPROVED plan, " +
            "grounded in the actual codebase (read the real files first: function names, signatures, " +
            "data structures must match reality). Call this before executing a subtask, directly or via an " +
            "optional subagent — and re-call it (with reason=) to CORRECT the spec whenever verification shows " +
            "the spec itself is wrong. Corrections are append-only: the full old spec text is preserved " +
            "in the audit trail (specChanges). The spec is stored per (planId, subtaskIndex); " +
            "subagent(SPAWN) will execute exactly this spec. The executor does NOT guess — the spec is " +
            "its sole source of implementation requirements. Include an applicable build/compile check, " +
            "or explain why compilation is inapplicable and specify a non-test check. " +
            "Do NOT include test-running steps; you test via verify_subtask."
    ) {
        override suspend fun execute(args: GenerateSpecArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            if (plan.sessionId != sessionId || (plan.status != PlanStatus.APPROVED && plan.status != PlanStatus.IN_PROGRESS))
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
                val current = p.subtasks.getOrNull(args.subtaskIndex) ?: return@updatePlan null
                if (p.sessionId != sessionId || p.isTerminal || p.status == PlanStatus.PENDING_APPROVAL ||
                    current.status == SubtaskStatus.COMPLETED || (current.spec != st.spec && args.reason.isBlank()))
                    return@updatePlan null
                p.copy(
                    status = if (p.status == PlanStatus.APPROVED) PlanStatus.IN_PROGRESS else p.status,
                    subtasks = p.subtasks.map { s ->
                        if (s.index == args.subtaskIndex) s.copy(
                            status = if (s.status == SubtaskStatus.PENDING) SubtaskStatus.IN_PROGRESS else s.status,
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
            val subtasksJson = encodeSubtasksWire(updated.subtasks)
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
                "Execute it directly or optionally delegate with planId=${args.planId}, " +
                "subtaskIndex=${args.subtaskIndex}; choose based on total cost and context value. Then verify_subtask."
        }
    }

    /**
     * 计划校验（聚合报错）：一次列出全部问题，模型一轮补齐所有缺失项。
     * 逐条报错的台阶实测爬不完（弱模型 6+ 轮都到不了底），聚合后一轮收敛。
     */
    private fun validateAcceptance(subtasks: List<SubtaskArg>): String? {
        if (subtasks.isEmpty()) return null // Partial updates may keep the existing task list.
        if (subtasks.any { it.acceptanceCases.isEmpty() || it.acceptanceCases.any { case -> !case.isValid() } })
            return "Every task needs concrete acceptance cases with valid IDs, setup, input, steps, observation source, expected value and defect detected."
        if (subtasks.any { task -> task.acceptanceCases.any { it.manual } &&
                (task.acceptanceCases.any { !it.manual } || task.verification.isNotBlank() || task.acceptanceFiles.isNotEmpty()) })
            return "Put manual cases in a dedicated task with no collector command or collector files."
        val cases = subtasks.flatMap { it.acceptanceCases }
        if (cases.map { it.id }.distinct().size != cases.size) return "Acceptance case IDs must be unique in the plan."
        val overall = subtasks.withIndex().filter { it.value.overallAcceptance }
        if (overall.size != 1 || overall.single().index != subtasks.lastIndex ||
            overall.single().value.dependsOn.toSet() != (0 until subtasks.lastIndex).toSet())
            return "The final task must be the only overall acceptance task and depend on every preceding task."
        return null
    }

    /**
     * changes 逐项校验（create 与 update 两个路径共用）。
     *
     * 与 DTO 全字段默认值化配套：字段缺漏不再在解码期逐个炸（kotlinx
     * "Field 'rationale' is required ... $.3" 只报第一个，模型需要逐轮猜补——2026-11-09
     * 实测一个 plan 里 7/18 个 change 缺 rationale），而是到这里一次性全部点名
     * （validatePlan 的「fix ALL + retry once」设计）。
     * 校验与原解码期必填语义严格对齐：缺失与空白同等报错（DTO 默认值化后两者不可区分），不留静默通道。
     */
    private fun validateChanges(changes: List<PlannedChangeArg>): List<String> {
        val errors = mutableListOf<String>()
        changes.forEachIndexed { i, c ->
            val label = "Change $i (${c.filePath.ifBlank { "<filePath missing>" }})"
            if (!c.action.uppercase().matches(Regex("MODIFY|NEW|DELETE")))
                errors.add(
                    "$label: action must be MODIFY, NEW, or DELETE (got \"${c.action}\"). " +
                        "Example: {module: \"core\", action: \"NEW\", filePath: \"main.go\", description: \"...\", rationale: \"...\"}."
                )
            if (c.filePath.isBlank())
                errors.add("$label: filePath is required — which file does this change touch?")
            if (c.description.isBlank())
                errors.add("$label: description is required — what specifically changes in this file, not just the file name.")
            if (c.rationale.isBlank())
                errors.add(
                    "$label: rationale is required — why this change is needed here and why this approach." +
                        if (c.action.equals("NEW", ignoreCase = true)) " A [NEW] file must justify why an existing file cannot be modified instead." else ""
                )
        }
        return errors
    }

    private fun validatePlan(args: WritePlanArgs): String? {
        val errors = mutableListOf<String>()
        if (args.subtasks.isEmpty())
            errors.add("Plan must contain at least one subtask.")
        if (args.approach.isBlank())
            errors.add("Approach is required: how you will fix/implement it, including business logic.")
        val isGreenfield = args.projectContext.equals("GREENFIELD", ignoreCase = true)
        // 二值不变量校验：只检查"有没有"，不限制"写多少"（篇幅交给提示词约束）
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
        if (args.languageStack.isBlank())
            errors.add("Language stack is required, e.g. 'Go 1.22', 'Kotlin 2.1 + Gradle 8.10'.")
        errors.addAll(validateChanges(args.changes))
        if (args.title.isBlank())
            errors.add("Title is required — name what will change and to what end.")
        args.subtasks.forEachIndexed { i, st ->
            if (st.name.isBlank())
                errors.add("Subtask $i: name is required (concise action-noun).")
            if (st.verification.isBlank() && st.acceptanceCases.none { it.manual })
                errors.add("Subtask $i (${st.name}): verification is required.")
            val hasCJK = st.verification.any { it.code in 0x4E00..0x9FFF }
            if (hasCJK)
                errors.add(
                    "Subtask $i (${st.name}): verification 必须是单条可执行命令（ASCII），不能是散文描述。" +
                        "示例：`python3 -c 'assert 1+1==2'`。"
                )
            if (st.verificationExpected.isBlank() && st.acceptanceCases.isEmpty())
                errors.add(
                    "Subtask $i (${st.name}): verificationExpected is required — state in plain words what the " +
                        "command must output for this subtask to count as passed (e.g. '3 passed, 0 failed'). " +
                        "It is rendered on the approval card so the user can judge whether the verification " +
                        "method itself is right."
                )
            if (st.targetFiles.isEmpty() && !st.overallAcceptance && st.acceptanceCases.none { it.manual })
                errors.add("Subtask $i (${st.name}): targetFiles required (which files will be changed?).")
            // 规则 1：每个子任务的验证必须钉至少 1 个期望 stdout 字面量（裸 exit-code 验证抓不到"活没干成"）
            if (st.acceptanceCases.isEmpty()) checkLiteralRequired(st)?.let { errors.add("Subtask $i (${st.name}): $it") }
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
        return "Plan validation failed — fix ALL of the following items, then retry write_plan once with the complete arguments:\n" +
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
        name = ToolName.UPDATE_VERIFY.value,
        description = "Amend a legacy unapproved draft's verification contract. Approved plans and structured " +
            "drafts require a replacement through write_plan. The full legacy contract history is retained."
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
                if (p.sessionId != sessionId || p.status != PlanStatus.PENDING_APPROVAL ||
                    st.acceptanceCases.isNotEmpty() || st.status == SubtaskStatus.COMPLETED) return@updatePlan null
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
                return "Error: Plan not found, or subtask ${args.subtaskIndex} missing/COMPLETED or locked (submit a new plan revision for approval)."
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
        /** write_plan 工具名（TurnExecutor 据此刻意写或找到挂起的计划工具调用）。 */
        val PLAN_TOOL = ToolName.WRITE_PLAN.value

        /** 规则 1：每个子任务的验证契约必须钉至少 1 个期望 stdout 字面量（返错误文本，null = 通过）。
         *  调用方（validatePlan）负责补 "Subtask $i (${st.name}): " 前缀。 */
        internal fun checkLiteralRequired(st: SubtaskArg): String? =
            if (st.verificationExpectStdoutContains.isEmpty())
                "verificationExpectStdoutContains is REQUIRED — pin >=1 literal the output MUST contain " +
                    "(e.g. [\"BEHAVIOR_CHECK_PASS\"]). Use behavior assertions for behavior changes; a compile-success " +
                    "literal alone is not evidence of changed behavior. An empty literal list is rejected: " +
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
         * 把这条作为 write_plan 的 ToolResult 写入历史，让下一轮 AI 视图无悬空 tool call，
         * 并明确"计划仍 PENDING_APPROVAL、用户只是继续了对话"——绝不引导模型追问为什么没批准。
         */
        const val USER_REPLIED_NEUTRAL =
            "User did not approve or reject the plan; they continued the conversation. " +
            "The plan stays PENDING_APPROVAL — respond to their message, do not ask why."
    }

}
