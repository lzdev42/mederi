package xyz.mederi.ui.components.command

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Zap
import xyz.mederi.core.contract.models.SkillItem

/**
 * 快捷命令分类分组。
 * 可按需扩展更多分类（如 COMMAND、PLUGIN 等）。
 */
enum class CommandGroup(val title: String, val order: Int) {
    COMMAND("命令", 1),
    PLUGIN("插件", 2);
}

/**
 * 快捷命令单项 Model。
 *
 * 【开发者配置约定与使用模板】：
 * 若需在代码中配置新命令，只需在 [SlashCommandRegistry.BUILTIN_COMMANDS] 列表中
 * 追加 [SlashCommandItem] 数据项，快捷菜单 UI 与搜索联想将全自动生效，无需改动任何 UI 代码。
 *
 * 示例模板：
 * ```kotlin
 * SlashCommandItem(
 *     id = "spec",                             // 命令唯一标识
 *     label = "spec",                          // 显示标题
 *     group = CommandGroup.COMMAND,            // 所属分组：CommandGroup.COMMAND 或 CommandGroup.PLUGIN
 *     description = "根据需求细化规范文档",     // 功能说明描述（单行展示并截断）
 *     icon = FeatherIcons.BookOpen,            // 图标 ImageVector（可选，如 Feather 图标）
 *     iconTint = Color(0xFF4C8DFF),            // 图标着色
 *     iconBadge = null,                        // 可选文字徽标（例如类似 "AI" 方块标签）
 *     iconBadgeBg = null,                      // 可选文字徽标背景色
 *     insertText = "/spec ",                   // 选中后插入输入框的文本
 *     keywords = listOf("spec", "规范"),       // 检索匹配关键词（拼音/英文/中文别名）
 *     isEnabled = true                         // 启用开关
 * )
 * ```
 *
 * @param id 唯一标识（例如 "clear", "compact", "plan", "skill"）
 * @param label 显示标题（例如 "clear", "compact"）
 * @param group 所属分组（[CommandGroup.COMMAND] 或 [CommandGroup.PLUGIN]）
 * @param description 功能说明描述（单行展示并截断）
 * @param icon 图标 ImageVector（可选，如 Feather 图标）
 * @param iconTint 图标颜色（可选）
 * @param iconBadge 图标文字徽标（例如 "AI" 方块标签，非空时优先渲染）
 * @param iconBadgeBg 文字徽标背景色
 * @param insertText 选中后插入输入框的文本（默认 "/${id} "）
 * @param keywords 检索匹配关键词（拼音、英文或中文别名，支持模糊查找）
 * @param isEnabled 启用开关
 * @param customAction 自定义点击动作（可选；若提供，选中时优先调用）
 */
data class SlashCommandItem(
    val id: String,
    val label: String,
    val group: CommandGroup,
    val description: String,
    val icon: ImageVector? = null,
    val iconTint: Color = Color.Unspecified,
    val iconBadge: String? = null,
    val iconBadgeBg: Color? = null,
    val insertText: String = "/$id ",
    val keywords: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val customAction: (() -> Unit)? = null,
)

/**
 * 快捷命令检索上下文（支持在文本开头、中间或末尾触发联想）。
 * @param query 提取出的检索关键词
 * @param range 触发联想的文本替换区间（用于回车补全时精准替换当前词）
 * @param isSlashMode 是否为显式斜杠命令模式（false 为仅开头首词的输入法前缀模式）
 */
data class CommandQuery(
    val query: String,
    val range: IntRange,
    val isSlashMode: Boolean
)

/**
 * 快捷命令 Token 范围（用于原子化删除整条命令）。
 */
data class CommandTokenRange(
    val start: Int,
    val end: Int,
    val token: String
)

/**
 * 快捷命令集中注册表与查询工具。
 */
object SlashCommandRegistry {

    /**
     * 内置原生系统命令列表（真实功能命令，杜绝假数据）。
     *
     * 【添加命令方式】：
     * 直接在下方列表中追加一条 [SlashCommandItem]（参考上方类注释模板）即可。
     */
    val BUILTIN_COMMANDS: List<SlashCommandItem> = listOf(
        SlashCommandItem(
            id = "clear",
            label = "clear",
            group = CommandGroup.COMMAND,
            description = "清空当前输入框草稿与暂存附件",
            icon = FeatherIcons.Trash2,
            iconTint = Color(0xFFE5534B),
            insertText = "/clear ",
            keywords = listOf("clear", "清空", "qk", "clean", "c")
        ),
        SlashCommandItem(
            id = "compact",
            label = "compact",
            group = CommandGroup.COMMAND,
            description = "手动压缩当前会话历史上下文，释放 Token 空间",
            icon = FeatherIcons.Minimize2,
            iconTint = Color(0xFFE69F00),
            insertText = "/compact ",
            keywords = listOf("compact", "压缩", "yasuo", "token", "c")
        ),
        SlashCommandItem(
            id = "plan",
            label = "plan",
            group = CommandGroup.COMMAND,
            description = "优先规划任务的执行方案，用户确认后再执行",
            icon = FeatherIcons.GitBranch,
            iconTint = Color(0xFF9D67EA),
            insertText = "/plan ",
            keywords = listOf("plan", "计划", "规划", "guihua", "jihua", "p")
        ),
        SlashCommandItem(
            id = "skill",
            label = "skill",
            group = CommandGroup.COMMAND,
            description = "调用已安装的扩展技能（SKILL.md）",
            icon = FeatherIcons.Zap,
            iconTint = Color(0xFF10B981),
            insertText = "/skill ",
            keywords = listOf("skill", "技能", "插件", "jn", "s")
        )
    )

    /**
     * 将动态安装的 Skills 转换为 SlashCommandItem 插件项。
     */
    fun fromSkills(skills: List<SkillItem>): List<SlashCommandItem> {
        return skills.map { skill ->
            SlashCommandItem(
                id = skill.name,
                label = skill.name,
                group = CommandGroup.PLUGIN,
                description = skill.description,
                icon = FeatherIcons.Zap,
                iconTint = Color(0xFF10B981),
                insertText = "/skill ${skill.name} ",
                keywords = listOf(skill.name, "skill")
            )
        }
    }

    /**
     * 获取全量可用命令（内置命令 + 动态 Skill 插件）。
     */
    fun getAllCommands(skills: List<SkillItem> = emptyList()): List<SlashCommandItem> {
        val skillItems = fromSkills(skills)
        return (BUILTIN_COMMANDS + skillItems).filter { it.isEnabled }
    }

    /**
     * 根据搜索关键词过滤命令列表。
     * 支持以输入法联想模式进行前缀与关键词匹配，前缀匹配项排在前列。
     *
     * @param query 搜索关键词（支持以 "/" 开头或纯字母/中文）
     * @param prefixOnly 是否仅启用输入法式前缀匹配（当用户未输入 "/" 时启用，输入法联想模式，避免常规英文词汇误触）
     */
    fun filter(
        items: List<SlashCommandItem>,
        query: String,
        prefixOnly: Boolean = false
    ): List<SlashCommandItem> {
        val q = query.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return items
        return items.filter { item ->
            val idLower = item.id.lowercase()
            val labelLower = item.label.lowercase()
            if (prefixOnly) {
                idLower.startsWith(q) ||
                labelLower.startsWith(q) ||
                item.keywords.any { it.lowercase().startsWith(q) }
            } else {
                idLower.startsWith(q) ||
                labelLower.startsWith(q) ||
                idLower.contains(q) ||
                labelLower.contains(q) ||
                item.description.lowercase().contains(q) ||
                item.keywords.any { it.lowercase().startsWith(q) || it.lowercase().contains(q) }
            }
        }.sortedWith(compareBy(
            { !it.id.lowercase().startsWith(q) && !it.label.lowercase().startsWith(q) },
            { it.group.order },
            { it.id }
        ))
    }

    /**
     * 在光标处分析待联想的命令上下文。
     *
     * 支持触发场景：
     * 1. 文本开头输入纯字符（如 "c"）或 "/" 开头命令（如 "/c"）；
     * 2. 文本任意位置（包括句子末尾），在空格或行首之后的 "/" 命令（如 "请分析 /s"）；
     * 3. 冲突规避：普通单词内斜杠（如 "a/b"、"http://..."）及显式转义（如 "\/skill"）绝不触发联想。
     *
     * @param text 当前输入框完整文本
     * @param cursor 当前光标位置（0..text.length）
     */
    fun findCommandQueryAtCursor(text: String, cursor: Int): CommandQuery? {
        if (cursor <= 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)

        // 0. 转义检查：以 \/ 开头或在光标前为 \/ 的属于转义，抑制快捷命令弹窗
        if (prefix.endsWith("\\/") || Regex("""\\/[^\s]*$""").containsMatchIn(prefix)) {
            return null
        }

        // 1. 任意位置的 "/" 命令输入（必须位于开头或紧跟空白字符，例如 "text /s" 或 "/s"）
        val slashMatch = Regex("""(?:^|\s)(/[^\s]*)$""").find(prefix)
        if (slashMatch != null) {
            val token = slashMatch.groupValues[1]
            val tokenStart = cursor - token.length
            return CommandQuery(
                query = token.removePrefix("/"),
                range = tokenStart until cursor,
                isSlashMode = true
            )
        }

        // 2. 仅在文本开头（首个单词）时支持无斜杠的输入法式前缀联想（如开头输入 "c" 匹配 clear/compact）
        // 包含 / 或 \ 的词不走纯字联想，且文本中间普通单词（如 "hello can you"）不误触
        val trimmedStart = prefix.trimStart()
        if (trimmedStart.isNotEmpty() && !trimmedStart.contains(' ') && !trimmedStart.contains('\n')
            && !trimmedStart.contains('/') && !trimmedStart.contains('\\')) {
            val tokenStart = prefix.indexOf(trimmedStart)
            return CommandQuery(
                query = trimmedStart,
                range = tokenStart until cursor,
                isSlashMode = false
            )
        }

        return null
    }

    /**
     * 检测光标当前是否处于某个完整命令 Token 内部或末尾。
     * 支持文本开头、中间或末尾的完整命令 Token（用于退格键 Backspace 原子整词删除）。
     *
     * @param text 当前输入框完整文本
     * @param cursor 当前光标位置
     * @param registeredCommands 当前已注册的命令列表
     */
    fun findCommandTokenAtCursor(
        text: String,
        cursor: Int,
        registeredCommands: List<SlashCommandItem> = BUILTIN_COMMANDS
    ): CommandTokenRange? {
        if (cursor <= 0 || text.isEmpty()) return null

        // 1. 优先匹配已注册命令项（按长度降序，长匹配优先）
        for (item in registeredCommands.sortedByDescending { it.insertText.length }) {
            val candidates = listOf(item.insertText, item.insertText.trim())
            for (candidate in candidates) {
                var searchFrom = 0
                while (searchFrom < text.length) {
                    val idx = text.indexOf(candidate, searchFrom)
                    if (idx < 0) break
                    // 命令必须位于开头或紧随空白字符之后，且不能被 \ 转义
                    val isEscaped = idx > 0 && text[idx - 1] == '\\'
                    val isAtWordBoundary = (idx == 0 || text[idx - 1].isWhitespace()) && !isEscaped
                    if (isAtWordBoundary) {
                        val tokenEnd = idx + candidate.length
                        // 光标必须位于该命令 Token 范围之内（idx + 1 .. tokenEnd）
                        if (cursor in (idx + 1)..tokenEnd) {
                            return CommandTokenRange(idx, tokenEnd, candidate)
                        }
                    }
                    searchFrom = idx + 1
                }
            }
        }

        // 2. 通用兜底：匹配类似 "/command " 带有后置空格且以空白/开头引导的语法 Token
        val genericMatches = Regex("""(?:^|(?<=\s))(/[a-zA-Z0-9_\-\u4e00-\u9fa5]+(?:\s+[a-zA-Z0-9_\-\u4e00-\u9fa5]+)?\s+)""").findAll(text)
        for (match in genericMatches) {
            val tokenStart = match.range.first
            val tokenEnd = match.range.last + 1
            if (cursor in (tokenStart + 1)..tokenEnd) {
                return CommandTokenRange(tokenStart, tokenEnd, match.value)
            }
        }

        return null
    }

    /**
     * 兼容方法：检测开头命令 Token。
     */
    fun findCommandTokenAtStart(
        text: String,
        registeredCommands: List<SlashCommandItem> = BUILTIN_COMMANDS
    ): CommandTokenRange? {
        return findCommandTokenAtCursor(text, text.length.coerceAtMost(if (text.startsWith("/")) 100 else 0), registeredCommands)
    }

    /**
     * 将命令按 Group 顺序进行分组归类。
     */
    fun groupItems(items: List<SlashCommandItem>): Map<CommandGroup, List<SlashCommandItem>> {
        // KMP 兼容：Map.toSortedMap 仅 JVM 可用（wasmJs 无此扩展），用 entries 排序 + associate（LinkedHashMap 保持序）
        return items
            .groupBy { it.group }
            .entries
            .sortedBy { it.key.order }
            .associate { it.key to it.value }
    }
}

