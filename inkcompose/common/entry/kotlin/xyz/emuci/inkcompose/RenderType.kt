package xyz.emuci.inkcompose

/**
 * 渲染类型。
 *
 * - [AUTO]:通用模式。任何字符串都走 Markdown 解析——纯文本是其合法子集,
 *   代码块/mermaid/latex 作为 markdown 语法块被自动识别并分发到对应子渲染器。
 *   就像网页一样:文本里有什么就渲染什么。
 *
 * - 其他值:针对模式。跳过 Markdown 解析,强制按指定类型渲染。
 *   用于明确知道内容类型的场景(如独立的图表查看器、公式预览、代码片段查看器)。
 *
 * 原则:只保证正确解析"正确"的字符串。输入本身有语法错误,按库默认行为处理,不做纠错。
 */
enum class RenderType {
    /**
     * 自动识别(默认)。永远走 Markdown parser,由它分发到子渲染器。
     * 纯文本、markdown、含代码块/公式/图表的混合内容都能正确渲染。
     */
    AUTO,

    /**
     * 纯文本。不做任何解析,直接当 Text 渲染。
     * 用于明确知道内容不含任何 markdown 语法、且需要最高性能的场景。
     */
    TEXT,

    /**
     * Markdown(含 LaTeX/Mermaid/Code 等 markdown 语法块)。
     * 与 [AUTO] 的区别:语义上明确声明"这是 markdown",未来若 AUTO 增加启发式分流,
     * MARKDOWN 仍保证走完整 Markdown 解析。
     */
    MARKDOWN,

    /**
     * LaTeX 公式。整段当公式源码渲染,不走 markdown。
     * 例:"\frac{1}{2}" 会被渲染成二分之一。
     */
    LATEX,

    /**
     * Mermaid 图表。整段当 mermaid 源码渲染。
     * 例:"graph TD\nA-->B"
     */
    MERMAID,

    /**
     * 竖排文字。整段当竖排文字渲染（蒙古文/满文/汉字混排）。
     * 例:"ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ"
     */
    VLR,

    /**
     * 代码高亮。整段当代码渲染,需配合 [MarkdownView.language] 参数指定语言。
     */
    CODE
}
