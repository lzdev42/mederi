package xyz.emuci.markdown.parser.html

/**
 * HTML 导出页面的内联样式表生成器。
 *
 * 以 CSS 变量主题骨架的形式输出 `<style>` 内的完整 CSS 文本。
 * 视觉 token 与 Compose 端 MarkdownTheme 的默认值对齐（sp→px 1:1 确定性映射），
 * 变量命名统一使用 `--md-` 前缀，后续子任务在此基础上逐节点补全规则。
 */
object HtmlStyleSheet {

    /** 沿用既有页面的正文字体栈 */
    private const val BODY_FONT_STACK =
        "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Helvetica, Arial, sans-serif"

    private const val CODE_FONT_STACK =
        "ui-monospace, SFMono-Regular, \"SF Mono\", Menlo, Consolas, monospace"

    /**
     * 生成完整 CSS 文本（置于 <style> 标签内）。
     *
     * @param dark true 时通过 html[data-theme="dark"] 选择器启用深色变量
     * @param customCss 附加自定义 CSS，追加在末尾
     * @param codeColors 代码高亮 category（"keyword"/"string"/…）→ hex 颜色；缺省走 --md-text-color
     */
    fun generate(
        dark: Boolean = false,
        customCss: String = "",
        codeColors: Map<String, String> = emptyMap(),
    ): String = buildString {
        // tok-* 颜色规则：按 category 注入主题色，缺省回落正文色；keyword 加粗、comment 斜体
        val codeColorCss = buildString {
            val categories = listOf(
                "keyword", "string", "comment", "number", "type", "function",
                "annotation", "builtin", "constant", "operator", "punctuation",
                "identifier", "plain",
            )
            for (category in categories) {
                val color = codeColors[category]
                val fontStyles = when (category) {
                    "keyword" -> "font-weight: bold; "
                    "comment" -> "font-style: italic; "
                    else -> ""
                }
                append(".tok-$category { ")
                append("color: ${color ?: "var(--md-text-color)"}; ")
                append(fontStyles)
                append("}\n")
            }
        }
        if (dark) {
            append("html {\n    data-theme: dark;\n}\n\n")
        }
        append(
            """
            :root {
                --md-body-size: 13px;
                --md-body-line-height: 21.5px;
                --md-link-color: #0969da;
                --md-text-color: #1f2328;
                --md-bg: #ffffff;
                --md-border-color: #d0d7de;
                --md-code-bg: #f6f8fa;
                --md-heading-color: #1f2328;
                --md-blockquote-text: #656d76;
                --md-inline-code-bg: #eff1f3;
                --md-inline-code-color: #1f2328;
                --md-code-color: #1f2328;
                --md-mark-bg: #fff3b0;
                --md-mark-color: #1f2328;
                --md-kbd-bg: #eff1f3;
                --md-kbd-color: #1f2328;
                --md-task-checked: #1a7f37;
                --md-task-unchecked: #d0d7de;
                --md-footnote-color: #656d76;
                --md-figcaption-color: #656d76;
                --md-spoiler-bg: #3a3a3a;
                --md-blockquote-border: 3px;
                --md-code-title-bg: #ebedf0;
                --md-code-hl-bg: rgba(0, 0, 0, 0.07);
                --md-line-number-color: #8c959f;
            }

            html[data-theme="dark"] {
                --md-text-color: #e6edf3;
                --md-bg: #0d1117;
                --md-link-color: #4493f8;
                --md-border-color: #3d444d;
                --md-code-bg: #161b22;
                --md-heading-color: #e6edf3;
                --md-blockquote-text: #9198a1;
                --md-inline-code-bg: #343942;
                --md-inline-code-color: #e6edf3;
                --md-code-color: #e6edf3;
                --md-mark-bg: #5c4b00;
                --md-mark-color: #e6edf3;
                --md-kbd-bg: #343942;
                --md-kbd-color: #e6edf3;
                --md-task-checked: #3fb950;
                --md-task-unchecked: #3d444d;
                --md-footnote-color: #9198a1;
                --md-figcaption-color: #9198a1;
                --md-spoiler-bg: #3d444d;
                --md-code-title-bg: #21262d;
                --md-code-hl-bg: rgba(255, 255, 255, 0.13);
                --md-line-number-color: #8b949e;
            }

            *, *::before, *::after { box-sizing: border-box; }

            body {
                margin: 0;
                padding: 32px;
                font-family: $BODY_FONT_STACK;
                font-size: var(--md-body-size);
                line-height: var(--md-body-line-height);
                color: var(--md-text-color);
                background-color: var(--md-bg);
            }

            /* ─────────────── 标题 ─────────────── */
            h1, h2, h3, h4, h5, h6 {
                margin-top: 24px;
                margin-bottom: 16px;
                color: var(--md-heading-color);
                line-height: 1.35;
            }
            h1, h2 {
                border-bottom: 1px solid var(--md-border-color);
                padding-bottom: .3em;
            }
            h1 { font-size: 17px; font-weight: 700; line-height: 23px; }
            h2 { font-size: 15px; font-weight: 700; line-height: 20px; }
            h3 { font-size: 14px; font-weight: 600; line-height: 18px; }
            h4 { font-size: 13.5px; font-weight: 600; line-height: 18px; }
            h5 { font-size: 13px; font-weight: 600; line-height: 18px; }
            h6 { font-size: 12.5px; font-weight: 500; line-height: 17px; }

            p { margin-top: 0; margin-bottom: 16px; }

            a { color: var(--md-link-color); text-decoration: none; }
            a:hover { text-decoration: underline; }

            /* ─────────────── 分割线 ─────────────── */
            hr {
                border: 0;
                border-top: 1px solid var(--md-border-color);
                margin: 16px 0;
            }

            /* ─────────────── 引用块 ─────────────── */
            blockquote {
                margin: 0 0 16px;
                padding: 10px;
                padding-left: calc(10px - var(--md-blockquote-border));
                color: var(--md-blockquote-text);
                border-left: var(--md-blockquote-border) solid var(--md-border-color);
            }
            blockquote p { margin-bottom: 0; }

            /* ─────────────── 列表 ─────────────── */
            ul, ol { padding-left: 18px; margin-top: 0; margin-bottom: 16px; }
            li > p { margin-bottom: 0; }
            li input[type="checkbox"] {
                appearance: none;
                width: 13px;
                height: 13px;
                margin: 0 6px 0 0;
                vertical-align: -2px;
                border: 1px solid var(--md-task-unchecked);
                border-radius: 3px;
                background: transparent;
            }
            li input[type="checkbox"][checked] {
                background-color: var(--md-task-checked);
                border-color: var(--md-task-checked);
            }

            /* ─────────────── 表格 ─────────────── */
            table {
                border-spacing: 0;
                border-collapse: collapse;
                margin-top: 0;
                margin-bottom: 16px;
                width: 100%;
                overflow: auto;
                display: block;
            }
            table th, table td {
                padding: 6px 13px;
                border: 1px solid var(--md-border-color);
                text-align: inherit;
            }
            table th { background-color: var(--md-code-bg); font-weight: 600; }
            table tr:nth-child(2n) { background: var(--md-code-bg); }

            /* ─────────────── 代码 ─────────────── */
            pre {
                padding: 10px;
                overflow: auto;
                font-size: 12.5px;
                line-height: 20px;
                background: var(--md-code-bg);
                border-radius: 8px;
                margin-top: 0;
                margin-bottom: 16px;
                font-family: $CODE_FONT_STACK;
                color: var(--md-code-color);
            }
            code {
                font-family: $CODE_FONT_STACK;
                font-size: 12px;
                color: var(--md-inline-code-color);
            }
            pre code { font-size: 12.5px; color: var(--md-code-color); }
            :not(pre) > code {
                background: var(--md-inline-code-bg);
                border-radius: 4px;
                padding: 1px 4px;
            }

            /* ─────────────── 代码块容器（对齐 Compose CodeBlock） ─────────────── */
            .code-block {
                border-radius: 8px;
                background: var(--md-code-bg);
                border: 1px solid var(--md-border-color);
                margin: 16px 0;
                overflow: hidden;
            }
            .code-block-title {
                display: flex;
                align-items: center;
                gap: 8px;
                background: var(--md-code-title-bg);
                padding: 6px 12px;
                font-size: 12px;
                font-family: $CODE_FONT_STACK;
                border-bottom: 1px solid var(--md-border-color);
            }
            .code-block-title .code-lang {
                font-weight: 600;
                color: var(--md-text-color);
            }
            .code-block-title .code-title-text {
                color: var(--md-text-color);
            }
            .code-block-pre {
                margin: 0;
                border: 0;
                border-radius: 0;
                padding: 10px;
                line-height: 20px;
                overflow: auto;
                font-family: $CODE_FONT_STACK;
            }
            .code-block-pre code {
                display: block;
                min-width: max-content;
                font-size: 12.5px;
                color: var(--md-code-color);
            }
            .code-line { display: block; }
            .line-number {
                display: inline-block;
                min-width: 2.5em;
                margin-right: 12px;
                text-align: right;
                user-select: none;
                color: var(--md-line-number-color);
            }
            .line-content { white-space: pre-wrap; }
            .code-line.hl { background: var(--md-code-hl-bg); }
            $codeColorCss

            /* ─────────────── 图表（mermaid 渲染容器 / 源码降级容器） ─────────────── */
            .diagram { margin: 16px 0; }
            .mermaid {
                text-align: center;
                background-color: var(--md-code-bg);
                border-radius: 6px;
                padding: 12px;
                overflow-x: auto;
            }

            /* ─────────────── 键盘按键 ─────────────── */
            kbd {
                display: inline-block;
                font-family: $CODE_FONT_STACK;
                font-size: 11.5px;
                font-weight: 500;
                line-height: 1;
                color: var(--md-kbd-color);
                background: var(--md-kbd-bg);
                border: 1px solid var(--md-border-color);
                border-radius: 4px;
                padding: 2px 5px;
                vertical-align: middle;
            }

            /* ─────────────── 行内标记 ─────────────── */
            mark {
                background: var(--md-mark-bg);
                color: var(--md-mark-color);
                padding: 0 2px;
                border-radius: 2px;
            }
            del, s { text-decoration: line-through; }
            ins, u { text-decoration: underline; }
            sup { font-size: 11px; line-height: 0; }
            sub { font-size: 11px; line-height: 0; }
            abbr[title] { text-decoration: underline dotted; }

            /* ─────────────── 脚注 ─────────────── */
            sup a.footnote-ref, sup a.citation-ref {
                font-size: 11px;
                text-decoration: none;
            }
            div[id^="fn-"] {
                font-size: 11px;
                color: var(--md-footnote-color);
                border-top: 1px solid var(--md-border-color);
                padding-top: 8px;
                margin-bottom: 8px;
            }
            div[id^="fn-"] p { margin: 0; }

            /* ─────────────── 图片 / figure ─────────────── */
            img { max-width: 100%; }
            figure {
                margin: 16px 0;
                text-align: center;
            }
            figure img { max-width: 100%; }
            figcaption {
                font-size: 11px;
                color: var(--md-figcaption-color);
                margin-top: 6px;
                text-align: center;
            }

            /* ─────────────── admonition（色板对齐 Compose MarkdownTheme） ─────────────── */
            .admonition {
                border-left: 3px solid var(--md-border-color);
                background-color: var(--md-code-bg);
                border-radius: 6px;
                padding: 12px 16px;
                margin: 16px 0;
            }
            .admonition p:last-child { margin-bottom: 0; }
            .admonition-title {
                font-weight: 700;
                margin: 0 0 8px 0;
                font-size: 13px;
            }
            .admonition-title::before { content: "☰ "; }
            .admonition.note { border-left-color: #0969da; background-color: #ddf4ff; }
            .admonition.note .admonition-title { color: #0969da; }
            .admonition.note .admonition-title::before { content: "ℹ️ "; }
            .admonition.tip { border-left-color: #1a7f37; background-color: #dcffe4; }
            .admonition.tip .admonition-title { color: #1a7f37; }
            .admonition.tip .admonition-title::before { content: "💡 "; }
            .admonition.important { border-left-color: #8250df; background-color: #fbefff; }
            .admonition.important .admonition-title { color: #8250df; }
            .admonition.important .admonition-title::before { content: "❗ "; }
            .admonition.warning { border-left-color: #bf8700; background-color: #fff8c5; }
            .admonition.warning .admonition-title { color: #bf8700; }
            .admonition.warning .admonition-title::before { content: "⚠️ "; }
            .admonition.caution { border-left-color: #cf222e; background-color: #ffebe9; }
            .admonition.caution .admonition-title { color: #cf222e; }
            .admonition.caution .admonition-title::before { content: "🔴 "; }
            /* 深色主题覆盖（data-theme=dark 激活时生效，色值对齐 Compose darkAdmonitionStyles） */
            html[data-theme="dark"] .admonition.note { border-left-color: #4493f8; background-color: #0d1d30; }
            html[data-theme="dark"] .admonition.note .admonition-title { color: #4493f8; }
            html[data-theme="dark"] .admonition.tip { border-left-color: #3fb950; background-color: #0d2818; }
            html[data-theme="dark"] .admonition.tip .admonition-title { color: #3fb950; }
            html[data-theme="dark"] .admonition.important { border-left-color: #ab7df8; background-color: #1b1030; }
            html[data-theme="dark"] .admonition.important .admonition-title { color: #ab7df8; }
            html[data-theme="dark"] .admonition.warning { border-left-color: #d29922; background-color: #2a1f00; }
            html[data-theme="dark"] .admonition.warning .admonition-title { color: #d29922; }
            html[data-theme="dark"] .admonition.caution { border-left-color: #f85149; background-color: #300c0c; }
            html[data-theme="dark"] .admonition.caution .admonition-title { color: #f85149; }

            /* ─────────────── 自定义容器 ─────────────── */
            .custom-container {
                border: 1px solid var(--md-border-color);
                border-radius: 6px;
                padding: 12px 16px;
                margin: 16px 0;
            }
            .custom-container-title { font-weight: 700; margin: 0 0 8px 0; }

            /* ─────────────── tab 标签页（静态全展开，tab 栏为视觉标签列表） ─────────────── */
            .tab-block {
                margin: 16px 0;
                border: 1px solid var(--md-border-color);
                border-radius: 6px;
                overflow: hidden;
            }
            .tab-bar {
                display: flex;
                flex-wrap: wrap;
                gap: 4px;
                background-color: var(--md-code-bg);
                border-bottom: 1px solid var(--md-border-color);
                padding: 4px 8px;
            }
            .tab-label {
                font-size: 12px;
                font-weight: 600;
                padding: 4px 12px;
                border-radius: 4px;
                background-color: var(--md-bg);
                border: 1px solid var(--md-border-color);
            }
            .tab-item { padding: 12px 16px; border-bottom: 1px solid var(--md-border-color); }
            .tab-item:last-child { border-bottom: none; }

            /* ─────────────── 多列布局（显式 width → inline flex-basis；无 width → 均分） ─────────────── */
            .columns-layout { display: flex; flex-direction: row; gap: 8px; margin: 16px 0; }
            .column-item { flex: 1 1 0; min-width: 0; }

            /* ─────────────── TOC 目录 ─────────────── */
            .table-of-contents {
                background-color: var(--md-code-bg);
                border: 1px solid var(--md-border-color);
                border-radius: 6px;
                padding: 12px 16px;
                margin: 16px 0;
            }
            .toc-title { font-weight: 700; margin: 0 0 8px 0; font-size: 15px; }
            .toc-list, .toc-list ul { list-style: none; padding-left: 0; margin: 0; }
            .toc-list ul { padding-left: 24px; }
            .toc-list a { color: var(--md-link-color); text-decoration: none; }

            /* ─────────────── 注音 / ruby ─────────────── */
            ruby { ruby-align: center; }
            ruby rt {
                font-size: 7px;
                color: var(--md-blockquote-text);
            }

            /* ─────────────── 剧透（占位；交互遮罩由后续子任务完善） ─────────────── */
            .spoiler {
                color: transparent;
                background-color: var(--md-spoiler-bg);
                border-radius: 2px;
            }
            /* hover 揭示（屏幕交互）+ 打印始终显示（PDF 完整性） */
            .spoiler:hover { color: inherit; background-color: transparent; }
            @media print { .spoiler { color: inherit; background-color: transparent; } }

            /* Math & Diagram */
            .math-display {
                text-align: center;
                margin: 16px 0;
                overflow-x: auto;
                background-color: var(--md-code-bg);
                border-radius: 6px;
                padding: 12px 16px;
                font-size: var(--md-body-size);
            }
            .math-inline { font-size: var(--md-body-size); }
            .math-inline math, .math-display math { font-size: 1.15em; }

            /* Vertical Text (vlr) Container */
            .vtext, .vertical-text {
                background-color: var(--md-code-bg);
                border-radius: 6px;
                padding: 16px;
                margin-bottom: 16px;
                border: 1px solid var(--md-border-color);
                /* 容器级列距默认（与 Compose verticalTextStyle 21.5sp lineHeight 对齐）；
                   元素 inline style 的 line-height 优先级更高，显式 fontSize 时由 inline 覆盖 */
                line-height: 21.5px;
                letter-spacing: 0;
            }

            /* Page Break */
            .page-break {
                display: block;
                height: 0;
                page-break-after: always;
                break-after: page;
            }

            /* Print Media Settings */
            @media print {
                body { padding: 0; }
                pre, code, table, img, .admonition, .math-display, .vtext {
                    break-inside: avoid;
                    page-break-inside: avoid;
                }
            }
            """.trimIndent()
        )
        if (customCss.isNotBlank()) {
            append("\n\n")
            append(customCss)
        }
    }
}
