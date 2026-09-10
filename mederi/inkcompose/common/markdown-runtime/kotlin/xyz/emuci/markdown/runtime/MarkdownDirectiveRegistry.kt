package xyz.emuci.markdown.runtime
class MarkdownDirectiveRegistry(
    directivePlugins: List<MarkdownDirectivePlugin>,
) {
    val directivePlugins: List<MarkdownDirectivePlugin> = directivePlugins
        .sortedWith(compareBy<MarkdownDirectivePlugin> { it.priority }.thenBy { it.id })
        .toList()

    private val blockRenderers = LinkedHashMap<String, MarkdownBlockDirectiveRenderer>()
    private val inlineRenderers = LinkedHashMap<String, MarkdownInlineDirectiveRenderer>()
    private val htmlFallbacks = LinkedHashMap<String, HtmlDirectiveFallback>()
    private val htmlInlineFallbacks = LinkedHashMap<String, HtmlInlineDirectiveFallback>()
    private val transformers = mutableListOf<MarkdownInputTransformer>()

    /**
     * 当前实现只在“没有 transformer”时保留 streaming fast path。
     */
    val supportsStreamingFastPath: Boolean
        get() = transformers.isEmpty()

    init {
        for (plugin in this.directivePlugins) {
            transformers += plugin.inputTransformers
            for ((tagName, renderer) in plugin.blockDirectiveRenderers) {
                blockRenderers[tagName] = renderer
            }
            for ((tagName, renderer) in plugin.inlineDirectiveRenderers) {
                inlineRenderers[tagName] = renderer
            }
            for ((tagName, fallback) in plugin.htmlDirectiveFallbacks) {
                htmlFallbacks[tagName] = fallback
            }
            for ((tagName, fallback) in plugin.htmlInlineDirectiveFallbacks) {
                htmlInlineFallbacks[tagName] = fallback
            }
        }
    }

    fun inputTransformers(): List<MarkdownInputTransformer> = transformers.toList()

    fun findBlockDirectiveRenderer(tagName: String): MarkdownBlockDirectiveRenderer? = blockRenderers[tagName]

    fun findInlineDirectiveRenderer(tagName: String): MarkdownInlineDirectiveRenderer? = inlineRenderers[tagName]

    fun findHtmlDirectiveFallback(tagName: String): HtmlDirectiveFallback? = htmlFallbacks[tagName]

    fun findHtmlInlineDirectiveFallback(tagName: String): HtmlInlineDirectiveFallback? = htmlInlineFallbacks[tagName]

    companion object {
        val Empty = MarkdownDirectiveRegistry(emptyList())
    }
}
