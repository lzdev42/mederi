package xyz.emuci.vtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font
import xyz.emuci.inkcompose.resources.Res
import xyz.emuci.inkcompose.resources.NotoSansMongolian_Regular

/**
 * 竖排文字渲染组件（只读）。
 *
 * 支持蒙古文、满文、汉字、日文、韩文、英文等混排，竖排显示。
 * 蒙古文/满文等原生竖排字符随列旋转，保持自然连写形态；
 * 汉字、假名、韩文等直立字符在旋转后反向补偿，保持直立。
 *
 * 自动使用随库打包的 Noto Sans Mongolian 字体渲染蒙古文/满文区段，
 * 也可通过 [config] 参数指定自定义字体。
 *
 * 用法：
 * ```
 * VTextView("ᠮᠣᠩᠭᠣᠯ ᠪᠢᠴᠢᠭ\n汉字 mixed text")
 * ```
 *
 * @param text 文本内容
 * @param modifier Compose modifier
 * @param style 文字样式（字号、颜色等）
 * @param config 竖排渲染配置，默认使用打包的蒙古文字体
 */
@Composable
fun VTextView(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    config: VTextConfig = VTextConfig(),
) {
    // 加载随库打包的 Noto Sans Mongolian 字体
    val bundledFontFamily = FontFamily(Font(Res.font.NotoSansMongolian_Regular))
    val effectiveFont = config.verticalFontFamily ?: bundledFontFamily

    val internalConfig = remember(effectiveFont, config) {
        VTextInternalConfig(
            verticalFontFamily = effectiveFont,
            ascentTrim = config.ascentTrim,
            baselineShift = config.baselineShift,
            fixedWidth = config.fixedWidth,
            columnSpacing = config.columnSpacing,
        )
    }

    VerticalText(
        text = text,
        modifier = modifier,
        style = style,
        config = internalConfig,
    )
}
