package xyz.emuci.markdown.renderer.inline

import androidx.compose.ui.text.buildAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InlineRenderModelsTest {
    @Test
    fun should_build_inline_widget_paint_payload_with_placeholder_spec() {
        val payload = inlineWidgetPaintPayload(
            alternateText = "latex",
            widthPx = 12f,
            heightPx = 18f,
        ) { }

        val spec = payload.placeholder

        assertEquals("latex", spec.alternateText)
        assertEquals(12f, spec.widthPx)
        assertEquals(18f, spec.heightPx)
    }

    @Test
    fun should_decode_inline_placeholder_ids_from_annotations() {
        val annotated = buildAnnotatedString {
            append("prefix ")
            appendInlinePlaceholder(InlinePlaceholderId(42))
            append(" suffix")
        }

        assertEquals(listOf(InlinePlaceholderId(42)), annotated.getInlinePlaceholderIds())
    }

    @Test
    fun should_decode_inline_placeholder_ranges_from_annotations() {
        val annotated = buildAnnotatedString {
            append("ab")
            appendInlinePlaceholder(InlinePlaceholderId(7))
            append("cd")
        }

        assertEquals(
            listOf(
                InlinePlaceholderAnnotationRange(
                    id = InlinePlaceholderId(7),
                    start = 2,
                    end = 3,
                )
            ),
            annotated.getInlinePlaceholderRanges(),
        )
    }

    @Test
    fun should_return_null_when_placeholder_annotation_is_invalid() {
        assertNull(InlinePlaceholderId.fromAnnotation("not-a-number"))
    }
}
