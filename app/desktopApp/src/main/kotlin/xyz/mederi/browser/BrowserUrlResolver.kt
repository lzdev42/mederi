package xyz.mederi.browser

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 智能地址栏输入解析：
 * - 已带协议头（`http://`, `https://`, `file://`, `about:`, `data:`, `blob:` 等）→ 原样返回；
 * - 本地开发地址（`localhost`、`127.0.0.1`、`0.0.0.0`、`[::1]`、IPv4、带显式 `:port`、`.local`/`.localhost`）→ 补 `http://`；
 * - 合法域名形态（不含空白且含点号与至少 2 位顶级域，如 `github.com`、`kotlinlang.org/docs`）→ 补 `https://`；
 * - 其余关键词或含空格短语 → 走 **Bing 搜索**（`https://www.bing.com/search?q=...`）。
 */
internal fun resolveUrlOrSearch(rawInput: String): String {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return ""

    // 1. 已含标准协议头（如 https://, http://, file://）或已知无双斜杠协议（about:, data:, blob:, javascript:）
    val lower = trimmed.lowercase()
    if (
        SCHEME_WITH_SLASHES_REGEX.containsMatchIn(trimmed) ||
        lower.startsWith("about:") ||
        lower.startsWith("data:") ||
        lower.startsWith("blob:") ||
        lower.startsWith("javascript:")
    ) {
        return trimmed
    }

    // 2. 含任何空白字符 → 直接作为搜索词走 Bing
    if (trimmed.any { it.isWhitespace() }) {
        return buildBingSearchUrl(trimmed)
    }

    // 3. 提取 authority（第一个 / ? # 之前的部分）判断是否为本地地址或域名
    val authority = trimmed.takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.isEmpty()) {
        return buildBingSearchUrl(trimmed)
    }

    // 处理 IPv6 字面量（如 [::1] 或 [::1]:8080）
    if (authority.startsWith("[")) {
        val closeBracket = authority.indexOf(']')
        if (closeBracket > 1) {
            return "http://$trimmed"
        }
    }

    val hostPortParts = authority.split(':', limit = 2)
    val host = hostPortParts[0].lowercase()
    val port = hostPortParts.getOrNull(1)
    val hasValidPort = port != null && port.toIntOrNull() in 1..65535

    // 若包含冒号但后面不是合法端口号，当作普通搜索词
    if (port != null && !hasValidPort) {
        return buildBingSearchUrl(trimmed)
    }

    // 本地地址 / IPv4 / 显式端口 → 默认 http://
    if (
        host == "localhost" ||
        host.endsWith(".localhost") ||
        host.endsWith(".local") ||
        host.endsWith(".internal") ||
        host.endsWith(".lan") ||
        IPV4_REGEX.matches(host) ||
        hasValidPort
    ) {
        return "http://$trimmed"
    }

    // 标准域名形态（至少含一个点，且末尾顶级域为 2 个以上字母/数字/连字符）→ 默认 https://
    if (DOMAIN_HOST_REGEX.matches(host)) {
        return "https://$trimmed"
    }

    // 4. 兜底：非 URL 关键词 → Bing 搜索
    return buildBingSearchUrl(trimmed)
}

private val SCHEME_WITH_SLASHES_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
private val DOMAIN_HOST_REGEX = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

private fun buildBingSearchUrl(query: String): String {
    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
    return "https://www.bing.com/search?q=$encoded"
}

/** 提取标签页展示标题：优先页面 title → customTitle → URL 域名 → 默认文案。 */
internal fun resolveTabDisplayTitle(
    pageTitle: String?,
    customTitle: String?,
    currentUrl: String?,
    fallback: String,
): String {
    val cleanTitle = pageTitle?.trim()?.takeIf { it.isNotEmpty() && it != "about:blank" }
    if (cleanTitle != null) return cleanTitle

    val cleanCustom = customTitle?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanCustom != null) return cleanCustom

    val cleanUrl = currentUrl?.trim()?.takeIf { it.isNotEmpty() && it != "about:blank" && !it.startsWith("data:") }
    if (cleanUrl != null) {
        val host = runCatching { URI(cleanUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        return host ?: cleanUrl
    }

    return fallback
}
