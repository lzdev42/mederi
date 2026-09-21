package xyz.mederi.skills

/**
 * 解析结果；isValid=false 表示没有合法 `---` frontmatter 边界。
 */
data class ParsedFrontmatter(
    val name: String?,
    val description: String?,
    val license: String?,
    val compatibility: String?,
    val metadata: Map<String, String>?,
    val allowedTools: String?,
    val isValid: Boolean,
)

/**
 * Mederi 自研 frontmatter 解析器（Koog discoverSkills 解析的超集）。
 *
 * 相比 Koog 前端解析，额外支持：
 * - YAML 块标量 `>`（折叠）/ `|`（字面量），含 chomping 指示符 `-` / `+`
 * - 空值 + 缩进块（`description:` 后跟缩进行，按折叠块处理）
 * - 引号去包裹、metadata 子键、注释与空行忽略
 *
 * 纯 Kotlin，不依赖任何第三方库，可跨平台。
 */
object SkillFrontmatterParser {

    /** 块标量指示符：`>` / `|` 可选跟 `-`（strip chomping）或 `+`（keep chomping）。 */
    private val BLOCK_INDICATOR = Regex("^[>|][-+]?$")

    fun parse(content: String): ParsedFrontmatter {
        val lines = content.lines()
        // 1) 边界：首行必须是 `---`，且从第 2 行起找到第一个 `---` 作为闭合行
        if (lines.isEmpty() || lines[0].trim() != "---") return invalid()
        val closeIndex = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return invalid()

        // 2) 逐行状态机
        var blockKey: String? = null
        var blockLines = mutableListOf<String>()
        var blockIsLiteral = false
        var blockChompStrip = false
        var inMetadata = false
        val topLevel = linkedMapOf<String, String>()
        val metadataMap = linkedMapOf<String, String>()

        fun settleBlock() {
            val key = blockKey ?: return
            // 折叠块：空行处折叠为单个空格（先剔除空行再以单空格连接）；
            // 字面量块：空行原样保留（按换行连接）。
            val text = if (blockIsLiteral) {
                blockLines.joinToString("\n")
            } else {
                blockLines.filter { it.isNotEmpty() }.joinToString(" ")
            }
            topLevel[key] = if (blockChompStrip) text.trimEnd() else text.trimEnd()
            blockKey = null
        }

        for (rawLine in lines.subList(1, closeIndex)) {
            // 3a) 空行跳过（正在收集块标量时空行保留进 blockLines）；注释行丢弃
            if (rawLine.isBlank()) {
                if (blockKey != null) blockLines.add("")
                continue
            }
            if (rawLine.trimStart().startsWith("#")) continue

            // 3b) 正在收集块标量：缩进行 → 加入 blockLines；非缩进行 → 先结算块，再按普通行处理
            if (blockKey != null) {
                if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                    blockLines.add(rawLine.trimStart().trimEnd())
                    continue
                }
                settleBlock()
            }

            // 3c) 普通行（非缩进顶层行）
            val isIndented = rawLine.startsWith(" ") || rawLine.startsWith("\t")
            if (inMetadata && isIndented) {
                val colonIndex = rawLine.indexOf(':')
                if (colonIndex > 0) {
                    val key = rawLine.substring(0, colonIndex).trim()
                    val value = unquote(rawLine.substring(colonIndex + 1).trim())
                    if (key.isNotBlank() && value.isNotBlank()) metadataMap[key] = value
                }
                continue
            }
            inMetadata = false

            val colonIndex = rawLine.indexOf(':')
            if (colonIndex <= 0) continue
            val key = rawLine.substring(0, colonIndex).trim()
            val rawValue = rawLine.substring(colonIndex + 1).trim()

            if (key == "metadata") {
                inMetadata = true
                continue
            }
            if (BLOCK_INDICATOR.matches(rawValue)) {
                blockKey = key
                blockLines = mutableListOf()
                blockIsLiteral = rawValue.startsWith("|")
                blockChompStrip = rawValue.endsWith("-")
                continue
            }
            if (rawValue.isEmpty()) {
                // 空值 + 缩进块（YAML plain block）：按折叠块处理
                blockKey = key
                blockLines = mutableListOf()
                blockIsLiteral = false
                blockChompStrip = false
                continue
            }
            topLevel[key] = unquote(rawValue)
        }

        // 4) yamlLines 全部处理完仍有未结算块 → 结算
        if (blockKey != null) settleBlock()

        // 5) 产出；name/description 空白视为 null（与 Koog 语义一致）
        return ParsedFrontmatter(
            name = topLevel["name"]?.takeIf { it.isNotBlank() },
            description = topLevel["description"]?.takeIf { it.isNotBlank() },
            license = topLevel["license"],
            compatibility = topLevel["compatibility"],
            metadata = metadataMap.takeIf { it.isNotEmpty() },
            allowedTools = topLevel["allowed-tools"],
            isValid = true,
        )
    }

    private fun unquote(value: String): String =
        value.removeSurrounding("\"").removeSurrounding("'")

    private fun invalid(): ParsedFrontmatter = ParsedFrontmatter(
        name = null,
        description = null,
        license = null,
        compatibility = null,
        metadata = null,
        allowedTools = null,
        isValid = false,
    )
}
