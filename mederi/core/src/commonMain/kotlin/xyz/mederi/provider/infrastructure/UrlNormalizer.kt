package xyz.mederi.provider.infrastructure

/**
 * URL 拆解结果。
 *
 * 将用户输入的端点 URL（如 "https://api.openai.com/v1"）拆分为两部分：
 * - [base]: 协议 + 主机部分（如 "https://api.openai.com"）
 * - [versionPath]: 版本路径部分（如 "v1"、"v1beta"、"v3"），不含前导和尾部斜杠
 *
 * @param base 协议 + 主机部分，不含任何路径，不含尾部斜杠。
 * @param versionPath 版本路径部分，不含前导和尾部斜杠。如果用户未输入版本路径则为空字符串。
 */
data class NormalizedUrl(val base: String, val versionPath: String) {

    /**
     * 构建路径前缀，用于拼接 Koog 的各个 path 字段。
     *
     * 如果 [versionPath] 不为空，返回 "v1/"；如果为空，返回 ""。
     *
     * 例如：
     * - versionPath = "v1" -> "v1/"
     * - versionPath = "v1beta" -> "v1beta/"
     * - versionPath = "" -> ""
     */
    val pathPrefix: String
        get() = if (versionPath.isNotEmpty()) "$versionPath/" else ""
}

/**
 * URL 拆解工具。
 *
 * 用户按照市面上通用格式输入带版本路径的完整端点：
 * - "https://api.openai.com/v1"
 * - "https://generativelanguage.googleapis.com/v1beta"
 * - "https://api.deepseek.com/v3"
 *
 * 我们不负责纠正用户填错的 URL--填错了请求就会失败，错误信息会反馈给用户。
 * 本工具唯一的职责是把用户输入的 URL 拆解成 Koog 需要的格式。
 *
 * Koog 的客户端配置类将 base URL 和 API 路径分开管理：
 * - OpenAIClientSettings: baseUrl = "https://api.openai.com", chatCompletionsPath = "v1/chat/completions"
 * - GoogleClientSettings: baseUrl = "https://generativelanguage.googleapis.com", defaultPath = "v1beta/models"
 *
 * 因此，本工具将用户输入的完整端点拆解为 [NormalizedUrl]（base + versionPath），
 * 供 KoogClientFactory 拼接 Koog 的各个 path 字段使用。
 * 不在乎版本路径是 v1、v2、v3 还是 v1beta，只负责拆解。
 *
 * 这是一个无状态的工具对象，线程安全。
 */
object UrlNormalizer {

    /**
     * 将用户输入的端点 URL 拆解为 base 和 versionPath。
     *
     * 仅做 trim 和去尾部斜杠，不做任何纠错。
     * 用户填了错误的端点，请求自然会失败，错误会反馈给用户。
     *
     * 示例:
     * - "https://api.openai.com/v1" -> NormalizedUrl("https://api.openai.com", "v1")
     * - "https://api.openai.com/v1/" -> NormalizedUrl("https://api.openai.com", "v1")
     * - "https://generativelanguage.googleapis.com/v1beta" -> NormalizedUrl("https://generativelanguage.googleapis.com", "v1beta")
     * - "https://api.deepseek.com/v3" -> NormalizedUrl("https://api.deepseek.com", "v3")
     * - "https://api.openai.com" -> NormalizedUrl("https://api.openai.com", "")
     *
     * @param url 用户输入的端点 URL。
     * @return 拆解后的 [NormalizedUrl]。
     */
    fun normalize(url: String): NormalizedUrl {
        val trimmed = url.trim().trimEnd('/')

        // 定位 scheme:// 之后的第一段路径
        val schemeEnd = trimmed.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = trimmed.indexOf('/', hostStart)

        // 没有路径部分，只有 host
        if (pathStart < 0) {
            return NormalizedUrl(base = trimmed, versionPath = "")
        }

        val base = trimmed.substring(0, pathStart)
        // 去掉前导 /，去掉尾部 /（trimEnd 已处理，但保险起见再 trim 一次）
        val versionPath = trimmed.substring(pathStart + 1).trimEnd('/')

        return NormalizedUrl(base = base, versionPath = versionPath)
    }
}
