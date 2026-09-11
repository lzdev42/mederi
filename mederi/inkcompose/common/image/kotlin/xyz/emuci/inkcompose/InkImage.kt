package xyz.emuci.inkcompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.Decoder
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * 各平台 actual：把路径开头的 `~` 展开为用户主目录（jvm/ios 有意义，android/wasm 原样返回）。
 */
internal expect fun inkExpandUserHome(path: String): String

/**
 * 各平台 actual：SVG 解码器工厂（全平台 = skia SVGDOM，wasm 构建含 SVG 模块；
 * android 返回 null 由 coil-svg 经 ServiceLoader 自动注册）。
 */
internal expect fun inkSvgDecoderFactory(): Decoder.Factory?

/**
 * 图片来源归一化：
 * - `~` / `~/...` → 展开用户主目录后转 `file://`
 * - 无 scheme 的 POSIX 绝对路径（`/...`）→ `file://`
 * - Windows 盘符路径（`C:\...` / `D:/...`）→ `file:///`（反斜杠统一转正斜杠）
 * - 其余（http(s)、file://、data:、content:、res:、jar: 等）原样交给 Coil
 */
internal fun inkNormalizeImageModel(model: String): String {
    val p = model.trim()
    return when {
        p.isEmpty() -> p
        p == "~" || p.startsWith("~/") -> "file://" + inkExpandUserHome(p)
        p.startsWith("/") -> "file://$p"
        // Windows 盘符：C:\ 或 C:/（第一个字符是字母，第二个是冒号）
        p.length >= 3 && p[0].isLetter() && p[1] == ':' && (p[2] == '/' || p[2] == '\\') ->
            "file:///" + p.replace('\\', '/')
        else -> p
    }
}

// 进程级共享 loader（只在 UI 线程创建，无需并发原语）。
// 自定义 loader 会自动并入 ServiceLoader 注册的组件（网络 fetcher、Android coil-svg 解码器等）。
private var inkImageLoader: ImageLoader? = null

private fun inkImageLoader(context: coil3.PlatformContext): ImageLoader =
    inkImageLoader ?: ImageLoader.Builder(context)
        .components { inkSvgDecoderFactory()?.let(::add) }
        .crossfade(false)
        .build()
        .also { inkImageLoader = it }

/**
 * InkCompose 统一图片组件。
 *
 * 一个入口吃下所有图片来源，宿主不必按来源挑选加载方式：
 * - 网络 URL（http/https，含 ktor 引擎，见 inkcompose 依赖说明）
 * - base64 data URI（`data:image/...;base64,...`，聊天附件即此形态）
 * - 本地文件（`file://`、`/abs/path`、`~/path`、`C:\path`；Android 另支持 `content://`）
 *
 * 支持的图片格式：
 * - 光栅：PNG、JPEG、WebP、BMP、ICO（全平台 Skia/Android 内置）
 * - 矢量：SVG、SVGZ（jvm/ios/wasm 走 skia SVGDOM，android 走 coil-svg）
 *
 * 加载失败显示可见占位（替代文本），不静默空白。
 */
internal val InkDensityKey = coil3.Extras.Key(default = 1f)

@Composable
fun InkImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (model.isNullOrBlank()) return
    val context = LocalPlatformContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    println("[InkImage] Loading model=$model with screen density=$density")
    val loader = remember { inkImageLoader(context) }
    val request = remember(model, density) {
        ImageRequest.Builder(context)
            .data(inkNormalizeImageModel(model))
            .apply {
                extras[InkDensityKey] = density
            }
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        imageLoader = loader,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = {},
        error = {
            // 不静默：给出可见的失败反馈与替代文本
            Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = contentDescription?.takeIf { it.isNotBlank() } ?: "无法加载图片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
