package xyz.mederi.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 浏览器判定脑（BrowserBrain，对应 BrowserPilot 的 Analyst）。
 *
 * 职责：
 * 1. 内容判定（judge）：分析 Operator 抓取的原始文本，提取结构化数据或做出布尔判定；
 * 2. 终态报告（generateFinalReport）：结合任务目标、执行轨迹及 Operator 汇总，生成一句话简报与详细总结，
 *    workingDir 非 null 时把详细报告真写入 `workingDir/reports/task_<ts>.md`；
 * 3. 记忆灵活性：[continuousMemory] 控制单次判定是否跨轮次保留对话历史，随时可以在一次性与持续记忆间切换。
 *
 * LLM 调用全部走 [BrowserLLMCaller]（由 [BrowserLLMHelper] 默认实现）——Brain 不持有
 * provider/client，判定与报告均为纯文本调用（无 image）。
 */
class BrowserBrain(
    private val llmCaller: BrowserLLMCaller,
    private val workingDir: File? = null,
    /**
     * 持续记忆开关：
     * false: 每次调用均为全新提示词（无历史堆叠，默认，轻量高抗噪）
     * true: 在生命周期内累积每次判定记录（跨步骤持续上下文）
     */
    private val continuousMemory: Boolean = false
) {

    // 持续记忆存储：若 continuousMemory = true，历史记录累积在此
    private val memoryLog = mutableListOf<String>()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * 重置记忆。
     */
    fun resetMemory() {
        memoryLog.clear()
    }

    /**
     * 判定/提取网页内容。
     *
     * @param content 原始页面抓取文本
     * @param instruction 具体的判定指令或期望的 schema
     * @param recipeRules 挂载在任务上的配方规则（如 Skill 判定逻辑）
     */
    suspend fun judge(
        content: String,
        instruction: String,
        recipeRules: String = ""
    ): BrainResult {
        val sysPrompt = BrowserBrainPrompt.buildJudgeSystemPrompt()
        val baseUserPrompt = BrowserBrainPrompt.buildJudgeUserPrompt(content, instruction, recipeRules)

        val fullUserPrompt = if (continuousMemory && memoryLog.isNotEmpty()) {
            buildString {
                append("<prior_judgments>\n")
                append(memoryLog.joinToString("\n---\n"))
                append("\n</prior_judgments>\n\n")
                append(baseUserPrompt)
            }
        } else {
            baseUserPrompt
        }

        return try {
            val text = llmCaller.call(sysPrompt, fullUserPrompt)
            val parsed = parseJsonToMap(text)

            if (continuousMemory) {
                memoryLog.add("Instruction: $instruction\nContent: ${content.take(300)}\nResult: $text")
            }

            BrainResult(
                success = true,
                data = parsed,
                text = text
            )
        } catch (e: Exception) {
            BrainResult(
                success = false,
                text = "BrowserBrain judge error: ${e.message}"
            )
        }
    }

    /**
     * 任务完成时生成终态简报与详细报告；workingDir 非 null 时把报告真写入磁盘。
     */
    suspend fun generateFinalReport(
        taskHistory: String,
        goal: String,
        rawOperatorMessage: String,
        recipeRules: String = ""
    ): BrainResult {
        val sysPrompt = BrowserBrainPrompt.buildReportSystemPrompt()
        val userPrompt = BrowserBrainPrompt.buildReportUserPrompt(goal, taskHistory, rawOperatorMessage, recipeRules)

        return try {
            val text = llmCaller.call(sysPrompt, userPrompt)
            val parsed = parseJsonToMap(text)

            val filesWritten = mutableListOf<String>()
            if (workingDir != null) {
                val reportPath = writeReportFile(workingDir, goal, text, parsed)
                if (reportPath != null) {
                    filesWritten.add(reportPath)
                }
            }

            BrainResult(
                success = true,
                data = parsed,
                text = text,
                filesWritten = filesWritten
            )
        } catch (e: Exception) {
            BrainResult(
                success = false,
                text = "BrowserBrain report error: ${e.message}"
            )
        }
    }

    private fun writeReportFile(workingDir: File, goal: String, rawText: String, parsed: Map<String, String>): String? {
        return runCatching {
            val dir = File(workingDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            val timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val file = File(dir, "task_${timestamp}.md")
            val summary = parsed["summary"] ?: goal
            val details = parsed["details"] ?: rawText
            file.writeText("""
                # 浏览器自动化任务报告
                - **目标**: $goal
                - **生成时间**: $timestamp
                - **概要**: $summary
                
                ## 详细记录
                $details
            """.trimIndent())
            file.path
        }.getOrNull()
    }

    private fun parseJsonToMap(text: String): Map<String, String> {
        return runCatching {
            val cleanJson = extractJsonSubstring(text)
            val element = json.parseToJsonElement(cleanJson).jsonObject
            element.entries.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: v.toString())
            }
        }.getOrElse { emptyMap() }
    }

    private fun extractJsonSubstring(text: String): String {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            trimmed
        }
    }
}