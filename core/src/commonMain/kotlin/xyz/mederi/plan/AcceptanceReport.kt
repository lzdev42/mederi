package xyz.mederi.plan

internal fun appendAcceptanceCase(report: StringBuilder, case: AcceptanceCase) {
    report.appendLine()
    report.appendLine("#### ${case.id} · ${case.objective}")
    report.appendLine()
    appendCaseProcedure(report, case)
    report.appendLine("- **预期值**　${case.expected}")
    report.appendLine("- **能发现的问题**　${case.detects}")
}

private fun appendCaseProcedure(report: StringBuilder, case: AcceptanceCase) {
    report.appendLine("- **前置状态**　${case.setup}")
    report.appendLine("- **输入**　${case.input}")
    report.appendLine("- **操作**　${case.steps}")
    report.appendLine("- **观察位置**　${case.observationSource}")
    if (case.manual) report.appendLine("- **验收方式**　人工签认")
}

internal fun buildAcceptanceReport(plan: Plan): String = buildString {
    appendLine("# 验收证据报告 · ${plan.title}")
    appendLine()
    appendLine("计划版本 `${plan.id}` · ${plan.status}")
    plan.replacesPlanId?.let { appendLine("替代计划版本：`$it`") }
    appendLine()
    if (plan.summary.isNotBlank()) {
        appendLine(plan.summary)
        appendLine()
    }
    plan.subtasks.forEach { task ->
        appendLine("## ${if (task.overallAcceptance) "整体验收" else "任务 ${task.index + 1}"} · ${task.name}")
        appendLine()
        appendLine(task.planDetail)
        appendLine()
        val taskState = when (task.status) {
            SubtaskStatus.PENDING -> "待执行"
            SubtaskStatus.IN_PROGRESS -> "进行中"
            SubtaskStatus.COMPLETED -> if (task.acceptanceCases.isEmpty()) "旧版任务已结束，缺少结构化证据" else "已完成"
            SubtaskStatus.FAILED -> "验收未通过"
        }
        appendLine("**任务状态**　$taskState")
        appendLine()
        if (task.executorTouchedFiles.isNotEmpty()) {
            appendLine("执行器记录的改动文件：${task.executorTouchedFiles.joinToString()}")
            appendLine()
        }
        task.verificationResult?.let { notes ->
            appendLine("执行说明（代理提供）")
            appendLine()
            if (notes.evidence.isNotBlank()) appendLine("> " + notes.evidence.lines().joinToString("\n> "))
            notes.rootCause?.let { appendLine("> 归因：$it") }
            notes.remediation?.let { appendLine("> 处理：$it") }
            if (notes.machineMismatch) appendLine("> 异常：自动观测与代理判断有分歧，任务未完成。")
            appendLine()
        }
        if (task.acceptanceCases.isEmpty()) {
            appendLine("证据不足 · 此任务没有结构化用例和可对照的实测值。")
            appendLine()
        } else {
            task.acceptanceCases.forEach { case ->
                appendLine("### ${case.id} · ${case.objective}")
                appendLine()
                val run = task.acceptanceRuns.lastOrNull { attempt -> attempt.observations.any { it.caseId == case.id } }
                val observation = run?.observations?.find { it.caseId == case.id }
                appendLine("- **预期值**　${case.expected}")
                if (observation == null) {
                    appendLine("- **判定**　未验证")
                } else {
                    val actual = observation.actual?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "无"
                    appendLine("- **实测值**　$actual")
                    val why = when (observation.verdict) {
                        AcceptanceVerdict.PASS -> "实测值与批准的预期值相等。"
                        AcceptanceVerdict.FAIL -> if (run.exitCode != null && run.exitCode != 0) run.detail
                            else "实测值与批准的预期值不符。"
                        AcceptanceVerdict.INSUFFICIENT_EVIDENCE -> "运行未提供此用例的有效观测值。"
                        AcceptanceVerdict.BLOCKED -> run.detail
                    }
                    appendLine("- **判定**　**${observation.verdict}** · $why")
                    val attemptNumber = task.acceptanceRuns.indexOf(run) + 1
                    appendLine("- **证据**　第 $attemptNumber 次运行 [^run-${run.id}]")
                    run.confirmedBy?.let { appendLine("- **人工签认**　$it") }
                }
                appendLine()
                appendCaseProcedure(this, case)
                appendLine("- **能发现的问题**　${case.detects}")
                appendLine()
            }
            if (task.verification.command.isNotBlank() || task.acceptanceRuns.isNotEmpty()) {
                appendLine("### 取证与运行记录")
                appendLine()
            }
            if (task.verification.command.isNotBlank()) appendLine("- **取证命令**　${task.verification.command}")
            task.acceptanceFiles.forEach { (path, digest) -> appendLine("- **批准的取证文件**　`$path` · SHA-256 `$digest`") }
            task.acceptanceRuns.forEachIndexed { index, run ->
                appendLine()
                val marker = if (task.acceptanceCases.any { case ->
                    task.acceptanceRuns.lastOrNull { attempt -> attempt.observations.any { it.caseId == case.id } } == run
                }) "[^run-${run.id}]: " else ""
                appendLine("${marker}第 ${index + 1} 次运行 · ${run.timestamp} · `${run.id}` · 退出码 ${run.exitCode ?: "无"} · ${run.detail}")
                val indent = if (marker.isEmpty()) "" else "    "
                run.confirmedBy?.let { appendLine("${indent}人工签认：$it") }
                run.observations.forEach { item ->
                    val expected = kotlinx.serialization.json.JsonPrimitive(item.expected)
                    val actual = item.actual?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "无"
                    appendLine("$indent- ${item.caseId} · ${item.verdict} · 预期 $expected · 实测 $actual")
                }
                if (run.sourceDigests.isNotEmpty()) {
                    appendLine("${indent}运行时声明的改动文件与指纹：")
                    run.sourceDigests.forEach { (path, digest) -> appendLine("$indent- `$path` · `$digest`") }
                }
                if (run.executionNotes.isNotBlank()) {
                    appendLine("${indent}过程说明（代理提供）：")
                    run.executionNotes.lines().forEach { appendLine("$indent> $it") }
                }
                if (run.output.isNotBlank()) {
                    appendLine()
                    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(run.output).maxOfOrNull { it.value.length } ?: 0) + 1))
                    appendLine("$indent$fence")
                    run.output.lines().forEach { appendLine("$indent$it") }
                    appendLine("$indent$fence")
                }
            }
            appendLine()
        }
    }
}
