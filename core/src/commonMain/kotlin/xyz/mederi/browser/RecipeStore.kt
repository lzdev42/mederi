package xyz.mederi.browser

import xyz.mederi.browser.drill.DrillScriptExtractor
import java.io.File

/**
 * 一次加载完成的配方（Recipe）。
 *
 * @param id skill ID（= 文件名，不含 .md 扩展名）
 * @param description frontmatter 中的 description，主管用于判断 skill 是否适合当前任务
 * @param operatorContent "## 操作规则" 节内容，供 Operator 注入执行提示词
 * @param judgeRules "## 判定规则" 节内容，只传给 BrowserBrain 判定，执行器不感知
 * @param drillScriptJson "## Drill" 节里的纯 DrillScript JSON（经 [DrillScriptExtractor] 抽取），无则 null
 */
data class LoadedRecipe(
    val id: String,
    val description: String,
    val operatorContent: String,
    val judgeRules: String,
    val drillScriptJson: String?
)

/**
 * 从 skills 目录按 skill ID 加载配方（Recipe）。
 *
 * 解析逻辑对齐 BrowserPilot 的 SkillParser：frontmatter（--- 之间 key:value）+ 按 "## " 切节，
 * 节名兼容中文（操作规则 / 判定规则）。Drill 节保留的是纯 JSON 字符串（经
 * [DrillScriptExtractor.extractDrillJson] 抽取），由调用方自行反序列化为 DrillScript。
 */
class RecipeStore(private val skillsDir: File) {

    /**
     * 按 skill ID 加载配方；文件不存在或解析失败返回 null（安全兜底，不抛）。
     */
    fun load(skillId: String): LoadedRecipe? {
        val file = File(skillsDir, "$skillId.md")
        if (!file.exists() || !file.isFile) return null
        return runCatching { parseRecipe(file, skillId) }.getOrNull()
    }

    /**
     * 解析 skillsDir 下所有 .md 配方，按 id 排序；目录不存在返回 emptyList。
     */
    fun listAll(): List<LoadedRecipe> {
        if (!skillsDir.exists() || !skillsDir.isDirectory) return emptyList()
        return skillsDir.listFiles { f -> f.isFile && f.extension == "md" }
            ?.mapNotNull { file -> runCatching { parseRecipe(file, file.nameWithoutExtension) }.getOrNull() }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    private fun parseRecipe(file: File, id: String): LoadedRecipe {
        val text = file.readText()
        val description = parseFrontmatter(text)["description"] ?: ""
        val sections = parseSections(text)

        return LoadedRecipe(
            id = id,
            description = description,
            operatorContent = sections["操作规则"]?.trim().orEmpty(),
            judgeRules = sections["判定规则"]?.trim().orEmpty(),
            drillScriptJson = sections["Drill"]?.let { DrillScriptExtractor.extractDrillJson(it) }
        )
    }

    /**
     * 解析 frontmatter（--- 之间的 key: value 对）。
     */
    private fun parseFrontmatter(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!text.trimStart().startsWith("---")) return result

        val afterFirst = text.trimStart().removePrefix("---")
        val endIdx = afterFirst.indexOf("---")
        if (endIdx < 0) return result

        val frontmatter = afterFirst.substring(0, endIdx)
        frontmatter.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    /**
     * 按 "## 标题" 切割正文，返回 标题 -> 内容 的映射；跳过 frontmatter 部分。
     */
    private fun parseSections(text: String): Map<String, String> {
        // 跳过 frontmatter
        val body = if (text.trimStart().startsWith("---")) {
            val afterFirst = text.trimStart().removePrefix("---")
            val endIdx = afterFirst.indexOf("---")
            if (endIdx >= 0) afterFirst.substring(endIdx + 3) else text
        } else {
            text
        }

        val sections = mutableMapOf<String, String>()
        val lines = body.lines()
        var currentSection: String? = null
        val currentContent = StringBuilder()

        for (line in lines) {
            if (line.startsWith("## ")) {
                // 保存上一节
                currentSection?.let { sections[it] = currentContent.toString() }
                currentSection = line.removePrefix("## ").trim()
                currentContent.clear()
            } else {
                if (currentSection != null) {
                    currentContent.appendLine(line)
                }
            }
        }
        // 保存最后一节
        currentSection?.let { sections[it] = currentContent.toString() }

        return sections
    }
}