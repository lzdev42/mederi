package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import xyz.mederi.theme.LocalMederiColors

/**
 * 供应商图标组件：
 * 1. 自动解析供应商名称或 Base URL 对应的域名；
 * 2. 借助 Favicon 服务异步拉取 Logo，配合 Coil3 的内存与持久化缓存；
 * 3. 当处于加载中、无网络或无法解析时，平滑降级展示首字母徽标。
 */
@Composable
fun ProviderIcon(
    name: String,
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    size: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(3.5.dp)
) {
    val colors = LocalMederiColors.current
    val iconUrl = remember(name, baseUrl) { resolveProviderIconUrl(name, baseUrl) }

    val firstChar = remember(name) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) "P" else trimmed.take(1).uppercase()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.surfaceInput)
            .border(0.5.dp, colors.divider, shape),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            val context = LocalPlatformContext.current
            val loader = remember { xyz.emuci.inkcompose.getInkImageLoader(context) }
            val request = remember(iconUrl) {
                ImageRequest.Builder(context)
                    .data(iconUrl)
                    .crossfade(true)
                    .size(coil3.size.Size.ORIGINAL)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                imageLoader = loader,
                contentDescription = name,
                modifier = Modifier.size(size).clip(shape),
                contentScale = ContentScale.Fit,
                loading = {
                    FallbackLetter(char = firstChar, size = size, textColor = colors.textMuted)
                },
                error = {
                    FallbackLetter(char = firstChar, size = size, textColor = colors.textMuted)
                }
            )
        } else {
            FallbackLetter(char = firstChar, size = size, textColor = colors.textMuted)
        }
    }
}

/**
 * 获取供应商高清图标 URL（优先 128px+ 或矢量 SVG，杜绝 16px/32px 的低分模糊 ICO）。
 */
internal fun resolveProviderIconUrl(name: String, baseUrl: String?): String? {
    val cleanName = name.trim().lowercase()

    // 1. 知名预设专用高清/矢量直链
    when {
        cleanName.contains("empero") -> return "https://empero.org/assets/favicon.svg"
        cleanName.contains("openrouter") -> return "https://openrouter.ai/apple-touch-icon.png"
        cleanName.contains("agnes") -> return "https://agnes-ai.com/images/biglogo.png"
    }

    // 2. 解析域名
    val domain = resolveProviderDomain(name, baseUrl) ?: return null

    // 3. 采用 Google 128px 高清 Favicon 服务（浏览器级清晰度）
    return "https://t0.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$domain&size=128"
}

@Composable
private fun FallbackLetter(
    char: String,
    size: Dp,
    textColor: Color
) {
    val fontSize = (size.value * 0.58f).coerceAtLeast(8f).sp
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = char,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            lineHeight = fontSize
        )
    }
}

/**
 * 提取/推导供应商的官网域名。
 */
internal fun resolveProviderDomain(name: String, baseUrl: String?): String? {
    val cleanName = name.trim().lowercase()

    // 1. 优先根据常见预设/品牌名称直接映射
    when {
        cleanName.contains("gemini") || cleanName.contains("google") -> return "google.com"
        cleanName.contains("agnes") -> {
            return if (cleanName.contains("cn")) "agnes-ai.cn" else "agnes-ai.com"
        }
        cleanName.contains("hetzner") -> return "hetzner.com"
        cleanName.contains("empero") -> return "empero.org"
        cleanName.contains("opencode") -> return "opencode.ai"
        cleanName.contains("openrouter") -> return "openrouter.ai"
        cleanName.contains("sensenova") || cleanName.contains("sensetime") || cleanName.contains("商汤") -> return "sensetime.com"
        cleanName.contains("openai") || cleanName.contains("chatgpt") -> return "openai.com"
        cleanName.contains("anthropic") || cleanName.contains("claude") -> return "anthropic.com"
        cleanName.contains("deepseek") -> return "deepseek.com"
        cleanName.contains("mistral") -> return "mistral.ai"
        cleanName.contains("groq") -> return "groq.com"
        cleanName.contains("ollama") -> return "ollama.com"
        cleanName.contains("siliconflow") || cleanName.contains("硅基") -> return "siliconflow.cn"
        cleanName.contains("moonshot") || cleanName.contains("kimi") -> return "moonshot.cn"
        cleanName.contains("zhipu") || cleanName.contains("智谱") || cleanName.contains("glm") -> return "zhipuai.cn"
        cleanName.contains("baichuan") || cleanName.contains("百川") -> return "baichuan-ai.com"
        cleanName.contains("minimax") -> return "minimax.io"
        cleanName.contains("stepfun") || cleanName.contains("阶跃") -> return "stepfun.com"
        cleanName.contains("together") -> return "together.ai"
        cleanName.contains("fireworks") -> return "fireworks.ai"
        cleanName.contains("perplexity") -> return "perplexity.ai"
        cleanName.contains("cohere") -> return "cohere.com"
        cleanName.contains("qwen") || cleanName.contains("aliyun") || cleanName.contains("通义") -> return "aliyun.com"
        cleanName.contains("azure") -> return "microsoft.com"
        cleanName.contains("github") -> return "github.com"
        cleanName.contains("bedrock") || cleanName.contains("aws") -> return "aws.amazon.com"
    }

    // 2. 若有 baseUrl，从 URL 中解析域名
    if (!baseUrl.isNullOrBlank()) {
        val domainFromUrl = extractDomainFromUrl(baseUrl)
        if (!domainFromUrl.isNullOrBlank()) {
            return domainFromUrl
        }
    }

    // 3. 尝试如果名称本身就是域名格式（如 foo.bar.com）
    if (cleanName.contains(".") && !cleanName.contains(" ")) {
        return cleanName
    }

    return null
}

private fun extractDomainFromUrl(url: String): String? {
    var raw = url.trim()
    if (raw.isBlank()) return null
    val schemeIndex = raw.indexOf("://")
    if (schemeIndex != -1) {
        raw = raw.substring(schemeIndex + 3)
    }
    val slashIndex = raw.indexOf('/')
    if (slashIndex != -1) {
        raw = raw.substring(0, slashIndex)
    }
    val colonIndex = raw.indexOf(':')
    if (colonIndex != -1) {
        raw = raw.substring(0, colonIndex)
    }
    return raw.ifBlank { null }
}
