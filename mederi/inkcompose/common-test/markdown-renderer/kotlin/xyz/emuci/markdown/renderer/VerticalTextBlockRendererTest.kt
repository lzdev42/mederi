package xyz.emuci.markdown.renderer

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import xyz.emuci.markdown.renderer.block.RenderVerticalTextBlockWidgetModel
import xyz.emuci.markdown.renderer.internal.core.identity.RenderIdentity
import xyz.emuci.markdown.renderer.internal.core.model.VerticalTextBlockWidgetModel
import xyz.emuci.vtext.VTextConfig
import xyz.emuci.vtext.VTextView
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class VerticalTextBlockRendererTest {

    @Test
    fun renderVerticalTextBlockUnderDarkThemeDoesNotCrashAndAppliesDarkStyle() = runComposeUiTest {
        val darkTheme = MarkdownTheme.dark()
        setContent {
            ProvideMarkdownTheme(darkTheme) {
                RenderVerticalTextBlockWidgetModel(
                    model = VerticalTextBlockWidgetModel(
                        identity = RenderIdentity(
                            stableId = 1L,
                            contentRevision = 1,
                            layoutRevision = 1,
                            paintRevision = 1L,
                        ),
                        text = "ᠮᠣᠩᠭᠣᠯ",
                    ),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun vTextViewRespectsLocalContentColorWhenStyleColorUnspecified() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalContentColor provides Color.Cyan) {
                VTextView(
                    text = "ᠮᠣᠩᠭᠣᠯ",
                    config = VTextConfig(),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun markdownWithVlrRendersUnderDarkTheme() = runComposeUiTest {
        val markdown = """
            # Header
            ```vlr
            ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ
            汉字竖排
            ```
        """.trimIndent()

        setContent {
            Markdown(
                markdown = markdown,
                theme = MarkdownTheme.dark(),
            )
        }
        waitForIdle()
    }
}
