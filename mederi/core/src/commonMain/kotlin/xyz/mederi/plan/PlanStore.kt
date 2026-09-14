package xyz.mederi.plan

import kotlinx.serialization.json.Json
import java.io.File
/**
 * 从项目目录列表中查找 `.mederi/` 工作目录。
 * 多目录项目取第一个找到的 `.mederi/`。
 * 只读语义：不存在返回 null，绝不创建（读路径用）。
 */
internal fun findMederiDir(projectDirectories: List<String>): File? =
    projectDirectories.firstNotNullOfOrNull { dir ->
        val d = File(dir, ".mederi")
        if (d.isDirectory) d else null
    }

/**
 * 确保 `.mederi/` 工作目录存在，不存在时自动初始化。
 */
internal fun ensureMederiDir(projectDirectories: List<String>): File? {
    findMederiDir(projectDirectories)?.let { return it }
    val main = projectDirectories.firstOrNull() ?: return null
    val mederi = File(main, ".mederi")
    File(mederi, "plans").mkdirs()
    File(mederi, "plans-done").mkdirs()
    val notebook = File(mederi, "notebook.md")
    if (!notebook.exists()) notebook.writeText("# 工作日志\n")
    return mederi
}

class PlanStore(private val projectDirectories: List<String>) {

    private companion object {
        /**
         * 跨实例共享的 plan 写锁：同一进程内即使存在多个 PlanStore 实例，
         * load→copy→save 也不会互相覆盖（parallel 工具调用场景，见 updatePlan）。
         */
        private val planWriteLock = Any()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val plansDir: File? get() = findMederiDir(projectDirectories)?.let { File(it, "plans") }

    private val writePlansDir: File? get() = ensureMederiDir(projectDirectories)?.let { File(it, "plans") }

    /**
     * 保存计划：双文件落盘。
     * - `{planId}.json`：机器可读真理源（load 全走它，解析零歧义）
     * - `{planId}.md`：纯人读 Markdown（无 JSON 注释块），批准 UI / 用户点开阅读的就是它
     */
    fun save(plan: Plan) {
        val dir = writePlansDir ?: return
        dir.mkdirs()
        File(dir, "${plan.id}.json").writeText(json.encodeToString(Plan.serializer(), plan))
        File(dir, "${plan.id}.md").writeText(buildMarkdown(plan))
    }

    fun load(planId: String): Plan? {
        val file = plansDir?.let { File(it, "$planId.json") } ?: return null
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * 按 sessionId 加载活跃计划（未 COMPLETED 的）。
     */
    fun loadBySession(sessionId: String): Plan? {
        val dir = plansDir ?: return null
        val files = dir.listFiles { f -> f.extension == "json" } ?: return null
        for (file in files) {
            val plan = runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }.getOrNull() ?: continue
            if (plan.sessionId == sessionId && plan.status != PlanStatus.COMPLETED) return plan
        }
        return null
    }

    fun loadActive(): Plan? {
        val dir = plansDir ?: return null
        val files = dir.listFiles { f -> f.extension == "json" } ?: return null
        for (file in files) {
            val plan = runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }.getOrNull() ?: continue
            if (plan.status != PlanStatus.COMPLETED) return plan
        }
        return null
    }

    /**
     * 返回 plan 文件相对项目目录的路径，如 ".mederi/plans/plan_abc.md"。
     */
    fun getPlanRelativePath(planId: String): String? {
        val dir = plansDir ?: return null
        val file = File(dir, "$planId.md")
        return if (file.exists()) ".mederi/plans/$planId.md" else null
    }

    /**
     * 返回 plan 文件绝对路径。事件 payload 用绝对路径，
     * 调用方（UI/桥接层）无需项目目录上下文即可直接读取文件。
     */
    fun getPlanAbsolutePath(planId: String): String? {
        val dir = plansDir ?: return null
        val file = File(dir, "$planId.md")
        return if (file.exists()) file.absolutePath else null
    }

    fun archive(planId: String) {
        val dst = ensureMederiDir(projectDirectories)?.let { File(it, "plans-done") } ?: return
        dst.mkdirs()
        // json + md 双文件一起归档
        listOf("json", "md").forEach { ext ->
            val src = writePlansDir?.let { File(it, "$planId.$ext") }
            if (src != null && src.exists()) {
                src.copyTo(File(dst, "$planId.$ext"), overwrite = true)
                src.delete()
            }
        }
    }

    fun update(plan: Plan) = save(plan)

    /**
     * 原子读-改-写：加锁执行 [transform]，返回非 null 的 plan 时落盘并返回，返回 null 则放弃写。
     *
     * 需要改 plan 的工具（spawn_agent 的 IN_PROGRESS 标记、generate_spec 的 spec 写入、
     * verify_subtask 的验证结果）必须走这里，不能用 load→copy→save——
     * 工具现已支持并行调度，裸 RMW 会互相覆盖（A.load → B.load → A.save → B.save 把 A 写丢）。
     * [transform] 必须是纯内存操作（不得执行命令/IO/挂起），锁才不会被长操作占住。
     *
     * @return 写盘后的新 plan；planId 不存在或 transform 返回 null 时为 null。
     */
    fun updatePlan(planId: String, transform: (Plan) -> Plan?): Plan? = synchronized(planWriteLock) {
        val plan = load(planId) ?: return null
        val updated = transform(plan) ?: return null
        save(updated)
        updated
    }

    private fun buildMarkdown(plan: Plan): String {
        val sb = StringBuilder()
        // ===== Part 1: Implementation Plan（给人读，纯 Markdown）=====
        sb.appendLine("# Implementation Plan: ${plan.title}")
        sb.appendLine()
        // 内部状态（Status/Created/AgentMode/WorkType）由 UI 卡片与侧边栏呈现，不写进文档
        sb.appendLine("## Project Context")
        sb.appendLine("- Type: ${if (plan.projectContext == ProjectContextType.GREENFIELD) "GREENFIELD (brand-new project)" else "BROWNFIELD (iterating existing codebase)"}")
        if (plan.languageStack.isNotBlank()) sb.appendLine("- Stack: ${plan.languageStack}")
        sb.appendLine()

        if (plan.summary.isNotBlank()) {
            sb.appendLine("> ${plan.summary}")
            sb.appendLine()
        }

        sb.appendLine("## Overview")
        sb.appendLine(plan.overview)
        sb.appendLine()

        // 业务逻辑段（CODE 模式必填，WORK 模式留空不渲染）
        if (plan.businessLogic.isNotBlank()) {
            sb.appendLine("## Business Logic")
            sb.appendLine(plan.businessLogic)
            sb.appendLine()
        }

        if (plan.inScope.isNotEmpty()) {
            sb.appendLine("## Scope")
            sb.appendLine("### In Scope")
            plan.inScope.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            if (plan.outScope.isNotEmpty()) {
                sb.appendLine("### Out of Scope")
                plan.outScope.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
        }

        if (plan.keyDecisions.isNotEmpty()) {
            sb.appendLine("## Key Decisions")
            plan.keyDecisions.forEach { d ->
                sb.appendLine("- **${d.question}** -> ${d.choice}")
                sb.appendLine("  - Rationale: ${d.rationale}")
                sb.appendLine("  - Alternatives: ${d.alternatives}")
            }
            sb.appendLine()
        }

        if (plan.changes.isNotEmpty()) {
            sb.appendLine("## Changes")
            plan.changes.groupBy { it.module }.forEach { (module, changes) ->
                sb.appendLine("### $module")
                changes.forEach { c ->
                    sb.appendLine("- **[${c.action}]** `${c.filePath}`: ${c.description}")
                    sb.appendLine("  - Rationale: ${c.rationale}")
                }
            }
            sb.appendLine()
        }

        // 数据与参数段（可选，不涉及数据/参数的任务整段省略）
        if (plan.dataAndParams.isNotEmpty()) {
            sb.appendLine("## Data & Parameters")
            plan.dataAndParams.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (plan.risks.isNotEmpty()) {
            sb.appendLine("## Risks")
            plan.risks.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (plan.successCriteria.isNotEmpty()) {
            sb.appendLine("## Success Criteria")
            plan.successCriteria.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        plan.architecture?.let {
            sb.appendLine("## Architecture")
            sb.appendLine("```mermaid")
            sb.appendLine(it)
            sb.appendLine("```")
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()

        // ===== Part 2: 子任务（Brief 批准时可见；Spec 批准后由 generate_spec 派生）=====
        sb.appendLine("## Subtasks")
        sb.appendLine()
        plan.subtasks.forEach { st ->
            sb.appendLine("### Subtask ${st.index + 1}: ${st.name}")
            sb.appendLine("**Status**: ${st.status}")
            if (st.targetFiles.isNotEmpty()) sb.appendLine("**Files**: ${st.targetFiles.joinToString(", ")}")
            if (st.dependsOn.isNotEmpty()) sb.appendLine("**Depends On**: ${st.dependsOn.joinToString(", ")}")
            if (st.parallelizable) sb.appendLine("**Parallelizable**: yes")
            sb.appendLine()
            sb.appendLine("#### Brief")
            sb.appendLine(st.planDetail)
            sb.appendLine()
            if (!st.spec.isNullOrBlank()) {
                sb.appendLine("#### Spec")
                sb.appendLine(st.spec)
                sb.appendLine()
            }
            if (st.decisions.isNotEmpty()) {
                sb.appendLine("#### Decisions")
                st.decisions.forEach { d ->
                    sb.appendLine("- ${d.question} -> ${d.choice} (${d.rationale})")
                }
                sb.appendLine()
            }
            sb.appendLine("#### Verification")
            sb.appendLine(st.verification)
            st.verificationResult?.let { r ->
                sb.appendLine()
                sb.appendLine("#### Verification Result")
                sb.appendLine("- Status: ${r.status}")
                sb.appendLine("- Evidence: ${r.evidence}")
                r.gapType?.let { sb.appendLine("- Gap Type: $it") }
                r.remediation?.let { sb.appendLine("- Remediation: $it") }
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }
}
