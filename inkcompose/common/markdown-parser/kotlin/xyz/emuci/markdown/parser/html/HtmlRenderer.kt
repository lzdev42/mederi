package xyz.emuci.markdown.parser.html

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import xyz.emuci.markdown.parser.flavour.MarkdownFlavour
import kotlin.math.roundToInt

/**
 * 将 Markdown AST 节点树输出为标准 HTML 的渲染器。
 *
 * 实现 [NodeVisitor] 接口，递归遍历 AST 生成 HTML 字符串。
 * 支持服务端 SSR 和 HTML 导出场景。
 *
 * ## 用法
 *
 * ```kotlin
 * val parser = MarkdownParser()
 * val document = parser.parse("# Hello\n\nWorld")
 * val html = HtmlRenderer.render(document)
 * // <h1 id="hello">Hello</h1>\n<p>World</p>\n
 * ```
 *
 * ## 配置
 *
 * ```kotlin
 * val renderer = HtmlRenderer(
 *     softBreak = "<br />\n",  // 软换行输出 <br>
 *     hardBreak = "<br />\n",  // 硬换行输出 <br>
 *     escapeHtml = true,       // 转义原始 HTML
 *     xhtml = true,            // XHTML 自闭合标签
 * )
 * val html = renderer.render(document)
 * ```
 *
 * 参考 JetBrains Markdown 的 `HtmlGenerator(src, parsedTree, flavour).generateHtml()` API 设计。
 */
data class HtmlDirectiveBlockSnapshot(
    val tagName: String,
    val args: Map<String, String>,
    val hasContent: Boolean,
)

data class HtmlDirectiveInlineSnapshot(
    val tagName: String,
    val args: Map<String, String>,
    val alternateText: String,
)

class HtmlRenderer(
    /** 软换行输出内容，默认 "\n" */
    val softBreak: String = "\n",
    /** 硬换行标签 */
    val hardBreak: String = "<br />\n",
    /** 是否转义原始 HTML 内容 */
    val escapeHtml: Boolean = false,
    /** 是否使用 XHTML 风格自闭合标签 */
    val xhtml: Boolean = true,
    /** 自定义块级 directive HTML fallback，返回 null 则走默认输出 */
    val directiveBlockFallback: ((HtmlDirectiveBlockSnapshot) -> String?)? = null,
    /** 自定义行内 directive HTML fallback，返回 null 则走默认输出 */
    val directiveInlineFallback: ((HtmlDirectiveInlineSnapshot) -> String?)? = null,
    /** 渲染增强 SPI（数学 MathML / 代码高亮 / 图表 bootstrap），默认全部降级 */
    val hooks: HtmlExportHooks = HtmlExportHooks(),
    /** 资源需求收集器，传入后渲染过程填充 hasMath/hasDiagram/hasVerticalText 等 */
    val requirements: HtmlExportRequirements? = null,
) : NodeVisitor<Unit> {

    private val sb = StringBuilder()
    /** 当前 render(document) 的根文档（TOC 两遍扫描用） */
    private var rootDocument: Document? = null
    /** 惰性收集的全文档 heading 列表（TOC 两遍扫描用） */
    private var collectedHeadings: List<TocHeadingInfo>? = null

    /**
     * 渲染 Document AST 为 HTML 字符串。
     */
    fun render(document: Document): String {
        sb.clear()
        rootDocument = document
        collectedHeadings = null
        document.accept(this)
        return sb.toString()
    }

    // ─────────────── 辅助方法 ───────────────

    private fun tag(name: String, attrs: Map<String, String?> = emptyMap(), selfClosing: Boolean = false) {
        sb.append('<').append(name)
        for ((key, value) in attrs) {
            if (value != null) {
                sb.append(' ').append(key).append("=\"").append(escapeAttr(value)).append('"')
            }
        }
        if (selfClosing && xhtml) {
            sb.append(" /")
        }
        sb.append('>')
    }

    private fun closeTag(name: String) {
        sb.append("</").append(name).append('>')
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun escapeAttr(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun visitChildren(node: ContainerNode) {
        for (child in node.children) {
            child.accept(this)
        }
    }

    /**
     * 构建 HTML 属性映射（id, class, 及自定义 key-value）。
     */
    private fun buildAttrs(
        baseAttrs: Map<String, String?> = emptyMap(),
        attributes: Map<String, String> = emptyMap(),
    ): Map<String, String?> {
        if (attributes.isEmpty()) return baseAttrs
        val merged = LinkedHashMap<String, String?>(baseAttrs)
        for ((k, v) in attributes) {
            when (k) {
                "class" -> {
                    val existing = merged["class"]
                    merged["class"] = if (existing != null) "$existing $v" else v
                }
                else -> merged[k] = v
            }
        }
        return merged
    }

    // ─────────────── 块级节点 ───────────────

    override fun visitDocument(node: Document) {
        visitChildren(node)
    }

    override fun visitHeading(node: Heading) {
        val tagName = "h${node.level}"
        val baseAttrs = buildMap<String, String?> {
            node.id?.let { put("id", it) }
        }
        val attrs = buildAttrs(baseAttrs, node.blockAttributes)
        tag(tagName, attrs)
        visitChildren(node)
        closeTag(tagName)
        sb.append('\n')
    }

    override fun visitSetextHeading(node: SetextHeading) {
        val tagName = "h${node.level}"
        val baseAttrs = buildMap<String, String?> {
            node.id?.let { put("id", it) }
        }
        val attrs = buildAttrs(baseAttrs, node.blockAttributes)
        tag(tagName, attrs)
        visitChildren(node)
        closeTag(tagName)
        sb.append('\n')
    }

    override fun visitParagraph(node: Paragraph) {
        // tight list items: render content without <p> tags and without trailing newline
        val parent = node.parent
        if (parent is ListItem) {
            val grandParent = parent.parent
            if (grandParent is ListBlock && grandParent.tight) {
                visitChildren(node)
                val siblings = parent.children
                if (siblings.last() !== node) {
                    sb.append('\n')
                }
                return
            }
        }
        val attrs = buildAttrs(attributes = node.blockAttributes)
        tag("p", attrs)
        visitChildren(node)
        closeTag("p")
        sb.append('\n')
    }

    override fun visitThematicBreak(node: ThematicBreak) {
        tag("hr", selfClosing = true)
        sb.append('\n')
    }

    override fun visitFencedCodeBlock(node: FencedCodeBlock) {
        val title = node.attributes.pairs["title"].orEmpty()
        renderCodeBlock(
            code = node.literal,
            language = node.language,
            title = title,
            showLineNumbers = node.showLineNumbers,
            startLineNumber = node.startLineNumber,
            highlightLines = node.highlightLines,
            extraCodeAttrs = node.attributes.toMap().filterKeys { it != "title" },
        )
    }

    override fun visitIndentedCodeBlock(node: IndentedCodeBlock) {
        renderCodeBlock(
            code = node.literal,
            language = "",
            title = "",
            showLineNumbers = false,
            startLineNumber = 1,
            highlightLines = emptyList(),
        )
    }

    /**
     * 输出对齐 Compose 端 CodeBlock 视觉的代码块结构：
     * 可选 title 栏（语言标签 + 标题文本）、行号列、高亮行背景与 tok-* 语法高亮。
     */
    private fun renderCodeBlock(
        code: String,
        language: String,
        title: String,
        showLineNumbers: Boolean,
        startLineNumber: Int,
        highlightLines: List<IntRange>,
        extraCodeAttrs: Map<String, String> = emptyMap(),
    ) {
        val containerAttrs = buildMap<String, String?> {
            put("class", "code-block")
            if (!showLineNumbers) put("data-linenums", "false")
        }
        tag("div", containerAttrs)
        sb.append('\n')
        if (title.isNotEmpty() || language.isNotEmpty()) {
            tag("div", mapOf("class" to "code-block-title"))
            if (language.isNotEmpty()) {
                tag("span", mapOf("class" to "code-lang"))
                sb.append(escape(language))
                closeTag("span")
            }
            if (title.isNotEmpty()) {
                tag("span", mapOf("class" to "code-title-text"))
                sb.append(escape(title))
                closeTag("span")
            }
            closeTag("div")
            sb.append('\n')
        }

        val preAttrs = buildMap<String, String?> {
            put("class", "code-block-pre")
            if (startLineNumber != 1) put("data-startline", startLineNumber.toString())
            if (showLineNumbers) put("data-linenums", "true")
            if (highlightLines.isNotEmpty()) {
                put(
                    "data-hl-lines",
                    highlightLines.joinToString(" ") { range ->
                        if (range.first == range.last) range.first.toString()
                        else "${range.first}-${range.last}"
                    },
                )
            }
        }
        tag("pre", preAttrs)
        val codeAttrs = buildMap<String, String?> {
            if (language.isNotEmpty()) put("class", "language-$language")
            for ((k, v) in extraCodeAttrs) {
                if (k == "class") {
                    val existing = this["class"]
                    put("class", if (existing != null) "$existing $v" else v)
                } else {
                    put(k, v)
                }
            }
        }
        tag("code", codeAttrs)
        sb.append('\n')
        sb.append(appendHighlightedLines(code, language, showLineNumbers, startLineNumber, highlightLines))
        closeTag("code")
        sb.append('\n')
        closeTag("pre")
        sb.append('\n')
        closeTag("div")
        sb.append('\n')
    }

    /**
     * 按行输出代码内容：行号 span + 行内容（tok-* 高亮或转义纯文本）。
     * 高亮 token 覆盖不完整（串联 != 原文）时整块降级为转义纯文本。
     */
    private fun appendHighlightedLines(
        code: String,
        language: String,
        showLineNumbers: Boolean,
        startLineNumber: Int,
        highlightLines: List<IntRange>,
    ): String {
        val lines = code.split('\n')
        // 尾部空行来自结尾换行符，不输出多余空行
        val end = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.size - 1 else lines.size
        val tokensByLine = resolveTokensByLine(code, language, lines)
        val out = StringBuilder()
        for (index in 0 until end) {
            val lineText = lines[index]
            val lineNumber = startLineNumber + index
            val isHighlighted = highlightLines.any { lineNumber >= it.first && lineNumber <= it.last }
            val lineClass = if (isHighlighted) "code-line hl" else "code-line"
            out.append("<span class=\"").append(lineClass).append("\">")
            if (showLineNumbers) {
                out.append("<span class=\"line-number\">").append(lineNumber).append("</span>")
            }
            out.append("<span class=\"line-content\">")
            val lineTokens: List<HtmlHighlightToken>? = if (tokensByLine != null) tokensByLine[index] else null
            if (lineTokens != null && lineTokens.isNotEmpty()) {
                for (token in lineTokens) {
                    out.append("<span class=\"tok-").append(token.category).append("\">")
                    out.append(escape(token.text))
                    out.append("</span>")
                }
            } else if (lineText.isNotEmpty()) {
                out.append(escape(lineText))
            }
            out.append("</span></span>\n")
        }
        return out.toString()
    }

    /**
     * 调用 code hook 取得中立 token，并按源码行切分。
     * 返回 null 表示 hook 缺失、抛错或 token 文本串联与原文不一致（降级纯文本）。
     */
    private fun resolveTokensByLine(
        code: String,
        language: String,
        lines: List<String>,
    ): List<List<HtmlHighlightToken>>? {
        val provider = hooks.code
        if (provider == null || code.isEmpty()) return null
        val tokens: List<HtmlHighlightToken>?
        try {
            tokens = provider.highlight(code, language)
        } catch (_: Throwable) {
            return null
        }
        if (tokens == null) return null
        // 安全兜底：token 必须按序完整覆盖原文，否则放弃高亮
        var total = 0
        for (token in tokens) {
            total += token.text.length
        }
        if (total != code.length) {
            return null
        }
        val result: MutableList<MutableList<HtmlHighlightToken>> = ArrayList(lines.size)
        for (index in lines.indices) {
            result.add(ArrayList())
        }
        var cursor = 0
        for (token in tokens) {
            if (token.text.isEmpty()) continue
            var remaining = token.text
            while (remaining.isNotEmpty()) {
                if (cursor >= lines.size) {
                    // 所有行已填满：仅剩换行分隔符可丢弃，否则视为覆盖错位
                    if (remaining.any { it != '\n' }) return null
                    break
                }
                val lineText = lines[cursor]
                val used = result[cursor].sumOf { t -> t.text.length }
                val lineRemaining = lineText.length - used
                if (lineRemaining <= 0) {
                    // 当前行已填满：token 中只允许出现换行分隔符，否则视为覆盖错位
                    if (remaining.first() != '\n') return null
                    remaining = remaining.substring(1)
                    cursor++
                    continue
                }
                if (remaining.first() == '\n') {
                    // 行内容未满却遇到换行：token 与源码行错位
                    return null
                }
                val take = minOf(lineRemaining, remaining.length)
                result[cursor].add(HtmlHighlightToken(token.category, remaining.substring(0, take)))
                remaining = remaining.substring(take)
            }
        }
        // 最终校验：每行 token 串联必须与该行原文一致，任何错位都降级
        for (index in result.indices) {
            val joined = result[index].joinToString("") { it.text }
            if (joined != lines[index]) return null
        }
        return result
    }

    fun resolveTokensByLinePublic(code: String, language: String, lines: List<String>): List<List<HtmlHighlightToken>>? =
        resolveTokensByLine(code, language, lines)

    fun appendHighlightedLinesPublic(code: String, language: String, showLineNumbers: Boolean, startLineNumber: Int, highlightLines: List<IntRange>): String =
        appendHighlightedLines(code, language, showLineNumbers, startLineNumber, highlightLines)

    override fun visitBlockQuote(node: BlockQuote) {
        val attrs = buildAttrs(attributes = node.blockAttributes)
        tag("blockquote", attrs)
        sb.append('\n')
        visitChildren(node)
        closeTag("blockquote")
        sb.append('\n')
    }

    override fun visitListBlock(node: ListBlock) {
        val baseAttrs = buildMap<String, String?> {
            if (node.ordered && node.startNumber != 1) {
                put("start", node.startNumber.toString())
            }
        }
        val attrs = buildAttrs(baseAttrs, node.blockAttributes)
        if (node.ordered) {
            tag("ol", attrs)
        } else {
            tag("ul", attrs)
        }
        sb.append('\n')
        visitChildren(node)
        if (node.ordered) closeTag("ol") else closeTag("ul")
        sb.append('\n')
    }

    override fun visitListItem(node: ListItem) {
        val parentList = node.parent as? ListBlock
        val isLoose = parentList != null && !parentList.tight
        val fc = node.children.firstOrNull()
        // add newline after <li> if loose (with children) or tight with non-paragraph first child
        // empty items never get newline after <li>
        val hasChildren = fc != null
        val nlAfterLi = (isLoose && hasChildren) || (parentList != null && parentList.tight && fc != null && fc !is Paragraph)
        if (node.taskListItem) {
            tag("li")
            if (nlAfterLi) sb.append('\n')
            val checked = if (node.checked) " checked=\"\"" else ""
            val disabled = " disabled=\"\""
            sb.append("<input type=\"checkbox\"$checked$disabled")
            if (xhtml) sb.append(" /")
            sb.append("> ")
            visitChildren(node)
            closeTag("li")
        } else {
            tag("li")
            if (nlAfterLi) sb.append('\n')
            visitChildren(node)
            closeTag("li")
        }
        sb.append('\n')
    }

    override fun visitHtmlBlock(node: HtmlBlock) {
        if (escapeHtml) {
            sb.append(escape(node.literal))
        } else {
            sb.append(node.literal)
        }
        // only add newline if literal doesn't already end with one
        if (!node.literal.endsWith('\n')) {
            sb.append('\n')
        }
    }

    override fun visitLinkReferenceDefinition(node: LinkReferenceDefinition) {
        // 不输出到 HTML
    }

    override fun visitTable(node: Table) {
        val attrs = buildAttrs(attributes = node.blockAttributes)
        tag("table", attrs)
        sb.append('\n')
        visitChildren(node)
        closeTag("table")
        sb.append('\n')
    }

    override fun visitTableHead(node: TableHead) {
        tag("thead")
        sb.append('\n')
        visitChildren(node)
        closeTag("thead")
        sb.append('\n')
    }

    override fun visitTableBody(node: TableBody) {
        tag("tbody")
        sb.append('\n')
        visitChildren(node)
        closeTag("tbody")
        sb.append('\n')
    }

    override fun visitTableRow(node: TableRow) {
        tag("tr")
        sb.append('\n')
        visitChildren(node)
        closeTag("tr")
        sb.append('\n')
    }

    override fun visitTableCell(node: TableCell) {
        val tagName = if (node.isHeader) "th" else "td"
        val attrs = when (node.alignment) {
            Table.Alignment.LEFT -> mapOf<String, String?>("align" to "left")
            Table.Alignment.CENTER -> mapOf<String, String?>("align" to "center")
            Table.Alignment.RIGHT -> mapOf<String, String?>("align" to "right")
            Table.Alignment.NONE -> emptyMap()
        }
        tag(tagName, attrs)
        visitChildren(node)
        closeTag(tagName)
        sb.append('\n')
    }

    override fun visitFootnoteDefinition(node: FootnoteDefinition) {
        val attrs = mapOf<String, String?>("id" to "fn-${node.label}")
        tag("div", attrs)
        sb.append('\n')
        tag("p")
        sb.append("[${node.index}] ")
        visitChildren(node)
        closeTag("p")
        sb.append('\n')
        closeTag("div")
        sb.append('\n')
    }

    override fun visitMathBlock(node: MathBlock) {
        requirements?.hasMath = true
        val mathml = hooks.math?.toMathMl(node.literal, displayMode = true)
        if (mathml != null) {
            val attrs = mapOf<String, String?>("class" to "math-display")
            tag("div", attrs)
            sb.append(mathml)
            closeTag("div")
            sb.append('\n')
            return
        }
        val attrs = mapOf<String, String?>("class" to "math-display")
        tag("div", attrs)
        sb.append("\\[")
        sb.append(escape(node.literal))
        sb.append("\\]")
        closeTag("div")
        sb.append('\n')
    }

    override fun visitDefinitionList(node: DefinitionList) {
        tag("dl")
        sb.append('\n')
        visitChildren(node)
        closeTag("dl")
        sb.append('\n')
    }

    override fun visitDefinitionTerm(node: DefinitionTerm) {
        tag("dt")
        visitChildren(node)
        closeTag("dt")
        sb.append('\n')
    }

    override fun visitDefinitionDescription(node: DefinitionDescription) {
        tag("dd")
        visitChildren(node)
        closeTag("dd")
        sb.append('\n')
    }

    override fun visitAdmonition(node: Admonition) {
        val attrs = mapOf<String, String?>("class" to "admonition ${node.type.lowercase()}")
        tag("div", attrs)
        sb.append('\n')
        if (node.title.isNotEmpty()) {
            tag("p", mapOf("class" to "admonition-title"))
            sb.append(escape(node.title))
            closeTag("p")
            sb.append('\n')
        }
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitFrontMatter(node: FrontMatter) {
        // 不输出到 HTML（元数据）
    }

    override fun visitBlankLine(node: BlankLine) {
        // 不输出
    }

    override fun visitTocPlaceholder(node: TocPlaceholder) {
        // 两遍扫描：渲染到 TOC 节点时从根文档收集全文档 heading（惰性，仅首次触发）
        val all = collectedHeadings
            ?: rootDocument?.let { collectHeadings(it) }?.also { collectedHeadings = it }
            ?: emptyList()
        var entries = all.filter { it.level in node.minDepth..node.maxDepth }
        if (node.excludeIds.isNotEmpty()) {
            entries = entries.filter { it.id == null || it.id !in node.excludeIds }
        }
        if (node.order == "desc") entries = entries.reversed()

        val attrs = mapOf<String, String?>("class" to "table-of-contents")
        if (entries.isEmpty()) {
            // 全部被过滤 → 空壳 nav（不输出任何占位文本）
            tag("nav", attrs)
            closeTag("nav")
            sb.append('\n')
            return
        }
        tag("nav", attrs)
        sb.append('\n')
        tag("p", mapOf("class" to "toc-title"))
        sb.append("Table of Contents")
        closeTag("p")
        sb.append('\n')
        appendTocList(entries)
        closeTag("nav")
        sb.append('\n')
    }

    private data class TocHeadingInfo(val text: String, val level: Int, val id: String?)

    /** 递归收集全文档 heading（Heading + SetextHeading，ContainerNode 递归） */
    private fun collectHeadings(document: Document): List<TocHeadingInfo> {
        val result = mutableListOf<TocHeadingInfo>()
        for (child in document.children) {
            collectHeadingsRecursive(child, result)
        }
        return result
    }

    private fun collectHeadingsRecursive(node: Node, result: MutableList<TocHeadingInfo>) {
        when (node) {
            is Heading -> result.add(TocHeadingInfo(extractTocText(node), node.level, node.id))
            is SetextHeading -> result.add(TocHeadingInfo(extractTocText(node), node.level, node.id))
            is ContainerNode -> node.children.forEach { collectHeadingsRecursive(it, result) }
            else -> {}
        }
    }

    /** 提取行内节点纯文本（与 Compose 端 extractText 语义一致） */
    private fun extractTocText(node: Node): String = buildString {
        for (child in (node as? ContainerNode)?.children ?: emptyList()) {
            append(extractTocTextOf(child))
        }
    }

    private fun extractTocTextOf(node: Node): String = when (node) {
        is Text -> node.literal
        is InlineCode -> node.literal
        is EscapedChar -> node.literal
        is HtmlEntity -> node.resolved.ifEmpty { node.literal }
        is Emoji -> node.literal
        is ContainerNode -> buildString { for (c in node.children) append(extractTocTextOf(c)) }
        else -> ""
    }

    /**
     * 嵌套 ul 输出：level 加深时在当前 li 内开新 ul，level 减浅时逐层闭合 ul/li；
     * 跳级不产生空 ul。每项 `<li class="toc-level-{n}">`，id 非空包锚点 `<a href="#id">`。
     */
    private fun appendTocList(entries: List<TocHeadingInfo>) {
        tag("ul", mapOf("class" to "toc-list"))
        sb.append('\n')
        val openUlParentLevels = ArrayList<Int>()
        var prevLevel: Int? = null
        for (entry in entries) {
            val level = entry.level
            val prev = prevLevel
            when {
                prev == null -> {}
                level > prev -> {
                    tag("ul")
                    sb.append('\n')
                    openUlParentLevels.add(prev)
                }
                level < prev -> {
                    closeTag("li")
                    sb.append('\n')
                    while (openUlParentLevels.isNotEmpty() && openUlParentLevels.last() >= level) {
                        closeTag("ul")
                        sb.append('\n')
                        openUlParentLevels.removeAt(openUlParentLevels.size - 1)
                        closeTag("li")
                        sb.append('\n')
                    }
                }
                else -> {
                    closeTag("li")
                    sb.append('\n')
                }
            }
            tag("li", mapOf("class" to "toc-level-$level"))
            val id = entry.id
            if (id != null) {
                tag("a", mapOf("href" to "#$id"))
                sb.append(escape(entry.text))
                closeTag("a")
            } else {
                sb.append(escape(entry.text))
            }
            // li 暂不闭合：更深层级的 ul 可能嵌套在其内
            prevLevel = level
        }
        closeTag("li")
        sb.append('\n')
        while (openUlParentLevels.isNotEmpty()) {
            closeTag("ul")
            sb.append('\n')
            openUlParentLevels.removeAt(openUlParentLevels.size - 1)
            closeTag("li")
            sb.append('\n')
        }
        closeTag("ul")
        sb.append('\n')
    }

    override fun visitAbbreviationDefinition(node: AbbreviationDefinition) {
        // 不直接输出（缩写在行内 Abbreviation 节点中渲染）
    }

    override fun visitCustomContainer(node: CustomContainer) {
        val classes = buildList {
            add("custom-container")
            if (node.type.isNotEmpty()) add(node.type)
            addAll(node.cssClasses)
        }
        val attrs = mutableMapOf<String, String?>(
            "class" to classes.joinToString(" ")
        )
        node.cssId?.let { attrs["id"] = it }
        tag("div", attrs)
        sb.append('\n')
        if (node.title.isNotEmpty()) {
            tag("p", mapOf("class" to "custom-container-title"))
            sb.append(escape(node.title))
            closeTag("p")
            sb.append('\n')
        }
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitDiagramBlock(node: DiagramBlock) {
        requirements?.let {
            it.hasDiagram = true
            it.diagramTypes += node.diagramType
        }
        val isMermaid = node.diagramType.equals("mermaid", ignoreCase = true)
        val attrs = mapOf<String, String?>("class" to "diagram ${node.diagramType}")
        tag("div", attrs)
        sb.append('\n')
        if (isMermaid) {
            // mermaid 渲染路径：<pre class="mermaid"> 包转义源码，页面尾部 bootstrap 的
            // mermaid.run() 扫描 .mermaid 元素渲染为 SVG。
            // 内容必须转义：源码可能含 < > &；mermaid 读 textContent，转义不影响图表语义。
            // 无 bootstrap 时（平台无资源 / embedMermaid=false）浏览器直接展示转义源码文本，
            // pre 本就是源码展示容器，视觉与结构均可接受（降级语义天然成立）。
            tag("pre", mapOf<String, String?>("class" to "mermaid"))
            sb.append(escape(node.literal))
            closeTag("pre")
        } else {
            // 非 mermaid 图表类型（plantuml/dot/…）：无 JS 渲染引擎，保留源码展示
            tag("pre")
            tag("code", mapOf("class" to "language-${node.diagramType}"))
            sb.append(escape(node.literal))
            closeTag("code")
            closeTag("pre")
        }
        sb.append('\n')
        closeTag("div")
        sb.append('\n')
    }

    override fun visitVerticalTextBlock(node: VerticalTextBlock) {
        requirements?.hasVerticalText = true
        val classNames = buildList {
            add("vtext")
            add("vertical-text")
            addAll(node.attributes.classes)
        }.joinToString(" ")

        val style = buildString {
            // 字体回退链：NotoSansMongolian 嵌入时首选；未嵌入时平台系统蒙文字体兜底
            // （macOS Mongolian Baiti / Win Segoe UI Historic），末尾 serif 保底
            // （竖排场景 serif 默认字形直立性优于 sans-serif）。
            append("writing-mode: vertical-lr; text-orientation: mixed; font-family: 'Noto Sans Mongolian', 'Mongolian Baiti', 'Segoe UI Historic', serif; max-width: 100%; box-sizing: border-box;")
            node.height?.let { h ->
                val cssH = formatCssDimension(h)
                append(" height: $cssH;")
            }
            node.fontSize?.let { s ->
                val cssS = formatCssDimension(s)
                append(" font-size: $cssS;")
            }
            // Compose 端 verticalTextStyle：fontSize 13sp / lineHeight 21.5sp（lineHeight/fontSize ≈ 1.654）。
            // vertical-lr 下 line-height 即"列宽/列距"——Compose fixedWidth 等宽列语义的 CSS 近似
            //（实际列宽由每列最长字符决定，固定 line-height + text-orientation: mixed 已能模拟等宽列观感）。
            // fontSize 未指定 → 文档默认 13px 配 21.5px；可解析数值 → 1.654×px（保留 1 位小数）；
            // 非数值单位（如 2em）→ 无单位倍数 1.654。
            val fontSizePx = node.fontSize?.let { cssDimensionPxValue(it) }
            when {
                node.fontSize == null -> append(" line-height: 21.5px;")
                fontSizePx != null -> append(" line-height: ${formatLineHeightPx(1.654 * fontSizePx)};")
                else -> append(" line-height: 1.654;")
            }
            if (node.wrap == true) {
                append(" white-space: normal; overflow-x: auto;")
            } else {
                append(" white-space: pre; overflow-x: auto; overflow-y: auto;")
            }
        }

        val attrs = buildMap<String, String?> {
            put("class", classNames)
            put("style", style)
            node.attributes.id?.let { put("id", it) }
        }
        tag("div", attrs)
        sb.append('\n')
        sb.append(escape(node.literal))
        sb.append('\n')
        closeTag("div")
        sb.append('\n')
    }

    private fun formatCssDimension(raw: String): String {
        val trimmed = raw.trim()
        val num = trimmed.toDoubleOrNull()
        if (num != null) return "${num}px"
        if (trimmed.endsWith("dp", ignoreCase = true) || trimmed.endsWith("sp", ignoreCase = true)) {
            val prefix = trimmed.substring(0, trimmed.length - 2).trim()
            if (prefix.toDoubleOrNull() != null) return "${prefix}px"
        }
        return trimmed
    }

    /** [raw] 可解析为纯数值或 dp/sp 数值时返回 px 数值（dp/sp → px 1:1）；否则 null（如 "2em"） */
    private fun cssDimensionPxValue(raw: String): Double? {
        val trimmed = raw.trim()
        trimmed.toDoubleOrNull()?.let { return it }
        if (trimmed.endsWith("dp", ignoreCase = true) || trimmed.endsWith("sp", ignoreCase = true)) {
            val prefix = trimmed.substring(0, trimmed.length - 2).trim()
            prefix.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    /** lineHeight px 值保留 1 位小数（1.654×18 = 29.772 → "29.8px"）；整数无小数部分 */
    private fun formatLineHeightPx(px: Double): String {
        val tenths = (px * 10).roundToInt()
        val intPart = tenths / 10
        val fracPart = tenths % 10
        return if (fracPart == 0) "${intPart}px" else "${intPart}.${fracPart}px"
    }

    override fun visitColumnsLayout(node: ColumnsLayout) {
        tag("div", mapOf("class" to "columns-layout"))
        sb.append('\n')
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitColumnItem(node: ColumnItem) {
        val attrs = mutableMapOf<String, String?>("class" to "column-item")
        if (node.width.isNotEmpty()) {
            // 显式列宽 → flex-basis 固定；无 width 的列由样式表 flex:1 1 0 均分
            attrs["style"] = "flex: 0 0 ${node.width}"
        }
        tag("div", attrs)
        sb.append('\n')
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitPageBreak(node: PageBreak) {
        tag("div", mapOf("class" to "page-break", "style" to "page-break-after:always"))
        closeTag("div")
        sb.append('\n')
    }

    override fun visitDirectiveBlock(node: DirectiveBlock) {
        val snapshot = HtmlDirectiveBlockSnapshot(
            tagName = node.tagName,
            args = node.args,
            hasContent = node.children.isNotEmpty(),
        )
        val custom = directiveBlockFallback?.invoke(snapshot)
        if (custom != null) {
            sb.append(custom)
            if (!custom.endsWith('\n')) {
                sb.append('\n')
            }
            return
        }
        val argsStr = snapshot.args.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val attrs = mutableMapOf<String, String?>(
            "data-directive" to snapshot.tagName,
        )
        if (argsStr.isNotEmpty()) {
            attrs["data-args"] = argsStr
        }
        tag("div", attrs)
        sb.append('\n')
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    // ─────────────── 行内节点 ───────────────

    override fun visitText(node: Text) {
        sb.append(escape(node.literal))
    }

    override fun visitSoftLineBreak(node: SoftLineBreak) {
        sb.append(softBreak)
    }

    override fun visitHardLineBreak(node: HardLineBreak) {
        sb.append(hardBreak)
    }

    override fun visitEmphasis(node: Emphasis) {
        tag("em")
        visitChildren(node)
        closeTag("em")
    }

    override fun visitStrongEmphasis(node: StrongEmphasis) {
        tag("strong")
        visitChildren(node)
        closeTag("strong")
    }

    override fun visitStrikethrough(node: Strikethrough) {
        tag("del")
        visitChildren(node)
        closeTag("del")
    }

    override fun visitInlineCode(node: InlineCode) {
        tag("code")
        sb.append(escape(node.literal))
        closeTag("code")
    }

    override fun visitLink(node: Link) {
        val attrs = buildAttrs(
            baseAttrs = buildMap {
                put("href", node.destination)
                node.title?.let { put("title", it) }
            },
            attributes = node.attributes,
        )
        tag("a", attrs)
        visitChildren(node)
        closeTag("a")
    }

    override fun visitImage(node: Image) {
        val baseAttrs = buildMap<String, String?> {
            put("src", node.destination)
            // alt 文本从子节点提取
            val alt = extractAltText(node)
            put("alt", alt)
            node.title?.let { put("title", it) }
            node.imageWidth?.let { put("width", it.toString()) }
            node.imageHeight?.let { put("height", it.toString()) }
        }
        val attrs = buildAttrs(baseAttrs, node.attributes)
        tag("img", attrs, selfClosing = true)
    }

    private fun extractAltText(node: ContainerNode): String = buildString {
        for (child in node.children) {
            when (child) {
                is Text -> append(child.literal)
                is InlineCode -> append(child.literal)
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append(' ')
                is ContainerNode -> append(extractAltText(child))
                else -> {}
            }
        }
    }

    override fun visitAutolink(node: Autolink) {
        if (node.isEmail) {
            tag("a", mapOf("href" to "mailto:${node.destination}"))
        } else {
            tag("a", mapOf("href" to node.destination))
        }
        val display = node.rawText.ifEmpty { node.destination }
        sb.append(escape(display))
        closeTag("a")
    }

    override fun visitInlineHtml(node: InlineHtml) {
        if (escapeHtml) {
            sb.append(escape(node.literal))
        } else {
            sb.append(node.literal)
        }
    }

    override fun visitHtmlEntity(node: HtmlEntity) {
        // output the resolved unicode, falling back to the original entity
        val text = node.resolved.ifEmpty { node.literal }
        sb.append(escape(text))
    }

    override fun visitEscapedChar(node: EscapedChar) {
        sb.append(escape(node.literal))
    }

    override fun visitFootnoteReference(node: FootnoteReference) {
        tag("sup")
        tag("a", mapOf("href" to "#fn-${node.label}", "class" to "footnote-ref"))
        sb.append("[${node.index}]")
        closeTag("a")
        closeTag("sup")
    }

    override fun visitInlineMath(node: InlineMath) {
        requirements?.hasMath = true
        val mathml = hooks.math?.toMathMl(node.literal, displayMode = false)
        if (mathml != null) {
            tag("span", mapOf("class" to "math-inline"))
            sb.append(mathml)
            closeTag("span")
            return
        }
        tag("span", mapOf("class" to "math-inline"))
        sb.append("\\(")
        sb.append(escape(node.literal))
        sb.append("\\)")
        closeTag("span")
    }

    override fun visitHighlight(node: Highlight) {
        tag("mark")
        visitChildren(node)
        closeTag("mark")
    }

    override fun visitSuperscript(node: Superscript) {
        tag("sup")
        visitChildren(node)
        closeTag("sup")
    }

    override fun visitSubscript(node: Subscript) {
        tag("sub")
        visitChildren(node)
        closeTag("sub")
    }

    override fun visitInsertedText(node: InsertedText) {
        tag("ins")
        visitChildren(node)
        closeTag("ins")
    }

    override fun visitEmoji(node: Emoji) {
        val unicode = node.unicode
        if (unicode != null) {
            sb.append(unicode)
        } else {
            sb.append(":${node.shortcode}:")
        }
    }

    override fun visitStyledText(node: StyledText) {
        val attrs = buildAttrs(attributes = node.attributes)
        tag("span", attrs)
        visitChildren(node)
        closeTag("span")
    }

    override fun visitAbbreviation(node: Abbreviation) {
        tag("abbr", mapOf("title" to node.fullText))
        sb.append(escape(node.abbreviation))
        closeTag("abbr")
    }

    override fun visitKeyboardInput(node: KeyboardInput) {
        tag("kbd")
        sb.append(escape(node.literal))
        closeTag("kbd")
    }

    override fun visitDirectiveInline(node: DirectiveInline) {
        val snapshot = HtmlDirectiveInlineSnapshot(
            tagName = node.tagName,
            args = node.args,
            alternateText = node.literal,
        )
        val custom = directiveInlineFallback?.invoke(snapshot)
        if (custom != null) {
            sb.append(custom)
            return
        }
        val argsStr = snapshot.args.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val attrs = mutableMapOf<String, String?>(
            "data-directive" to snapshot.tagName,
        )
        if (argsStr.isNotEmpty()) {
            attrs["data-args"] = argsStr
        }
        tag("span", attrs)
        closeTag("span")
    }

    override fun visitTabBlock(node: TabBlock) {
        tag("div", mapOf("class" to "tab-block", "role" to "tablist"))
        sb.append('\n')
        // tab 栏：每项一个视觉标签（静态呈现，无 JS 交互）
        tag("div", mapOf("class" to "tab-bar"))
        node.children.forEach { child ->
            // 空 title 的 TabItem 是解析器的结构占位（tab 分隔），不生成标签
            val title = (child as? TabItem)?.title
            if (title.isNullOrEmpty()) return@forEach
            tag("span", mapOf("class" to "tab-label"))
            sb.append(escape(title))
            closeTag("span")
            sb.append('\n')
        }
        closeTag("div")
        sb.append('\n')
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitTabItem(node: TabItem) {
        tag("div", mapOf("class" to "tab-item", "data-tab-title" to node.title, "aria-selected" to "true"))
        sb.append('\n')
        visitChildren(node)
        closeTag("div")
        sb.append('\n')
    }

    override fun visitBibliographyDefinition(node: BibliographyDefinition) {
        tag("div", mapOf("class" to "bibliography"))
        sb.append('\n')
        tag("h4")
        sb.append("References")
        closeTag("h4")
        sb.append('\n')
        tag("ol", mapOf("class" to "bibliography-list"))
        sb.append('\n')
        for ((key, entry) in node.entries) {
            tag("li", mapOf("id" to "bib-$key"))
            sb.append(escape(entry.content))
            closeTag("li")
            sb.append('\n')
        }
        closeTag("ol")
        sb.append('\n')
        closeTag("div")
        sb.append('\n')
    }

    override fun visitCitationReference(node: CitationReference) {
        tag("sup")
        tag("a", mapOf("href" to "#bib-${node.key}", "class" to "citation-ref"))
        sb.append("[${escape(node.key)}]")
        closeTag("a")
        closeTag("sup")
    }

    override fun visitSpoiler(node: Spoiler) {
        tag("span", mapOf("class" to "spoiler"))
        visitChildren(node)
        closeTag("span")
    }

    override fun visitWikiLink(node: WikiLink) {
        val display = node.label ?: node.target
        tag("a", mapOf("href" to node.target, "class" to "wikilink"))
        sb.append(escape(display))
        closeTag("a")
    }

    override fun visitRubyText(node: RubyText) {
        tag("ruby")
        sb.append(escape(node.base))
        tag("rp")
        sb.append("(")
        closeTag("rp")
        tag("rt")
        sb.append(escape(node.annotation))
        closeTag("rt")
        tag("rp")
        sb.append(")")
        closeTag("rp")
        closeTag("ruby")
    }

    override fun visitFigure(node: Figure) {
        tag("figure")
        sb.append('\n')
        tag("img", mapOf(
            "src" to node.imageUrl,
            "alt" to node.caption,
        ), selfClosing = true)
        sb.append('\n')
        if (node.caption.isNotEmpty()) {
            tag("figcaption")
            sb.append(escape(node.caption))
            closeTag("figcaption")
            sb.append('\n')
        }
        closeTag("figure")
        sb.append('\n')
    }

    companion object {
        /**
         * 便捷方法：将 Document 渲染为 HTML 字符串。
         *
         * ```kotlin
         * val html = HtmlRenderer.render(document)
         * ```
         */
        fun render(document: Document): String {
            return HtmlRenderer().render(document)
        }

        /**
         * 便捷方法：将 Markdown 文本解析并渲染为 HTML。
         *
         * ```kotlin
         * val html = HtmlRenderer.renderMarkdown("# Hello")
         * // <h1 id="hello">Hello</h1>\n
         * ```
         */
        fun renderMarkdown(
            markdown: String,
            softBreak: String = "\n",
            escapeHtml: Boolean = false,
            flavour: MarkdownFlavour = ExtendedFlavour,
        ): String {
            val parser = xyz.emuci.markdown.parser.MarkdownParser(flavour)
            val document = parser.parse(markdown)
            return HtmlRenderer(softBreak = softBreak, escapeHtml = escapeHtml).render(document)
        }

        /**
         * 将 Markdown AST 渲染为完整的、自包含样式的独立 HTML 页面。
         *
         * 适合在 WebView / JCEF 中离屏加载并执行打印（Print to PDF）。
         *
         * @param document Markdown AST
         * @param title 页面标题
         * @param customCss 自定义附加 CSS
         * @param embeddedFontBase64 可选 Base64 编码的 Noto Sans Mongolian TTF 字体
         */
        fun renderFullHtmlPage(
            document: Document,
            title: String = "Markdown Export",
            customCss: String = "",
            embeddedFontBase64: String? = null,
        ): String = renderFullHtmlPage(
            document = document,
            options = HtmlPageOptions(
                title = title,
                customCss = customCss,
                dark = false,
                fontBase64ByFamily = if (embeddedFontBase64.isNullOrBlank()) {
                    emptyMap()
                } else {
                    mapOf("Noto Sans Mongolian" to embeddedFontBase64)
                },
            ),
        )

        /**
         * 完整 HTML 页面配置。
         *
         * @param title 页面标题
         * @param customCss 自定义附加 CSS
         * @param dark 是否启用深色主题
         * @param fontBase64ByFamily family 名 → Base64 字体数据（无则空 map）
         * @param lang <html lang> 属性值
         * @param hooks 渲染增强 SPI
         * @param requirements 资源需求收集器
         * @param codeColors 代码高亮 category（"keyword"/"string"/…）→ hex 颜色，注入 tok-* CSS 规则
         * @param mathFontVariants KaTeX 数学字体变体（family/weight/style + Base64），逐变体生成 @font-face
         */
        data class HtmlPageOptions(
            val title: String = "Markdown Export",
            val customCss: String = "",
            val dark: Boolean = false,
            /** family 名 → Base64（无则空） */
            val fontBase64ByFamily: Map<String, String> = emptyMap(),
            val lang: String = "zh-CN",
            val hooks: HtmlExportHooks = HtmlExportHooks(),
            val requirements: HtmlExportRequirements = HtmlExportRequirements(),
            /** 语法高亮 category（"keyword"/"string"/…）→ hex 颜色；空 map 时 tok-* 走 --md-text-color 缺省 */
            val codeColors: Map<String, String> = emptyMap(),
            /** KaTeX 数学字体变体（family/weight/style + Base64），renderFullHtmlPage 逐变体生成 @font-face；空则零嵌入 */
            val mathFontVariants: List<Pair<HtmlFontVariant, String>> = emptyList(),
            /** 图表 bootstrap 完整 <script>…</script> 片段（含 mermaid 库 + initialize + run + ready flag）；空串/null 则不注入（mermaid 源码降级展示） */
            val diagramBootstrap: String = "",
        )

        /**
         * 将 Markdown AST 渲染为完整的、自包含样式的独立 HTML 页面。
         *
         * 适合在 WebView / JCEF 中离屏加载并执行打印（Print to PDF）。
         */
        fun renderFullHtmlPage(
            document: Document,
            options: HtmlPageOptions,
        ): String {
            val bodyHtml = HtmlRenderer(
                hooks = options.hooks,
                requirements = options.requirements,
            ).render(document)
            val fontFaceCss = options.fontBase64ByFamily.entries.joinToString("\n") { (family, base64) ->
                """
                @font-face {
                    font-family: '$family';
                    src: url('data:font/truetype;charset=utf-8;base64,$base64') format('truetype');
                    font-weight: normal;
                    font-style: normal;
                }
                """.trimIndent()
            }
            val mathFontFaceCss = options.mathFontVariants.joinToString("\n") { (variant, base64) ->
                """
                @font-face {
                    font-family: '${variant.family}';
                    src: url('data:font/truetype;charset=utf-8;base64,$base64') format('truetype');
                    font-weight: ${variant.weight};
                    font-style: ${variant.style};
                }
                """.trimIndent()
            }
            val css = buildString {
                append(
                    HtmlStyleSheet.generate(
                        dark = options.dark,
                        customCss = options.customCss,
                        codeColors = options.codeColors,
                    )
                )
                if (fontFaceCss.isNotBlank()) {
                    append("\n\n")
                    append(fontFaceCss)
                }
                if (mathFontFaceCss.isNotBlank()) {
                    append("\n\n")
                    append(mathFontFaceCss)
                }
            }
            val themeAttr = if (options.dark) " data-theme=\"dark\"" else ""
            // bootstrap 注入点：bodyHtml 之后、</body> 之前（body 尾部 inline，DOM 已就绪可直接执行）
            val bootstrapScript = if (options.diagramBootstrap.isNotBlank()) "\n${options.diagramBootstrap}\n" else ""

            return """
            <!DOCTYPE html>
            <html lang="${escapeHtmlText(options.lang)}"$themeAttr>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${escapeHtmlText(options.title)}</title>
                <style>
                    $css
                </style>
            </head>
            <body>
            $bodyHtml$bootstrapScript
            </body>
            </html>
            """.trimIndent()
        }

        private fun escapeHtmlText(text: String): String = buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }
}
