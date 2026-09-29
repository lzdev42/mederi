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
     * 计划目录：`plans/{planId}/`。一个计划一个目录，下面挂 plan.json / plan.md /
     * research.md / reports/ / walkthrough.md。聚合根真理源 = plan.json，其余为工作文件。
     */
    private fun planDir(planId: String, write: Boolean = false): File? {
        val base = if (write) writePlansDir else plansDir
        return base?.let { File(it, planId) }
    }

    private fun writePlanDir(planId: String): File? {
        val dir = planDir(planId, write = true) ?: return null
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 保存计划：双文件落盘到 `plans/{planId}/` 目录。
     * - `plan.json`：机器可读真理源（load 全走它，解析零歧义）
     * - `plan.md`：纯人读 Markdown（无 JSON 注释块），批准 UI / 用户点开阅读的就是它
     */
    fun save(plan: Plan) {
        val dir = writePlanDir(plan.id) ?: return
        File(dir, "plan.json").writeText(json.encodeToString(Plan.serializer(), plan))
        File(dir, "plan.md").writeText(buildMarkdown(plan))
    }

    fun load(planId: String): Plan? {
        val file = planDir(planId)?.let { File(it, "plan.json") } ?: return null
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * 按 sessionId 加载该会话最晚的非终态计划（终态 = COMPLETED/VOIDED）。
     *
     * 计划状态机：每个会话同时最多一条"活跃"（非终态）计划——新 create_plan 作废旧的非终态
     * 计划（voidActivePlans）并成为新的活跃计划。多份计划目录并列时按 createdAt 取最新，
     * 避免目录乱序读到旧的作废前残本。作废计划已移出 plans/，正常不在此目录；此处仍按
     * [Plan.isTerminal] 兜底排除，兼顾移出失败/历史数据的边界。
     */
    fun loadBySession(sessionId: String): Plan? =
        allPlans()
            .filter { it.sessionId == sessionId && !it.isTerminal }
            .maxByOrNull { it.createdAt }

    /**
     * 按 sessionId 加载该会话的所有历史与活跃计划（涵盖 plans/、plans-done/、plans-voided/），
     * 按 createdAt 降序排列。
     */
    fun listBySession(sessionId: String): List<Plan> {
        val mederi = findMederiDir(projectDirectories) ?: return emptyList()
        val all = mutableListOf<Plan>()
        for (sub in listOf("plans", "plans-done", "plans-voided")) {
            val dir = File(mederi, sub)
            if (!dir.isDirectory) continue
            dir.listFiles { f -> f.isDirectory }?.forEach { subDir ->
                val file = File(subDir, "plan.json")
                if (file.exists()) {
                    runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }
                        .getOrNull()
                        ?.let { if (it.sessionId == sessionId) all.add(it) }
                }
            }
        }
        return all.sortedByDescending { it.createdAt }
    }

    fun loadActive(): Plan? =
        allPlans()
            .filter { !it.isTerminal }
            .maxByOrNull { it.createdAt }

    /** 读取 plans/ 目录全部可解析计划（每个计划一个子目录，读 plan.json）。 */
    private fun allPlans(): List<Plan> {
        val dir = plansDir ?: return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { subDir ->
                val file = File(subDir, "plan.json")
                if (!file.exists()) return@mapNotNull null
                runCatching { json.decodeFromString(Plan.serializer(), file.readText()) }.getOrNull()
            }
            ?: emptyList()
    }

    /**
     * 作废某会话全部非终态计划（含执行中）：状态置 VOIDED 落盘后连同整个 {planId}/ 目录
     * 移入 `plans-voided/`（与 [archive] 的 plans-done/ 对称，作废计划留痕可查、不删文件）。
     *
     * 触发时机 = 新 create_plan：同一会话只有最新计划可执行，旧计划（无论 PENDING_APPROVAL /
     * APPROVED / IN_PROGRESS）一律作废。已完成的算凭据保留，不受影响。
     *
     * @return 被作废的计划 id列表（由调用方用于发 PLAN_PROGRESS(voided) 事件）。
     */
    fun voidActivePlans(sessionId: String): List<String> {
        val dir = plansDir ?: return emptyList()
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        val voided = mutableListOf<String>()
        for (subDir in subDirs) {
            val planId = subDir.name
            val plan = load(planId) ?: continue
            if (plan.sessionId == sessionId && !plan.isTerminal) {
                updatePlan(plan.id) { it.copy(status = PlanStatus.VOIDED) }
                moveToVoided(plan.id)
                voided += plan.id
            }
        }
        return voided
    }

    /** 把整个 `plans/{planId}/` 目录移入 `plans-voided/{planId}/`（作废留痕，对称 archive）。 */
    private fun moveToVoided(planId: String) {
        val dst = ensureMederiDir(projectDirectories)?.let { File(it, "plans-voided") } ?: return
        dst.mkdirs()
        val src = planDir(planId, write = true) ?: return
        if (src.exists()) {
            // copyRecursively：copyTo 对目录只创建空壳不递归；plan.json/research.md/reports/ 必须整树搬走
            src.copyRecursively(File(dst, planId), overwrite = true)
            src.deleteRecursively()
        }
    }

    /**
     * 返回 plan 文件相对项目目录的路径，如 ".mederi/plans/plan_abc/plan.md"。
     */
    fun getPlanRelativePath(planId: String): String? {
        val mederi = findMederiDir(projectDirectories) ?: return null
        for (sub in listOf("plans", "plans-done", "plans-voided")) {
            val file = File(File(mederi, sub), "$planId/plan.md")
            if (file.exists()) return ".mederi/$sub/$planId/plan.md"
        }
        return null
    }

    /**
     * 返回 plan 文件绝对路径。事件 payload 用绝对路径，
     * 调用方（UI/桥接层）无需项目目录上下文即可直接读取文件。
     */
    fun getPlanAbsolutePath(planId: String): String? {
        val mederi = findMederiDir(projectDirectories) ?: return null
        for (sub in listOf("plans", "plans-done", "plans-voided")) {
            val file = File(File(mederi, sub), "$planId/plan.md")
            if (file.exists()) return file.absolutePath
        }
        return null
    }

    /**
     * 返回计划目录的绝对路径（用于落盘 research.md / reports/ 等工作文件）。
     * 不存在则创建（写入语义）。
     */
    fun getPlanDirAbsolutePath(planId: String): String? =
        writePlanDir(planId)?.absolutePath

    fun archive(planId: String) {
        val dst = ensureMederiDir(projectDirectories)?.let { File(it, "plans-done") } ?: return
        dst.mkdirs()
        val src = planDir(planId, write = true) ?: return
        if (src.exists()) {
            // copyRecursively：copyTo 对目录只创建空壳不递归；plan.json/walkthrough.md/reports/ 必须整树搬走
            src.copyRecursively(File(dst, planId), overwrite = true)
            src.deleteRecursively()
        }
    }

    /**
     * 计划完成（全部子任务 PASS）时自动装配 Walkthrough（结果总结，给人看），
     * 写入 `plans/{planId}/walkthrough.md`（archive 时随之移动到 plans-done/{planId}/）。
     *
     * 三块定死内容全部来自 Plan 已有数据（零额外 token）：改动内容（计划改动 + 各子任务
     * 实际触碰文件）、测试内容（各子任务验证命令）、验证结果（各子任务状态与证据）。
     * 尾部备注段留空，由 AI 在计划完成后按提示词自行补充（过程要点、UI 截图等）。
     * 代码级装配、无 AI 参与——验证结果即写即真，不经过模型转述。
     */
    fun writeWalkthrough(plan: Plan) {
        val dir = writePlanDir(plan.id) ?: return
        File(dir, "walkthrough.md").writeText(buildWalkthrough(plan))
    }

    /**
     * 落盘 researcher 报告到 `plans/{planId}/research.md`。
     *
     * 触发时机：researcher 子代理在活跃 plan 上下文中完成时调用。
     * 用途：executor 需要调研结论时 read_file 此路径（替代旧 briefing 全文注入）。
     * 返回：写入文件的绝对路径（调用方据此告知父 agent 报告位置）；plan 不存在返回 null。
     */
    fun writeResearchReport(planId: String, text: String): String? {
        val dir = writePlanDir(planId) ?: return null
        File(dir, "research.md").writeText(text)
        return File(dir, "research.md").absolutePath
    }

    /**
     * 落盘独立研究报告到 `.mederi/research/{timestamp}.md`（无活跃 plan 时）。
     *
     * 触发时机：researcher 子代理完成、但无活跃 plan（分诊阶段调研）。
     * 用途：研究报告始终落盘——父上下文只收摘要+路径，需要详情时 read_file。
     * 返回：写入文件的绝对路径；写盘失败返回 null。
     */
    fun writeStandaloneResearchReport(text: String): String? {
        val mederi = ensureMederiDir(projectDirectories) ?: return null
        val dir = File(mederi, "research").apply { mkdirs() }
        val fileName = "${java.time.Instant.now().toString().replace(":", "-").replace(".", "-")}.md"
        val file = File(dir, fileName)
        file.writeText(text)
        return file.absolutePath
    }

    /**
     * 落盘 executor 报告到 `plans/{planId}/reports/{NN}-executor.md`。
     *
     * 触发时机：executor 子代理完成时调用（per-subtask 一份）。
     * 用途：父上下文只收摘要+此路径，需要详情时 read_file（替代旧 result 全文回灌）。
     * NN = subtaskIndex 两位零填充（00/01/...），保证目录排序与子任务序一致。
     * 返回：写入文件的绝对路径；plan 不存在返回 null。
     */
    fun writeExecutorReport(planId: String, subtaskIndex: Int, text: String): String? {
        val dir = writePlanDir(planId) ?: return null
        val reportsDir = File(dir, "reports").apply { mkdirs() }
        val fileName = "${subtaskIndex.toString().padStart(2, '0')}-executor.md"
        val file = File(reportsDir, fileName)
        file.writeText(text)
        return file.absolutePath
    }

    private fun buildWalkthrough(plan: Plan): String {
        val sb = StringBuilder()
        sb.appendLine("# 完成总结（Walkthrough）：${plan.title}")
        sb.appendLine()
        if (plan.summary.isNotBlank()) {
            sb.appendLine("> ${plan.summary}")
            sb.appendLine()
        }

        sb.appendLine("## 改动内容（Changes Made）")
        if (plan.changes.isNotEmpty()) {
            sb.appendLine("### 计划改动（Planned Changes）")
            plan.changes.groupBy { it.module }.forEach { (module, changes) ->
                sb.appendLine()
                sb.appendLine("**$module**")
                changes.forEach { c ->
                    sb.appendLine("- **[${c.action}]** `${c.filePath}`：${c.description}")
                }
            }
            sb.appendLine()
        }
        val touched = plan.subtasks.flatMap { it.executorTouchedFiles }.distinct().sorted()
        if (touched.isNotEmpty()) {
            sb.appendLine("### 实际修改的文件（Files Actually Modified）")
            touched.forEach { sb.appendLine("- `$it`") }
            sb.appendLine()
        }
        if (plan.changes.isEmpty() && touched.isEmpty()) {
            sb.appendLine("（无文件改动记录）")
            sb.appendLine()
        }

        sb.appendLine("## 测试内容（What Was Tested）")
        plan.subtasks.forEach { st ->
            sb.appendLine()
            sb.appendLine("### 子任务 ${st.index + 1}：${st.name}")
            sb.appendLine("```")
            sb.appendLine(st.verification.command)
            sb.appendLine("```")
            if (st.verification.expected.isNotBlank())
                sb.appendLine("- 预期结果：${st.verification.expected}")
        }
        sb.appendLine()

        sb.appendLine("## 验证结果（Validation Results）")
        plan.subtasks.forEach { st ->
            val r = st.verificationResult
            val status = r?.status?.name ?: st.status.name
            sb.appendLine()
            sb.appendLine("- **$status** — 子任务 ${st.index + 1}（${st.name}）")
            r?.evidence?.let { sb.appendLine("  - 证据：$it") }
        }
        sb.appendLine()

        sb.appendLine("## 备注（Notes）")
        sb.appendLine()
        sb.appendLine("（本段由 AI 在计划完成后补充：过程要点、未尽事项；UI 改动可附截图说明。）")
        return sb.toString().trimEnd()
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
        // 段名规范化：用户语言（中文）+ 英文括注；正文内容由 AI 按提示词规则用用户语言撰写
        sb.appendLine("# 计划（Plan）：${plan.title}")
        sb.appendLine()
        // 内部状态（Status/Created/AgentMode）由 UI 卡片与侧边栏呈现，不写进文档
        sb.appendLine("## 项目背景（Project Context）")
        sb.appendLine(
            if (plan.projectContext == ProjectContextType.GREENFIELD) "- 类型：GREENFIELD（全新项目）"
            else "- 类型：BROWNFIELD（迭代现有工程）"
        )
        if (plan.languageStack.isNotBlank()) sb.appendLine("- 技术栈：${plan.languageStack}")
        sb.appendLine()

        if (plan.summary.isNotBlank()) {
            sb.appendLine("> ${plan.summary}")
            sb.appendLine()
        }

        // 置顶两段：用户知情决策的第一落点，先于一切内容段
        if (plan.userReviewRequired.isNotEmpty()) {
            sb.appendLine("## 需要你确认（User Review Required）")
            plan.userReviewRequired.forEach { item ->
                sb.appendLine()
                // 条目带 GitHub alert 标签（[!WARNING] 等）时渲染为警示块，否则渲染为普通段落
                val alertMatch = Regex("^\\[!(IMPORTANT|WARNING|CAUTION|NOTE|TIP)]\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
                    .matchEntire(item)
                if (alertMatch != null) {
                    sb.appendLine("> [!${alertMatch.groupValues[1]}]")
                    alertMatch.groupValues[2].lines().forEach { sb.appendLine("> $it") }
                } else {
                    sb.appendLine(item)
                }
            }
            sb.appendLine()
        }

        if (plan.openQuestions.isNotEmpty()) {
            sb.appendLine("## 默认决策（Open Questions）")
            sb.appendLine("以下默认选择未经你确认——不同意直接在对话里说即可。")
            plan.openQuestions.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        sb.appendLine("## 概览（Overview）")
        sb.appendLine(plan.overview)
        sb.appendLine()

        // 业务逻辑段（CODE 模式必填，WORK 模式留空不渲染）
        if (plan.businessLogic.isNotBlank()) {
            sb.appendLine("## 业务逻辑（Business Logic）")
            sb.appendLine(plan.businessLogic)
            sb.appendLine()
        }

        if (plan.inScope.isNotEmpty()) {
            sb.appendLine("## 范围（Scope）")
            sb.appendLine("### 本次做（In Scope）")
            plan.inScope.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            if (plan.outScope.isNotEmpty()) {
                sb.appendLine("### 本次不做（Out of Scope）")
                plan.outScope.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
        }

        if (plan.keyDecisions.isNotEmpty()) {
            sb.appendLine("## 关键决策（Key Decisions）")
            plan.keyDecisions.forEach { d ->
                sb.appendLine("- **${d.question}** -> ${d.choice}")
                sb.appendLine("  - 理由：${d.rationale}")
                sb.appendLine("  - 备选：${d.alternatives}")
            }
            sb.appendLine()
        }

        if (plan.changes.isNotEmpty()) {
            sb.appendLine("## 改动清单（Changes）")
            plan.changes.groupBy { it.module }.forEach { (module, changes) ->
                sb.appendLine("### $module")
                changes.forEach { c ->
                    sb.appendLine("- **[${c.action}]** `${c.filePath}`：${c.description}")
                    sb.appendLine("  - 理由：${c.rationale}")
                }
            }
            sb.appendLine()
        }

        // 数据与参数段（可选，不涉及数据/参数的任务整段省略）
        if (plan.dataAndParams.isNotEmpty()) {
            sb.appendLine("## 数据与参数（Data & Parameters）")
            plan.dataAndParams.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (plan.risks.isNotEmpty()) {
            sb.appendLine("## 风险（Risks）")
            plan.risks.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        if (plan.successCriteria.isNotEmpty()) {
            sb.appendLine("## 成功标准（Success Criteria）")
            plan.successCriteria.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        plan.architecture?.let {
            sb.appendLine("## 架构（Architecture）")
            sb.appendLine("```mermaid")
            sb.appendLine(it)
            sb.appendLine("```")
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()

        // ===== Part 2: 子任务（Brief 批准时可见；Spec 批准后由 generate_spec 派生）=====
        sb.appendLine("## 子任务（Subtasks）")
        sb.appendLine()
        plan.subtasks.forEach { st ->
            sb.appendLine("### 子任务 ${st.index + 1}：${st.name}")
            sb.appendLine("**状态**：${st.status}")
            if (st.targetFiles.isNotEmpty()) sb.appendLine("**文件**：${st.targetFiles.joinToString(", ")}")
            if (st.dependsOn.isNotEmpty()) sb.appendLine("**依赖**：${st.dependsOn.joinToString(", ")}")
            if (st.parallelizable) sb.appendLine("**可并行**：是")
            sb.appendLine()
            sb.appendLine("#### 简述（Brief）")
            sb.appendLine(st.planDetail)
            sb.appendLine()
            if (!st.spec.isNullOrBlank()) {
                sb.appendLine("#### 执行清单（Spec）")
                sb.appendLine(st.spec)
                sb.appendLine()
            }
            if (st.specChanges.isNotEmpty()) {
                // 严格 append-only：修正留痕（完整旧 spec 文本保存在 plan.json 的审计记录里）
                sb.appendLine("#### Spec 修正记录（append-only）")
                st.specChanges.forEachIndexed { i, c ->
                    val why = c.reason.takeIf { it.isNotBlank() } ?: "首次生成"
                    sb.appendLine("- #${i + 1}：$why — ${c.timestamp}")
                }
                sb.appendLine()
            }
            if (st.decisions.isNotEmpty()) {
                sb.appendLine("#### 决策（Decisions）")
                st.decisions.forEach { d ->
                    sb.appendLine("- ${d.question} -> ${d.choice}（${d.rationale}）")
                }
                sb.appendLine()
            }
            sb.appendLine("#### 验证（Verification）")
            sb.appendLine(st.verification.command)
            if (st.verification.expected.isNotBlank()) sb.appendLine("预期结果：${st.verification.expected}")
            if (st.verification.expectStdoutContains.isNotEmpty())
                sb.appendLine("机器校验·输出必须包含：${st.verification.expectStdoutContains.joinToString(" | ")}")
            if (st.verification.expectStdoutNotContains.isNotEmpty())
                sb.appendLine("机器校验·输出不得包含：${st.verification.expectStdoutNotContains.joinToString(" | ")}")
            if (st.verificationChanges.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("##### 验证契约修正记录（append-only）")
                st.verificationChanges.forEachIndexed { i, c ->
                    sb.appendLine("- #${i + 1} `${c.oldSpec.command}` → `${c.newSpec.command}`：${c.reason}（${c.timestamp}）")
                }
            }
            st.verificationResult?.let { r ->
                sb.appendLine()
                sb.appendLine("#### 验证结果（Verification Result）")
                sb.appendLine("- 状态：${r.status}")
                sb.appendLine("- 证据：${r.evidence}")
                r.rootCause?.let { sb.appendLine("- 根因：$it") }
                r.remediation?.let { sb.appendLine("- 补救：$it") }
                r.commandExitCode?.let { sb.appendLine("- 命令退出码：$it") }
                if (r.machineMismatch) sb.appendLine("- ⚠ 机器判定与模型判定矛盾（异常态，已要求主代理告知用户）")
                r.commandOutput?.takeIf { it.isNotBlank() }?.let {
                    sb.appendLine("- 命令真实输出（截断；完整输出见 plan.json）：")
                    sb.appendLine("```")
                    sb.appendLine(it.take(800))
                    sb.appendLine("```")
                }
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }
}
