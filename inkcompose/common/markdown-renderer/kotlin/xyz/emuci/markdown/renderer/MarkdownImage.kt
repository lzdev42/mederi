package xyz.emuci.markdown.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import xyz.emuci.inkcompose.InkImage

/**
 * 图片渲染所需的数据模型。
 *
 * @param url 图片 URL
 * @param altText 替代文本（无障碍）
 * @param title 图片标题（tooltip）
 * @param width 指定宽度（像素），null 表示自适应
 * @param height 指定高度（像素），null 表示自适应
 * @param attributes 额外属性（class, id, loading, align 等）
 */
@Immutable
data class MarkdownImageData(
    val url: String,
    val altText: String,
    val title: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    /** 获取对齐方式 */
    val align: String?
        get() = attributes["align"]

    /** 获取 loading 模式（lazy/eager） */
    val loading: String?
        get() = attributes["loading"]

    /** 获取 CSS class 列表 */
    val cssClasses: List<String>
        get() = attributes["class"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()

    /** 获取 CSS ID */
    val cssId: String?
        get() = attributes["id"]
}

/**
 * 图片渲染器的类型别名。
 * 外部可以通过提供自定义实现来替换默认的图片加载逻辑。
 */
typealias MarkdownImageRenderer = @Composable (data: MarkdownImageData, modifier: Modifier) -> Unit

/**
 * 用于在组件树中传递自定义图片渲染器的 CompositionLocal。
 *
 * 默认值为 null，表示使用内置的默认渲染器（纯文本占位显示）。
 * 外部调用者可以通过 [Markdown] 的 `imageContent` 参数提供自定义实现，
 * 例如使用 Coil、Kamel、Ktor 等图片加载库。
 *
 * 使用示例：
 * ```kotlin
 * Markdown(
 *     markdown = "![alt](https://example.com/image.png =200x300)",
 *     imageContent = { data, modifier ->
 *         // 使用 Coil 或其他图片加载库
 *         AsyncImage(
 *             model = data.url,
 *             contentDescription = data.altText,
 *             modifier = modifier,
 *         )
 *     },
 * )
 * ```
 */
internal val LocalImageRenderer = compositionLocalOf<MarkdownImageRenderer?> { null }

/**
 * 默认的图片渲染组件。
 *
 * 委托 [InkImage]（InkCompose 统一图片组件）：网络 / data: base64 / 本地文件 / SVG
 * 一体支持，加载失败显示可见占位（替代文本），不静默空白。
 *
 * 外部使用者也可通过 [Markdown] 的 `imageContent` 参数传入自定义图片加载实现来覆盖此行为。
 */
@Composable
internal fun DefaultMarkdownImage(
    data: MarkdownImageData,
    modifier: Modifier = Modifier,
) {
    val alignment = when (data.align?.lowercase()) {
        "left" -> Alignment.CenterStart
        "right" -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment,
    ) {
        InkImage(
            model = data.url,
            contentDescription = data.altText.ifEmpty { data.title },
            modifier = Modifier
                .applyImageSize(data.width, data.height)
                .onGloballyPositioned { coordinates ->
                    val widthDp = with(density) { coordinates.size.width.toDp() }
                    val heightDp = with(density) { coordinates.size.height.toDp() }
                    println("[DefaultMarkdownImage] Rendered size: ${coordinates.size.width}x${coordinates.size.height} px (${widthDp}x${heightDp}), specified: ${data.width}x${data.height}")
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 根据图片数据的宽高约束应用 Modifier。
 * - 未设定尺寸时保持自身尺寸（不强制 fillMaxWidth），真实尺寸显示；
 *   超出容器时由父级最大宽度约束自动等比缩放适配。
 * - 设定尺寸时应用指定的尺寸约束。
 */
internal fun Modifier.applyImageSize(width: Int?, height: Int?): Modifier {
    return when {
        width != null && height != null -> this.size(width.dp, height.dp)
        width != null -> this.width(width.dp)
        height != null -> this.height(height.dp)
        else -> this
    }
}

