package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.AcceptanceRun
import xyz.mederi.plan.PlanStatus
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.RootCause
import xyz.mederi.plan.Subtask
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerifyStatus
import xyz.mederi.plan.VerificationResult
import xyz.mederi.plan.VerificationSpec
import xyz.mederi.tools.ShellTools.CommandResult
import java.time.Instant

/** 自动验证命令的三态判定结果：exit 0→PASS；超时→TIMEOUT；非零非超时→FAIL。 */
internal enum class VerifyCommandOutcome { PASS, FAIL, TIMEOUT }

/** 三态判定：exit 0→PASS；超时→TIMEOUT；非零非超时→FAIL。 */
internal fun verifyCommandOutcome(exitCode: Int, timedOut: Boolean): VerifyCommandOutcome = when {
    timedOut -> VerifyCommandOutcome.TIMEOUT
    exitCode == 0 -> VerifyCommandOutcome.PASS
    else -> VerifyCommandOutcome.FAIL
}

/** 字面量预期核对结果（机器硬校验）。[ok] = 无缺失的必须项、也无意外出现的禁止项。 */
internal data class LiteralCheckResult(
    val missingContains: List<String>,
    val hitNotContains: List<String>
) {
    val ok: Boolean get() = missingContains.isEmpty() && hitNotContains.isEmpty()
}

/**
 * 字面量预期核对（机器硬校验，2026-09-24）。
 *
 * 抓的是"命令 exit 0 但计划预期没达到"——例：`pytest` 只跑通 1 条也 exit 0，
 * 但计划写明必须输出 "3 passed"，此时必须判 FAIL，不能靠模型自觉。
 */
internal fun checkOutputLiterals(
    output: String,
    mustContain: List<String>,
    mustNotContain: List<String>
): LiteralCheckResult = LiteralCheckResult(
    missingContains = mustContain.filterNot { output.contains(it) },
    hitNotContains = mustNotContain.filter { output.contains(it) }
)

/**
 * 规则 2（空产出门禁，2026-10）：executor 报零改动且子任务目标含真实文件 → 拒绝 PASS。
 *
 * 防 S15 事故：执行器 glitch 空报告、零改动、但验证命令（编译）仍然过——机器会放行，
 * 形成"假绿"。这里在机器门之前直接拒：没有产出物就不可能实现子任务。
 *
 * 豁免：targetFiles 全是目录路径（endsWith('/')）或 targetFiles 为空——
 * 这些是门禁/验证型子任务，不产出文件。
 */
internal fun shouldRejectEmptyOutputPass(subtask: Subtask): Boolean =
    subtask.executorTouchedFiles.isEmpty() &&
        subtask.targetFiles.any { !it.endsWith('/') && it.contains('.') }

/** 规则 2 拒绝消息：直接逼回 FAIL(IMPLEMENTATION) + converge_plan。 */
internal fun emptyOutputRejectionMessage(subtask: Subtask): String {
    val realFiles = subtask.targetFiles.filter { !it.endsWith('/') && it.contains('.') }
    return "Error: cannot declare PASS — the executor reported NO touched files " +
        "but this is an implementation subtask (targetFiles include real files: ${realFiles.joinToString(", ")}). " +
        "This is the signature of a glitched/empty-report executor that did nothing. " +
        "Re-examine: if the executor truly produced no changes, declare FAIL with rootCause=IMPLEMENTATION " +
        "and call converge_plan to re-run. " +
        "(Gate/verification-only subtasks with directory or empty targetFiles are exempt.)"
}

/**
 * 验证工具。主代理在子任务执行完成后调用 verify_subtask。
 *
 * **机器硬校验（2026-09-24 改造）**：只要子任务存了 verification 命令，**无条件**执行它
 * （不再只在模型声明 PASS 时才跑）——"验证被正确执行"不能依赖模型自觉：
 *
 * 1. exit 非零 → 机器 FAIL（模型声明 PASS 时拒绝存储，返回真实输出要求重判）
 * 2. exit 0 但缺 `expectStdoutContains` 字面量 → 机器 FAIL（命令通过但预期没达到）
 * 3. exit 0 但命中 `expectStdoutNotContains` 字面量 → 机器 FAIL
 * 4. 超时 → inconclusive（不存 PASS，也不硬拒——超时可能是缓存未热）
 * 5. 机器判定 FAIL 而模型坚持未通过（矛盾）→ 按未通过记录，标 `machineMismatch`，
 *    并要求主代理**如实告知用户**这是异常态（机器证明预期达成、模型判定不同）
 *
 * **归因两分支**：未通过时必须给 rootCause，且判定顺序是硬性的——
 * 先对照真实代码确认 executor 是否照 spec 执行（IMPLEMENTATION 错），
 * 只有在实现无误时才可归因于计划本身（PLAN 错）。
 * 验证方法对不对是计划的问题，不是验证器的问题。
 *
 * **真实证据落盘**：[VerificationResult.commandOutput]/[VerificationResult.commandExitCode]
 * 存机器真实输出与退出码，与模型自述的 evidence 分开——审计不经模型转述。
 */
class VerifyTools(
    private val sessionId: String,
    private val planStore: PlanStore,
    private val eventBus: MutableSharedFlow<MederiEvent>,
    private val shellTools: ShellTools? = null
) {

    @Serializable
    data class VerifySubtaskArgs(
        @LLMDescription("Plan ID.")
        val planId: String,
        @LLMDescription("Subtask index (0-based).")
        val subtaskIndex: Int,
        @LLMDescription("Verification result: PASS, PARTIAL, or FAIL.")
        val status: String,
        @LLMDescription("What you checked and what you found. Must reference real code or command output, not assumptions.")
        val evidence: String,
        @LLMDescription(
            "Required for PARTIAL/FAIL. Root cause — decide the order STRICTLY: first read the real code and " +
                "confirm whether the executor followed the spec (if it did not → IMPLEMENTATION); only if it " +
                "did → PLAN (the plan's own logic / verification method / expectation is wrong)."
        )
        val rootCause: String? = null,
        @LLMDescription("For FAIL: how to fix. Required when status is FAIL.")
        val remediation: String? = null,
        @LLMDescription("default runs the stored verification command in the sandbox. network/off requests one-time user permission for that exact verification command; it never changes the stored verification contract.")
        val sandbox: String = "default"
    )

    inner class VerifySubtaskTool : SimpleTool<VerifySubtaskArgs>(
        argsType = typeToken<VerifySubtaskArgs>(),
        name = ToolName.VERIFY_SUBTASK.value,
        description = "Verifies a subtask result against the plan's stored verification contract. The contract's " +
            "command is ALWAYS executed and its exit code + machine-checked output literals decide the machine " +
            "verdict; declaring PASS while the machine verdict fails is refused. Records PASS/PARTIAL/FAIL with " +
            "a required root cause (IMPLEMENTATION vs PLAN) when not passing."
    ) {
        override suspend fun execute(args: VerifySubtaskArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            val subtask = plan.subtasks.getOrNull(args.subtaskIndex)
                ?: return "Error: Subtask index ${args.subtaskIndex} not found (0..${plan.subtasks.size - 1})"

            if (plan.sessionId != sessionId || plan.status !in setOf(PlanStatus.APPROVED, PlanStatus.IN_PROGRESS))
                return "Error: Only this session's approved active plan can be verified."
            val structured = subtask.acceptanceCases.isNotEmpty()
            if (subtask.acceptanceCases.any { it.manual })
                return "Error: This task requires a human operator to submit observed values via manual acceptance."
            if (structured && subtask.overallAcceptance && !planStore.readyForOverallAcceptance(plan, subtask))
                return "Error: Finish or re-verify implementation tasks on the current files before overall acceptance."

            val declaredStatus = try {
                VerifyStatus.valueOf(args.status)
            } catch (e: IllegalArgumentException) {
                return "Error: Invalid status '${args.status}'. Use PASS, PARTIAL, or FAIL."
            }

            val rootCause = args.rootCause?.let {
                try { RootCause.valueOf(it) } catch (e: IllegalArgumentException) { null }
            }

            if (declaredStatus != VerifyStatus.PASS && rootCause == null) {
                return "Error: rootCause is required when status is PARTIAL or FAIL. Use IMPLEMENTATION " +
                    "(the spec said it clearly but the execution did not deliver) or PLAN (the execution " +
                    "matched the spec, but the plan's own logic/verification/expectation is wrong). " +
                    "Decide in this order, strictly: first confirm against the real code that the executor " +
                    "followed the spec; only if it did may you attribute the failure to the plan."
            }

            if (declaredStatus == VerifyStatus.FAIL && args.remediation.isNullOrBlank()) {
                return "Error: remediation is required when status is FAIL."
            }

            // 规则 2（空产出门禁，2026-10）：executor 报零改动 + 子任务有真实文件目标 → 拒 PASS。
            // 防 S15 事故：空报告 + 编译验证通过 = 假绿。没有产出物就不可能实现子任务。
            if (!structured && declaredStatus == VerifyStatus.PASS && shouldRejectEmptyOutputPass(subtask)) {
                return emptyOutputRejectionMessage(subtask)
            }

            // 无条件执行验证命令（2026-09-24）：不再只在模型声明 PASS 时才跑——"验证被正确执行"
            // 不能依赖模型自觉。命令退出码 + 字面量预期是机器判据，真实输出一律摆到桌面。
            val spec = subtask.verification
            val verifyCmd = spec.command.takeIf { it.isNotBlank() }
            val filesMatch = !structured || planStore.acceptanceFilesMatch(subtask)
            var blocked = when {
                !filesMatch -> "Approved collector or fixture changed. Restore it or submit a new plan for approval."
                structured && verifyCmd == null -> "The approved collector command is missing."
                structured && shellTools == null -> "The shell runner is unavailable."
                else -> null
            }
            val sourceDigests = if (structured) planStore.implementationDigests(plan, subtask) else emptyMap()
            val autoVerify: CommandResult? = if (verifyCmd != null && blocked == null) {
                val shell = shellTools
                    ?: return "Error: this subtask has a verification command but the shell runner is not " +
                        "available in this assembly. That is a configuration fault, not a verification result — " +
                        "refusing to record anything. Do not declare PASS."
                try {
                    shell.runCommand(verifyCmd, spec.timeoutSeconds ?: 30, spec.cwd, args.sandbox, "verify_subtask")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!structured)
                        return "Error: verification command could not execute: ${failure.message}. No verification result was recorded."
                    blocked = "Collector could not execute: ${failure.message}"
                    null
                }
            } else null

            if (structured && autoVerify?.timedOut == true) blocked = "Collector timed out."
            if (structured && autoVerify != null && !planStore.acceptanceFilesMatch(subtask))
                blocked = "Approved collector or fixture changed during the run."
            if (structured && autoVerify != null && planStore.implementationDigests(plan, subtask) != sourceDigests)
                blocked = "Declared implementation files changed during the run. Re-run on a stable checkout."
            val acceptanceRun = if (structured) AcceptanceRun.evaluate(
                subtask.acceptanceCases,
                autoVerify?.output.orEmpty(),
                autoVerify?.takeUnless { it.timedOut }?.exitCode,
                blocked
            ).copy(
                sourceDigests = sourceDigests,
                executionNotes = listOfNotNull(args.evidence.takeIf { it.isNotBlank() }, args.remediation)
                    .joinToString("\n").take(8000)
            ) else null
            val verifyStatus = if (acceptanceRun != null && !acceptanceRun.passed) VerifyStatus.FAIL else declaredStatus

            val literalCheck = autoVerify?.let {
                checkOutputLiterals(it.output, spec.expectStdoutContains, spec.expectStdoutNotContains)
            }
            // 机器判定 = exit 码三态 + 字面量预期（只有 exit 0 才继续查字面量）
            val machineOutcome: VerifyCommandOutcome? = autoVerify?.let {
                val base = verifyCommandOutcome(it.exitCode, it.timedOut)
                if (base == VerifyCommandOutcome.PASS && literalCheck?.ok == false) VerifyCommandOutcome.FAIL
                else base
            }

            // 模型声明 PASS：机器判定必须放行（这是防"命令失败/预期未达成却盖章 PASS"的硬门）
            if (!structured && verifyStatus == VerifyStatus.PASS) {
                when (machineOutcome) {
                    VerifyCommandOutcome.TIMEOUT -> return "Verification command (auto-executed) TIMED OUT " +
                        "(inconclusive):\n  $verifyCmd\n" +
                        "Output:\n${autoVerify.output.take(2000)}\n\n" +
                        "The verification timed out instead of failing — PASS is not stored. " +
                        "Re-run verify_subtask after warming the cache, or accept manual evidence."
                    VerifyCommandOutcome.FAIL -> return "Verification command (auto-executed from plan):\n" +
                        "  $verifyCmd\n" + describeMachineFailure(autoVerify, literalCheck, spec) + "\n\n" +
                        "The result does NOT match the plan's criteria — do NOT declare PASS. Re-examine the " +
                        "real output and call verify_subtask again with the correct status (FAIL with " +
                        "remediation + rootCause, or PARTIAL with rootCause)."
                    VerifyCommandOutcome.PASS, null -> {} // 机器放行（或无命令）：继续正常存储
                }
            }

            // 机器判定通过、模型却坚持未通过 → 异常态。用户裁定：算未通过（不 PASS），
            // 但必须让主代理如实告知用户"机器证明预期已达成、模型判定不同"。
            val contradiction = declaredStatus != VerifyStatus.PASS &&
                (acceptanceRun?.passed ?: (machineOutcome == VerifyCommandOutcome.PASS))

            // scope 越界检查（信任 AI 哲学：只报告、不硬拒 PASS/FAIL 判定）：
            // 执行器实际改动的文件（Subtask.executorTouchedFiles）vs 子任务声明的 targetFiles——
            // 越界文件追加进 evidence，供主代理判断（越界可能是合理的：用户并行工作、共享文件）。
            val outOfScopeFiles = subtask.executorTouchedFiles.filter { touched ->
                subtask.targetFiles.none { target ->
                    target.endsWith(touched) || touched.endsWith(target)
                }
            }
            val evidenceWithScope = if (outOfScopeFiles.isEmpty()) args.evidence else
                args.evidence + "\n\n[scope] 执行器改动超出 targetFiles 的文件: " + outOfScopeFiles.joinToString(", ")

            val result = VerificationResult(
                status = verifyStatus,
                evidence = evidenceWithScope,
                rootCause = rootCause,
                remediation = args.remediation,
                // 机器真实输出/退出码（截断存储，审计用；未经模型转述）
                commandOutput = autoVerify?.output?.take(4000),
                commandExitCode = autoVerify?.takeIf { !it.timedOut }?.exitCode,
                machineMismatch = contradiction
            )

            val newStatus = when (verifyStatus) {
                VerifyStatus.PASS -> SubtaskStatus.COMPLETED
                VerifyStatus.PARTIAL, VerifyStatus.FAIL -> SubtaskStatus.FAILED
            }

            // 原子写入验证结果与子任务状态：工具支持并行调度，同消息多次 verify_subtask 时，
            // 裸 load→copy→save 会让后写的 plan 覆盖先写的结果（验证结果静默丢失）。
            // 全部子任务 PASS 时顺带收尾：plan status → COMPLETED（随后归档）。
            val finalizedPlan = planStore.updatePlan(args.planId) { p ->
                val current = p.subtasks.getOrNull(args.subtaskIndex) ?: return@updatePlan null
                if (p.sessionId != sessionId || p.status !in setOf(PlanStatus.APPROVED, PlanStatus.IN_PROGRESS) ||
                    current.verification != spec || current.acceptanceCases != subtask.acceptanceCases ||
                    current.acceptanceFiles != subtask.acceptanceFiles ||
                    (current.overallAcceptance && !planStore.readyForOverallAcceptance(p, current)))
                    return@updatePlan null
                val withResult = p.copy(
                    subtasks = p.subtasks.map { st ->
                        if (st.index == args.subtaskIndex) st.copy(
                            status = newStatus,
                            verificationResult = result,
                            acceptanceRuns = if (acceptanceRun == null) st.acceptanceRuns else st.acceptanceRuns + acceptanceRun
                        )
                        else st
                    }
                )
                if (verifyStatus == VerifyStatus.PASS && withResult.isAllCompleted)
                    withResult.copy(status = xyz.mederi.plan.PlanStatus.COMPLETED)
                else withResult
            } ?: return "Error: The plan or its prerequisite evidence changed during the run. Reload the plan and retry."
            if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                // 先落 Walkthrough（结果总结，自动装配自 Plan 数据）再归档——归档只移整个计划目录
                runCatching { planStore.writeWalkthrough(finalizedPlan) }
                runCatching { planStore.archive(args.planId) }
            }

            val passed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
            val failed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.FAILED }
            val pending = finalizedPlan.subtasks.count { it.status == SubtaskStatus.PENDING }
            val subtasksJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Subtask.serializer()),
                finalizedPlan.subtasks
            )

            eventBus.emit(MederiEvent(
                type = EventType.PLAN_PROGRESS,
                sessionId = sessionId,
                payload = mapOf(
                    "planId" to args.planId,
                    "action" to if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) "completed" else "verified",
                    "passed" to passed.toString(),
                    "failed" to failed.toString(),
                    "pending" to pending.toString(),
                    "lastSubtaskIndex" to args.subtaskIndex.toString(),
                    "lastSubtaskStatus" to verifyStatus.name,
                    "lastSubtaskRootCause" to (args.rootCause ?: ""),
                    "subtasks" to subtasksJson
                ),
                timestamp = Instant.now().toString()
            ))

            val verifyNote = acceptanceRun?.let { run ->
                "\n\nAcceptance run ${run.id}: ${run.detail}\n" + run.observations.joinToString("\n") { item ->
                    "${item.caseId}: ${item.verdict}, expected=${item.expected}, observed=${item.actual ?: "(missing)"}"
                }
            } ?: autoVerify?.let { vr ->
                "\n\nVerification command output (auto-executed, exit ${vr.exitCode}):\n${vr.output.take(2000)}"
            } ?: ""

            val contradictionNotice = when {
                contradiction && structured -> "\nCore's observed values matched the approved cases, but your assessment differed. Report the disagreement."
                contradiction -> buildContradictionNotice(verifyCmd ?: "", spec, args.status)
                else -> ""
            }

            return if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                val next = if (structured) "Open the acceptance report for plan ${args.planId}. Explain individual results and evidence."
                    else "Plan ${args.planId} archived. The acceptance report marks legacy cases as lacking structured evidence."
                "Subtask #${args.subtaskIndex} verified: PASS. $next$verifyNote$contradictionNotice"
            } else if (verifyStatus == VerifyStatus.PASS) {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: PASS. ($passed passed, $failed failed, $pending pending)$verifyNote$contradictionNotice"
            } else {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: ${verifyStatus.name}" +
                    (args.rootCause?.let { " ($it)" } ?: "") +
                    ". ($passed passed, $failed failed, $pending pending) ${if (structured) "Repair and retry the same cases, or submit a replacement plan for approval." else guidanceFor(rootCause)}$verifyNote$contradictionNotice"
            }
        }
    }

    /** 机器 FAIL 的具体原因（exit 非零 / 缺 must-contain / 命中 must-not-contain）。 */
    private fun describeMachineFailure(
        autoVerify: CommandResult,
        literalCheck: LiteralCheckResult?,
        spec: VerificationSpec
    ): String {
        val sb = StringBuilder()
        if (autoVerify.exitCode != 0) {
            sb.appendLine("Exit code: ${autoVerify.exitCode}")
        }
        literalCheck?.missingContains?.takeIf { it.isNotEmpty() }?.let {
            sb.appendLine("Expected output literals NOT found: ${it.joinToString(", ")}")
            sb.appendLine("(The command may have exited 0, but the plan's expected result was not met.)")
        }
        literalCheck?.hitNotContains?.takeIf { it.isNotEmpty() }?.let {
            sb.appendLine("Output contained FORBIDDEN literals: ${it.joinToString(", ")}")
        }
        if (spec.expected.isNotBlank()) sb.appendLine("Plan's stated expectation: ${spec.expected}")
        sb.appendLine("Output:")
        sb.append(autoVerify.output.take(2000))
        return sb.toString().trimEnd()
    }

    /** 机器判定通过、模型判定未通过 → 异常态告知模板（要求主代理向用户如实说明）。 */
    private fun buildContradictionNotice(
        command: String,
        spec: VerificationSpec,
        declaredStatus: String
    ): String = buildString {
        appendLine()
        appendLine()
        appendLine("⚠ MACHINE/MODEL CONTRADICTION — ABNORMAL STATE (machineMismatch=true)")
        appendLine("The plan's verification command was auto-executed and the MACHINE verdict is PASS:")
        appendLine("  command: $command")
        if (spec.expectStdoutContains.isNotEmpty())
            appendLine("  must-contain literals all matched: ${spec.expectStdoutContains.joinToString(", ")}")
        if (spec.expectStdoutNotContains.isNotEmpty())
            appendLine("  must-not-contain literals all absent: ${spec.expectStdoutNotContains.joinToString(", ")}")
        if (spec.expected.isNotBlank()) appendLine("  plan's stated expectation: ${spec.expected}")
        appendLine("You nonetheless declared $declaredStatus. When the machine-checked expectations hold this")
        appendLine("should not happen, so it was recorded as $declaredStatus (non-PASS, machineMismatch=true).")
        appendLine("You MUST tell the user about this anomaly explicitly and in their language: state that the machine")
        appendLine("verified the plan's expectations were met while your own assessment differed, and say what you")
        appendLine("believe the machine's check missed. Do not silently proceed and do not hide the disagreement.")
    }

    /** 归因两分支 → 下一步动作（IMPLEMENTATION=追加补救；PLAN=追加修订）。 */
    private fun guidanceFor(rootCause: RootCause?): String = when (rootCause) {
        RootCause.IMPLEMENTATION ->
            "Root cause = IMPLEMENTATION: the spec was clear but the execution did not deliver it. " +
                "Call converge_plan to append remediation subtasks, then re-execute."
        RootCause.PLAN ->
            "Root cause = PLAN: the execution matched the spec, so the plan itself is wrong (its logic, " +
                "verification method, or expectation). Amend append-only, never rewrite: if the verification " +
                "contract is wrong call update_verification (reason required); if the spec was wrong call " +
                "generate_spec with reason= to correct it. Do NOT use converge_plan for a plan error."
        null -> ""
    }
}
