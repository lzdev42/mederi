package xyz.emuci.diagram

import androidx.compose.runtime.Immutable

/**
 * Mermaid 源码检测结果：用于 Markdown 代码块 / 流式前缀的三态路由。
 *
 * ```kotlin
 * val detection = MermaidSourceDetector.detect(fenceBody, hint = fenceInfo)
 * if (detection.shouldRouteToDiagram) DiagramBlockView(source = fenceBody)
 * ```
 */
@Immutable
data class MermaidSourceDetection(
    val status: MermaidSourceStatus,
    val reason: String,
) {
    /** True when the block is ready to hand to the mermaid renderer. */
    val isDiagram: Boolean get() = status == MermaidSourceStatus.DIAGRAM

    /** True when a stream prefix strongly suggests a mermaid diagram but needs more text. */
    val isPending: Boolean get() = status == MermaidSourceStatus.PENDING

    /** True when a Markdown container should reserve this block for diagram rendering. */
    val shouldRouteToDiagram: Boolean get() = isDiagram || isPending
}

/**
 * 三态检测结果。
 */
@Immutable
enum class MermaidSourceStatus {
    DIAGRAM,
    PENDING,
    NOT_DIAGRAM,
}

/**
 * Mermaid-only 源码检测器（原 diagram-render DiagramSourceDetector 的裁剪版：
 * 原生 PlantUML/DOT 管线已删除，PlantUML/DOT 语法不再路由到图表渲染）。
 */
internal object MermaidSourceDetector {

    fun detect(source: CharSequence, hint: String? = null): MermaidSourceDetection {
        if (languageFromHint(hint)) {
            return MermaidSourceDetection(
                status = MermaidSourceStatus.DIAGRAM,
                reason = "matched code fence hint '${hint.orEmpty().trim()}'",
            )
        }

        val first = firstSignificantLine(source)
            ?: return MermaidSourceDetection(
                status = MermaidSourceStatus.NOT_DIAGRAM,
                reason = "no significant source line",
            )
        val lower = first.lowercase()

        val mermaid = detectMermaid(lower)
        if (mermaid != null) return mermaid

        return MermaidSourceDetection(MermaidSourceStatus.NOT_DIAGRAM, "no mermaid header or hint")
    }

    private fun languageFromHint(hint: String?): Boolean {
        val token = hint
            ?.trim()
            ?.removePrefix("```")
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim('{', '}', '.', '`')
            ?.lowercase()
            ?: return false
        return token == "mermaid" || token == "mmd"
    }

    private fun firstSignificantLine(source: CharSequence): String? =
        source.toString()
            .lineSequence()
            .map { it.trimStart() }
            .firstOrNull { it.isNotBlank() && !it.isLeadingComment() }

    private fun String.isLeadingComment(): Boolean =
        startsWith("%%") || startsWith("'") || startsWith("//") || startsWith("#")

    private fun detectMermaid(line: String): MermaidSourceDetection? {
        val firstWord = line.takeWhile { !it.isWhitespace() }
        if (firstWord == "graph") {
            val rest = line.removePrefix("graph").trimStart()
            val direction = rest.takeWhile { !it.isWhitespace() }
            return if (direction in MERMAID_GRAPH_DIRECTIONS) {
                MermaidSourceDetection(MermaidSourceStatus.DIAGRAM, "matched Mermaid graph header")
            } else if (rest.isEmpty()) {
                MermaidSourceDetection(MermaidSourceStatus.PENDING, "partial Mermaid graph header")
            } else {
                null
            }
        }
        return if (firstWord in MERMAID_HEADERS) {
            MermaidSourceDetection(MermaidSourceStatus.DIAGRAM, "matched Mermaid '$firstWord' header")
        } else {
            null
        }
    }

    private val MERMAID_GRAPH_DIRECTIONS = setOf("tb", "td", "bt", "rl", "lr")

    private val MERMAID_HEADERS = setOf(
        "flowchart",
        "sequencediagram",
        "classdiagram",
        "statediagram",
        "statediagram-v2",
        "erdiagram",
        "journey",
        "gantt",
        "pie",
        "gauge",
        "gitgraph",
        "mindmap",
        "timeline",
        "requirementdiagram",
        "architecture-beta",
        "c4context",
        "c4container",
        "c4component",
        "c4dynamic",
        "c4deployment",
        "block-beta",
        "block",
        "packet-beta",
        "kanban",
        "xychart-beta",
        "quadrantchart",
        "sankey-beta",
    )
}
