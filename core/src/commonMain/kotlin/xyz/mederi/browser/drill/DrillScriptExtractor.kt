package xyz.mederi.browser.drill

/**
 * 从技能 markdown 文本中抽取出干净的 DrillScript JSON。
 *
 * 技能文件的 `## Drill` 节里，DrillScript 包在 ```json ... ``` 代码块中，
 * 前后还有中文说明。本工具负责把那段纯 JSON 抠出来，供 DrillExecutor
 * 按【技能ID】加载时反序列化使用——这样执行器 AI 只需传技能ID字符串，
 * 无需自己拼（且可能拼错）整段 JSON。
 */
object DrillScriptExtractor {

    // 匹配 ```json ... ``` 代码块（大小写不敏感的语言标签，惰性匹配到最近的结束围栏）
    private val FENCED_JSON = Regex(
        """```\s*json\s*\n(.*?)\n\s*```""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    // 兜底：无语言标签的 ``` ... ``` 代码块
    private val FENCED_ANY = Regex(
        """```\s*\n(.*?)\n\s*```""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * 从给定 markdown 中抽取 Drill JSON 字符串。
     *
     * 优先取 ```json 围栏块；若没有语言标签，则取首个看起来像 JSON 对象的 ``` 围栏块；
     * 都没有时返回 null。
     */
    fun extractDrillJson(markdown: String): String? {
        if (markdown.isBlank()) return null

        FENCED_JSON.find(markdown)?.let { m ->
            val body = m.groupValues[1].trim()
            if (body.startsWith("{")) return body
        }

        FENCED_ANY.findAll(markdown).forEach { m ->
            val body = m.groupValues[1].trim()
            if (body.startsWith("{") && body.endsWith("}")) return body
        }

        return null
    }
}