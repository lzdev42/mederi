package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.encodeTodos
import xyz.mederi.plan.GapType
import xyz.mederi.plan.PlanStore
import xyz.mederi.plan.SubtaskStatus
import xyz.mederi.plan.VerifyStatus
import xyz.mederi.plan.VerificationResult
import xyz.mederi.plan.toTodoProjection
import xyz.mederi.tools.ShellTools.CommandResult
import java.time.Instant

/**
 * 验证工具。主代理在 spawn_agent 返回后调用 verify_subtask。
 *
 * **自动验证**：若子任务存了 [xyz.mederi.plan.Subtask.verification] 命令，工具在
 * 存储 status 之前自动执行它（经 ShellTools 沙箱），把真实输出摆到桌面——
 * 主代理无法再仅凭 executor 自述就盖章 PASS。
 *
 * - exit code 0：正常存储 status，输出附在返回文本里供模型参考。
 * - exit code 非 0 且模型声明 PASS：**拒绝存储**，返回真实输出让模型重判。
 *
 * 端到端实测：watchdog 回滚文件后 executor 诚实报 success 但文件实为 v1，
 * 主代理 evidence 转述 executor 自述、归档了产物错误的"COMPLETED"计划。
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
        @LLMDescription("Gap type for PARTIAL/FAIL: MISSING, PARTIAL, CONTRADICTS, or UNREQUESTED.")
        val gapType: String? = null,
        @LLMDescription("For FAIL: how to fix. Required when status is FAIL.")
        val remediation: String? = null
    )

    inner class VerifySubtaskTool : SimpleTool<VerifySubtaskArgs>(
        argsType = typeToken<VerifySubtaskArgs>(),
        name = "verify_subtask",
        description = "After spawn_agent returns, verify the subtask result against its verification criteria. " +
            "Read the actual code/files, run verification commands, and report PASS, PARTIAL, or FAIL. " +
            "NEVER declare a subtask done without calling this tool."
    ) {
        override suspend fun execute(args: VerifySubtaskArgs): String {
            val plan = planStore.load(args.planId)
                ?: return "Error: Plan not found: ${args.planId}"
            val subtask = plan.subtasks.getOrNull(args.subtaskIndex)
                ?: return "Error: Subtask index ${args.subtaskIndex} not found (0..${plan.subtasks.size - 1})"

            val verifyStatus = try {
                VerifyStatus.valueOf(args.status)
            } catch (e: IllegalArgumentException) {
                return "Error: Invalid status '${args.status}'. Use PASS, PARTIAL, or FAIL."
            }

            val gapType = args.gapType?.let {
                try { GapType.valueOf(it) } catch (e: IllegalArgumentException) { null }
            }

            if (verifyStatus != VerifyStatus.PASS && gapType == null) {
                return "Error: gapType is required when status is PARTIAL or FAIL. " +
                    "Use: MISSING, PARTIAL, CONTRADICTS, or UNREQUESTED."
            }

            if (verifyStatus == VerifyStatus.FAIL && args.remediation.isNullOrBlank()) {
                return "Error: remediation is required when status is FAIL."
            }

            // 自动执行子任务的 verification 命令（若存了），在存储 status 之前。
            // 这把"验证"从模型自觉行为变成工具强制行为——主代理无法回避真实输出。
            val verifyCmd = subtask.verification?.takeIf { it.isNotBlank() }
            val autoVerify: CommandResult? = verifyCmd?.let { cmd ->
                runCatching { shellTools?.runCommand(cmd, 10) }.getOrNull()
            }

            // PASS 时若验证命令 exit 非 0：拒绝存储，把真实输出返回让模型重判
            if (verifyStatus == VerifyStatus.PASS && autoVerify != null && autoVerify.exitCode != 0) {
                return "Verification command (auto-executed from plan):\n  $verifyCmd\n" +
                    "Exit code: ${autoVerify.exitCode}\n" +
                    "Output:\n${autoVerify.output.take(2000)}\n\n" +
                    "The verification command exited non-zero — the result does NOT match the plan's criteria. " +
                    "Do NOT declare PASS. Re-examine the actual output and call verify_subtask again with " +
                    "the correct status (FAIL with remediation, or PARTIAL with gapType)."
            }

            val result = VerificationResult(
                status = verifyStatus,
                evidence = args.evidence,
                gapType = gapType,
                remediation = args.remediation
            )

            val newStatus = when (verifyStatus) {
                VerifyStatus.PASS -> SubtaskStatus.COMPLETED
                VerifyStatus.PARTIAL, VerifyStatus.FAIL -> SubtaskStatus.FAILED
            }

            val updatedSubtask = subtask.copy(
                status = newStatus,
                verificationResult = result
            )
            val updatedPlan = plan.copy(
                subtasks = plan.subtasks.mapIndexed { i, st ->
                    if (i == args.subtaskIndex) updatedSubtask else st
                }
            )
            // 全部子任务 PASS 时收尾：plan status → COMPLETED + 归档（plans → plans-done）。
            // 之前缺失这一环：isAllCompleted / archive 是孤儿（零调用点），导致计划跑完后
            // status 永远停在 APPROVED、永远不归档（端到端实测抓到）。
            val finalizedPlan = if (verifyStatus == VerifyStatus.PASS && updatedPlan.isAllCompleted) {
                updatedPlan.copy(status = xyz.mederi.plan.PlanStatus.COMPLETED)
            } else updatedPlan
            planStore.save(finalizedPlan)
            if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                runCatching { planStore.archive(args.planId) }
            }

            val passed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.COMPLETED }
            val failed = finalizedPlan.subtasks.count { it.status == SubtaskStatus.FAILED }
            val pending = finalizedPlan.subtasks.count { it.status == SubtaskStatus.PENDING }

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
                    "lastSubtaskStatus" to args.status,
                    "lastSubtaskGapType" to (args.gapType ?: ""),
                    "todos" to finalizedPlan.toTodoProjection().encodeTodos()
                ),
                timestamp = Instant.now().toString()
            ))

            val verifyNote = autoVerify?.let { vr ->
                "\n\nVerification command output (auto-executed, exit ${vr.exitCode}):\n${vr.output.take(2000)}"
            } ?: ""

            return if (finalizedPlan.status == xyz.mederi.plan.PlanStatus.COMPLETED) {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: PASS. All subtasks completed — plan ${args.planId} archived to .mederi/plans-done/.$verifyNote"
            } else if (verifyStatus == VerifyStatus.PASS) {
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: PASS. ($passed passed, $failed failed, $pending pending)$verifyNote"
            } else {
                // 缺口 D：按 gapType 分流，不再对所有 FAIL 都说 converge_plan。
                // CONTRADICTS = spec 跟现实矛盾（AI 能修：re-generate_spec 覆盖）；
                // 其余 = 执行错（converge_plan 追加补救）。
                val guidance = if (gapType == GapType.CONTRADICTS) {
                    "The spec contradicts reality — re-call generate_spec(planId=${args.planId}, " +
                        "subtaskIndex=${args.subtaskIndex}) to replace the spec, then spawn_agent to re-execute. " +
                        "Do NOT use converge_plan for a spec error."
                } else {
                    "Call converge_plan to append remediation subtasks."
                }
                "Subtask #${args.subtaskIndex} (0-based, of ${plan.subtasks.size}) verified: ${args.status}" +
                    (args.gapType?.let { " ($it)" } ?: "") +
                    ". ($passed passed, $failed failed, $pending pending) $guidance$verifyNote"
            }
        }
    }
}
