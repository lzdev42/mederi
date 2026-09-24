package xyz.mederi.browser

/**
 * BrowserBrain 的提示词模板与构建器。
 */
object BrowserBrainPrompt {

    /**
     * 内容判定系统提示词。
     * 仅包含角色定义、输出约束与判定纪律，不包含特定业务逻辑（业务逻辑由 recipeRules / instruction 提供）。
     */
    fun buildJudgeSystemPrompt(): String = """
You are BrowserBrain, the analytical brain of the browser automation system.
Your job is to analyze raw content extracted from web pages and make precise, objective judgments or data extractions based on the given rules.

# Output Contract
Respond with STRICT JSON only — no markdown fences, no conversational filler, no trailing text.
Unless a specific JSON schema is requested in the instruction, respond with:
{
  "match": true,
  "reason": "Clear explanation citing specific facts from the content"
}

# Rules
- Base your judgment STRICTLY on the provided content. Do not hallucinate or assume facts not present.
- If the content does not provide enough evidence, state that in "reason" and set match to false.
- You do not operate the browser directly; you only analyze content and guide next steps.
""".trimIndent()

    /**
     * 组装判定用户提示词。
     */
    fun buildJudgeUserPrompt(
        content: String,
        instruction: String,
        recipeRules: String = ""
    ): String = buildString {
        if (recipeRules.isNotBlank()) {
            append("### [判定规则与背景配置 / Recipe Rules]：\n")
            append(recipeRules.trim())
            append("\n\n")
        }
        append("### [待分析页面内容 / Raw Content]：\n")
        append(content.trim().ifBlank { "(Empty content)" })
        append("\n\n")
        append("### [判定要求与输出格式 / Instruction]：\n")
        val effectiveInstruction = if (instruction.isBlank()) {
            "请判断待分析内容是否符合要求，并以 {\"match\": boolean, \"reason\": string} 格式返回。"
        } else {
            instruction.trim()
        }
        append(effectiveInstruction)
    }

    /**
     * 终态报告生成系统提示词。
     */
    fun buildReportSystemPrompt(): String = """
You are BrowserBrain, summarizing the results of a completed browser automation task for the supervisor.
Review the task goal, execution history, and findings, then produce:
1. A concise one-sentence executive summary for the supervisor.
2. A structured JSON response containing the summary, status, and any key findings.

# Output Contract
Respond with STRICT JSON only — no markdown fences:
{
  "summary": "One concise sentence summarizing the outcome for the supervisor",
  "goal_achieved": true,
  "details": "A clean, structured summary of findings, data extracted, or actions taken"
}
""".trimIndent()

    /**
     * 组装终态报告用户提示词。
     */
    fun buildReportUserPrompt(
        taskGoal: String,
        taskHistory: String,
        rawOperatorMessage: String,
        recipeRules: String = ""
    ): String = buildString {
        append("### [任务原始目标 / Task Goal]：\n")
        append(taskGoal.trim())
        append("\n\n")
        if (recipeRules.isNotBlank()) {
            append("### [配方规则 / Recipe Rules]：\n")
            append(recipeRules.trim())
            append("\n\n")
        }
        if (rawOperatorMessage.isNotBlank()) {
            append("### [操作员完成信息 / Operator Output]：\n")
            append(rawOperatorMessage.trim())
            append("\n\n")
        }
        append("### [执行轨迹日志 / Execution History]：\n")
        append(taskHistory.trim().ifBlank { "(No step history recorded)" })
        append("\n\n")
        append("请根据以上信息，生成一句话简报及结构化详情。")
    }
}
