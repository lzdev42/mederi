package xyz.mederi.browser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 浏览器判定脑（BrowserBrain，对应 BrowserPilot 的 Analyst）。
 *
 * 完全无状态：每次调用均为独立单发（system + user 两条 message），不跨调用累积任何上下文。
 * 任务结束后实例销毁——操作员负责把历史摘要喂进来，大脑本身不记忆。
 *
 * 职责：
 * 1. 内容判定（judge）：分析 Operator 抓取的原始文本，提取结构化数据或做出布尔判定；
 * 2. 终态报告（generateFinalReport）：结合任务目标、执行轨迹及 Operator 汇总，生成一句话简报与详细总结，
 *    workingDir 非 null 时把详细报告真写入 `workingDir/reports/task_<ts>.md`。
 *
 * LLM 调用全部走 [BrowserLLMCaller]（由 [BrowserLLMHelper] 默认实现）——Brain 不持有
 * provider/client，判定与报告均为纯文本调用（无 image）。
 */
class BrowserBrain(
    private val llmCaller: BrowserLLMCaller,
    private val workingDir: File? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * 判定/提取网页内容（无状态单发：传入内容+指令，返回判定结果，不累积历史）。
     *
     * @param content 原始页面抓取文本
     * @param instruction 具体的判定指令或期望的 schema
     * @param recipeRules 挂载在任务上的配方规则（如 Skill 判定逻辑）
     * @param collectedItems 当前任务已收集的参考资料列表
     */
    suspend fun judge(
        content: String,
        instruction: String,
        recipeRules: String = "",
        collectedItems: List<BrowserCollectedItem> = emptyList()
    ): BrainResult {
        val sysPrompt = BrowserBrainPrompt.buildJudgeSystemPrompt()
        val userPrompt = BrowserBrainPrompt.buildJudgeUserPrompt(content, instruction, recipeRules, collectedItems)

        return try {
            val text = llmCaller.call(sysPrompt, userPrompt)
            val parsed = parseJsonToMap(text)

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
        recipeRules: String = "",
        collectedItems: List<BrowserCollectedItem> = emptyList()
    ): BrainResult {
        val sysPrompt = BrowserBrainPrompt.buildReportSystemPrompt()
        val userPrompt = BrowserBrainPrompt.buildReportUserPrompt(goal, taskHistory, rawOperatorMessage, recipeRules, collectedItems)

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

    /**
     * 严格解析 LLM 输出为键值 Map：仅接受完整 JSON 对象（可带 markdown 代码围栏），
     * 解析失败或出现非标量值（嵌套对象/数组）直接抛异常，由调用方走 success=false 通道。
     */
    private fun parseJsonToMap(text: String): Map<String, String> {
        val cleanJson = stripCodeFences(text.trim())
        val element = json.parseToJsonElement(cleanJson).jsonObject
        return element.entries.associate { (k, v) ->
            if (v !is JsonPrimitive) {
                throw SerializationException(
                    "Expected a scalar JSON value for key '$k' but got ${v::class.simpleName}"
                )
            }
            k to (v.contentOrNull ?: v.toString())
        }
    }

    /** 若 trim 后文本被 markdown 代码围栏（```...```）包裹，剥掉围栏与语言标记；否则原样返回。 */
    private fun stripCodeFences(trimmed: String): String {
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        val body = trimmed.removePrefix("```").removeSuffix("```").trim()
        return if (body.startsWith("json", ignoreCase = true)) body.drop(4).trim() else body
    }
}
