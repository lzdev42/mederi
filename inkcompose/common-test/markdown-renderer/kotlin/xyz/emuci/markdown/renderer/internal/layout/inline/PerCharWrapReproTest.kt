package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 复现"推理内容一行一个字母"：
 * 段落 "Actually, let me try one more search - ..." 在真实宽度下被逐字符断行。
 * 先用正常宽度验证 flow 引擎本身不逐字符断行（若此测试失败 = 引擎 bug；
 * 若通过 = 问题在更上游的宽度传递/缓存/AST 层）。
 */
@OptIn(ExperimentalTestApi::class)
class PerCharWrapReproTest {

    private val actualParagraph =
        "Actually, let me try one more search - maybe I can find information about the " +
            "Ming's campaigns against the Jurchen during the Chenghua period on a different page."

    @Test
    fun should_not_break_normal_english_paragraph_per_char_at_full_width() = runComposeUiTest {
        var layout: InlineFlowLayout? = null
        var maxWidthPx = 0f

        setContent {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            // 与推理块一致：fontSize 12.5sp 左右，lineHeight ~21sp（43px @2x scale）
            val style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.5.sp)
            val densityScale = density.density
            maxWidthPx = 1906f / densityScale // 逻辑 px
            layout = computeInlineFlowLayout(
                input = InlineFlowInput(
                    listOf(InlineFlowSegment.TextRun(AnnotatedString(actualParagraph)))
                ),
                style = style,
                density = density,
                textMeasurer = textMeasurer,
                maxWidthPx = maxWidthPx,
                maxLines = Int.MAX_VALUE,
            )
        }

        waitForIdle()

        val actual = assertNotNull2(layout)
        // 正常宽度下：整段应折成少数几行（每行几十个字符），而不是每行 1 个字符
        val avgCharsPerLine = actualParagraph.length / actual.lines.size
        assertTrue(
            avgCharsPerLine > 10,
            "Expected ~${actualParagraph.length} chars across few lines, " +
                "got ${actual.lines.size} lines (avg $avgCharsPerLine chars/line) — " +
                "per-char wrapping reproduced!",
        )
    }

    @Test
    fun should_not_break_per_char_when_text_run_contains_cjk_then_ascii_paragraph() = runComposeUiTest {
        // 模拟真实文档：CJK 段落 + ASCII 段落连续 TextRun（同一段落内混合，以及段落切换）
        var layout: InlineFlowLayout? = null

        setContent {
            layout = computeInlineFlowLayout(
                input = InlineFlowInput(
                    listOf(
                        InlineFlowSegment.TextRun(
                            AnnotatedString(
                                "The user is pointing out that I should look up \"成化犁庭\" specifically, " +
                                    "and that my search ability is not great.",
                            ),
                        ),
                        InlineFlowSegment.Newline,
                        InlineFlowSegment.Newline,
                        InlineFlowSegment.TextRun(AnnotatedString(actualParagraph)),
                    )
                ),
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.5.sp),
                density = LocalDensity.current,
                textMeasurer = rememberTextMeasurer(),
                maxWidthPx = 953f, // 逻辑 px（1906 物理 @2x）
                maxLines = Int.MAX_VALUE,
            )
        }

        waitForIdle()

        val actual = assertNotNull2(layout)
        val perCharLines = actual.lines.count { line ->
            val textLen = line.items.filterIsInstance<LineItem.TextItem>()
                .sumOf { it.text.text.length }
            textLen in 1..2
        }
        assertTrue(
            perCharLines < 10,
            "Expected no per-char lines, got $perCharLines of ${actual.lines.size} — reproduced!",
        )
    }

    @Test
    fun should_reproduce_with_exact_178_char_sentence() = runComposeUiTest {
        val text178 = "Also, the user mentioned \"丙子胡乱\" - this is the 1636 Manchu invasion of Korea (the Second Manchu invasion of Korea). I already found information about this from the Joseon article."
        var layout: InlineFlowLayout? = null

        setContent {
            val density = androidx.compose.ui.unit.Density(2f)
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
                val textMeasurer = rememberTextMeasurer()
                val style = TextStyle(fontSize = 13.0.sp, lineHeight = 21.5.sp)
                
                // Direct measure check
                val directMeasure = textMeasurer.measure(
                    text = AnnotatedString(text178),
                    style = style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = 1940),
                    maxLines = Int.MAX_VALUE,
                    softWrap = true,
                )
                println("[DirectMeasure @2x] lineCount=${directMeasure.lineCount} size=${directMeasure.size}")
                for (i in 0 until minOf(10, directMeasure.lineCount)) {
                    println("  direct line $i: start=${directMeasure.getLineStart(i)} end=${directMeasure.getLineEnd(i)} text=${text178.substring(directMeasure.getLineStart(i), directMeasure.getLineEnd(i))}")
                }

                val seg0 = text178.substring(0, 154)
                val seg0Meas = textMeasurer.measure(
                    text = AnnotatedString(seg0),
                    style = style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = Int.MAX_VALUE),
                    maxLines = 1,
                    softWrap = false,
                )
                println("[Seg0 Measure] widthPx=${seg0Meas.size.width} maxWidthPx=1940 diff=${seg0Meas.size.width - 1940}")

                layout = computeInlineFlowLayout(

                    input = InlineFlowInput(
                        listOf(InlineFlowSegment.TextRun(AnnotatedString(text178)))
                    ),
                    style = style,
                    density = density,
                    textMeasurer = textMeasurer,
                    maxWidthPx = 1940f,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }

        waitForIdle()

        val actual = assertNotNull2(layout)
        assertEquals(2, actual.lines.size, "Expected 2 lines for 178 chars at 2x density, got ${actual.lines.size}")
    }



    private fun assertNotNull2(layout: InlineFlowLayout?): InlineFlowLayout =
        requireNotNull(layout) { "layout was not computed" }
}

