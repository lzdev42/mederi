package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import xyz.emuci.markdown.parser.MarkdownParser
import xyz.emuci.markdown.renderer.DiagramHostRegistry
import xyz.emuci.markdown.renderer.MarkdownConfig
import xyz.emuci.markdown.renderer.MarkdownTheme
import xyz.emuci.markdown.renderer.internal.MarkdownEngineHost
import xyz.emuci.markdown.renderer.internal.RendererFacadeState
import xyz.emuci.markdown.renderer.internal.core.model.ParagraphBlockModel
import xyz.emuci.latex.renderer.measure.LatexMeasurerState
import xyz.emuci.latex.renderer.model.defaultLatexFontFamilies
import xyz.emuci.markdown.runtime.MarkdownDirectiveRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 全管线复现：真实推理 markdown（msg_0ceaf223 reasoning）→ parse → compile → layout，
 * 检验最后一段 "Actually, let me try one more search..." 是否被逐字符断行。
 */
@OptIn(ExperimentalTestApi::class)
class ReasoningFullPipelineReproTest {

    private val reasoningMarkdown = """
        The search didn't return anything. Let me try a different approach - search for the Ming's campaigns against the Jurchen during the Chenghua period.

        Actually, I think I should just be honest with the user. My search ability is limited, and I'm not finding the specific details about "成化犁庭/砺庭." The user is right that I should look up these events, but I'm struggling to find the specific information.

        Let me summarize what I've found and what I'm missing:

        1. **成化犁庭/砺庭**: I can't find specific details about this event. The Chinese Wikipedia page "成化砺庭" doesn't exist. I need to search for this term more specifically.

        2. **萨尔浒之战**: I found good details - 1619, Later Jin 60,000 vs. Ming 90,000-120,000 + Joseon 13,000, Later Jin victory, 45,000 Ming/Joseon casualties.

        3. **丙子胡乱**: I found details from the Joseon article - 1636, Hong Taiji sent 120,000 men to Joseon, King Injo was defeated and forced to submit to the Qing.

        The user is pointing out that I should look up "成化犁庭" specifically, and that my search ability is not great. Let me be honest about this and ask the user to help me understand what "成化犁庭" specifically refers to.

        Actually, let me try one more search - maybe I can find information about the Ming's campaigns against the Jurchen during the Chenghua period on a different page.
    """.trimIndent()

    @Test
    fun full_pipeline_should_not_wrap_last_paragraph_per_char() = runComposeUiTest {
        var perCharCount: Int? = null
        var totalBlocks = 0

        setContent {
            val textMeasurer = rememberTextMeasurer()
            // 与桌面端一致：density 2.0（截图 y 间距 43px = 21.5sp×2），viewport 1940 物理像素
            val density = Density(2f)
            val theme = MarkdownTheme.material3()
            val facade = RendererFacadeState(
                theme = theme,
                config = MarkdownConfig.Default,
                codeTheme = null,
                imageRenderer = null,
                onLinkClick = null,
                directiveRegistry = MarkdownDirectiveRegistry(emptyList()),
                isStreaming = false,
                enableSelection = true,
            )
            val host = MarkdownEngineHost()
            val document = MarkdownParser().parse(reasoningMarkdown)
            val renderDocument = host.compile(document, facade)
            val latexMeasurer = LatexMeasurerState(density, textMeasurer, defaultLatexFontFamilies())
            val layoutDoc = host.layout(
                renderDocument = renderDocument,
                facadeState = facade,
                viewportWidth = 1940f,
                density = density,
                textMeasurer = textMeasurer,
                latexMeasurer = latexMeasurer,
                diagramHostRegistry = DiagramHostRegistry(),
            )
            totalBlocks = layoutDoc.blocks.size


            val lastParagraph = layoutDoc.blocks.filterIsInstance<xyz.emuci.markdown.renderer.internal.layout.model.LayoutInlineBlockModel>()
                .lastOrNull { block ->
                    val firstLineText = block.lines.firstOrNull()?.runs
                        ?.filterIsInstance<xyz.emuci.markdown.renderer.internal.layout.model.LayoutTextRun>()
                        ?.firstOrNull()?.text?.text
                    firstLineText?.startsWith("Actually, let me try one more search") == true
                }
            perCharCount = lastParagraph?.lines?.count { line ->
                val len = line.runs.filterIsInstance<xyz.emuci.markdown.renderer.internal.layout.model.LayoutTextRun>()
                    .sumOf { it.text.text.length }
                len <= 1
            } ?: -1
        }

        waitForIdle()

        assertTrue(totalBlocks > 0, "no blocks laid out")
        val actual = requireNotNull(perCharCount) { "target paragraph not found in layout" }
        assertTrue(
            actual < 5,
            "reproduced: last paragraph has $actual single-char lines — 一行一个字母 reproduced in full pipeline",
        )
    }
}
