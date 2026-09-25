package xyz.emuci.markdown.renderer.internal.layout.inline

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 二分"一行一个字母"触发条件。
 * 假设 H1：maxWidthPx 退化（NaN → ceil(NaN).toInt()=0 → Constraints(maxWidth=1)）时逐字符断行。
 * 验证：maxWidthPx = Float.NaN 是否复现，以及边界保护是否被 NaN 绕过。
 */
@OptIn(ExperimentalTestApi::class)
class PerCharNanWidthTest {

    private val text =
        "Actually, let me try one more search - maybe I can find information about the " +
            "Ming's campaigns against the Jurchen during the Chenghua period on a different page."

    @Test
    fun nan_maxWidth_should_not_produce_per_char_lines() = runComposeUiTest {
        var layout: InlineFlowLayout? = null

        setContent {
            layout = computeInlineFlowLayout(
                input = InlineFlowInput(
                    listOf(InlineFlowSegment.TextRun(AnnotatedString(text)))
                ),
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.5.sp),
                density = LocalDensity.current,
                textMeasurer = rememberTextMeasurer(),
                maxWidthPx = Float.NaN,
                maxLines = Int.MAX_VALUE,
            )
        }

        waitForIdle()

        val actual = requireNotNull(layout) { "layout not computed" }
        // NaN 情况下不允许出现大量单字符行
        val perCharLines = actual.lines.count { line ->
            val len = line.items.filterIsInstance<LineItem.TextItem>().sumOf { it.text.text.length }
            len <= 1
        }
        assertTrue(
            perCharLines < 5,
            "NaN width produced $perCharLines/${actual.lines.size} single-char lines — bug reproduced",
        )
    }

    @Test
    fun huge_maxWidth_overflowing_toInt_should_not_produce_per_char_lines() = runComposeUiTest {
        // 疑点：ceil(maxWidthPx).toInt() 对超大 float 溢出为负 → coerceAtLeast(1) → 1px
        var layout: InlineFlowLayout? = null

        setContent {
            layout = computeInlineFlowLayout(
                input = InlineFlowInput(
                    listOf(InlineFlowSegment.TextRun(AnnotatedString(text)))
                ),
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 21.5.sp),
                density = LocalDensity.current,
                textMeasurer = rememberTextMeasurer(),
                maxWidthPx = 3.0e9f, // > Int.MAX_VALUE，ceil().toInt() 溢出
                maxLines = Int.MAX_VALUE,
            )
        }

        waitForIdle()

        val actual = requireNotNull(layout) { "layout not computed" }
        val perCharLines = actual.lines.count { line ->
            val len = line.items.filterIsInstance<LineItem.TextItem>().sumOf { it.text.text.length }
            len <= 1
        }
        assertTrue(
            perCharLines < 5,
            "overflow width produced $perCharLines/${actual.lines.size} single-char lines — bug reproduced",
        )
    }
}
