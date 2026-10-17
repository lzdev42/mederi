package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLEncoder
import xyz.mederi.tools.background.BackgroundTaskManager
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.ProcessRegistry

/**
 * 浏览器 UA（桌面 Chrome 120 / macOS），curl 抓取时伪装常规浏览器，
 * 规避部分站点对无头/curl 默认 UA 的拦截或差异返回。
 */
private const val BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Shell 命令执行工具集。
 *
 * 命令在项目主目录（第一个目录）下执行，经 [CommandSandbox] 包装：
 * - macOS Seatbelt / Linux bubblewrap：写仅限白名单（项目目录/临时/构建缓存），读全盘
 * - Windows / 未装 bwrap：裸跑 + 警告前缀
 * - shell 探测链：bash → sh（Windows: bash.exe → cmd /c）
 *
 * 无权限弹窗（已拆除）：沙箱 + diff 追踪 + git 是安全网，不再逐操作询问。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），命令的工作目录为第一个目录。
 * @param sandbox 命令沙箱（每 turn 由 ToolFactory 新建）。
 */
class ShellTools(
    private val allowedDirectories: List<String>,
    private val sandbox: CommandSandbox? = null,
    private val backgroundTaskManager: BackgroundTaskManager? = null,
    private val sessionId: String = "",
) {

    /**
     * 命令执行结果（输出 + exit code + 超时标志，供 BashTool 格式化和 VerifyTools 判定）。
     *
     * [timedOut] 为 true 表示进程未在 [ShellTools.runCommand] 的 timeoutSeconds 内完成被强制回收——
     * 与「进程正常退出但 exitCode 非零」的真实失败区分开（VerifyTools 三态判定依赖此标志）。
     */
    data class CommandResult(val output: String, val exitCode: Int, val timedOut: Boolean = false)

    /**
     * 执行一条 shell 命令，返回 [CommandResult]。
     *
     * BashTool 和 VerifyTools 的自动验证共用此方法——
     * 沙箱包装、工作目录、输出读取线程、超时处理全一致。
     *
     * @param cwd 命令工作目录（绝对路径）；null 或空白时回退到项目主目录（allowedDirectories.first()）。
     */
    suspend fun runCommand(command: String, timeoutSeconds: Int = 120, cwd: String? = null): CommandResult {
        if (command.isBlank()) return CommandResult("Error: command is empty", -1)

            val wrapped = sandbox?.wrap(command)
                ?: CommandSandbox.WrappedCommand(listOf(sandbox?.shell ?: "sh", "-c", command), null, false)
            val workDir = cwd?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(allowedDirectories.first())
            val builder = ProcessBuilder(wrapped.argv)
            builder.directory(workDir)
            builder.redirectErrorStream(true)

            val process = builder.start()

            // 启动即登记：posix 下直接子进程是进程组长（pid==pgid），
            // 宿主侧据此整组回收（stop_process / 超时清理），安全边界见 ProcessRegistry。
            ProcessRegistry.register(
                pid = process.pid(),
                pgid = if (wrapped.processGroupLeader) process.pid() else null,
                command = command,
                workDir = workDir.absolutePath
            )

            val outputFuture = java.util.concurrent.FutureTask {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            Thread(outputFuture).start()

            val completed = process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                // 超时整组回收：仅 destroyForcibly 只杀直接子进程，后台孙进程会变孤儿继续跑
                if (wrapped.processGroupLeader) {
                    ProcessRegistry.killGroup(process.pid(), process.pid(), force = true)
                }
                process.destroyForcibly()
                outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
                val partialOutput = outputFuture.get()
                return CommandResult("Command timed out after ${timeoutSeconds}s\n$partialOutput", -1, timedOut = true)
            }

            val output = outputFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = process.exitValue()
            val prefixed = if (wrapped.warning != null) wrapped.warning + output else output
            return CommandResult(prefixed, exitCode)
    }

    /**
     * 非阻塞命令执行：用与 [runCommand] 相同的沙箱包装启动进程，但不等待——
     * 进程交给 [BackgroundTaskManager] 的后台协程跟踪（waitFor + 输出读取 + 终态事件），
     * 立即返回 task ID，AI 可继续与用户对话。
     */
    private fun startBackgroundCommand(command: String, timeoutSeconds: Int, cwd: String?): String {
        if (command.isBlank()) return "Error: command is empty"

        return runCatching {
            val wrapped = sandbox?.wrap(command)
                ?: xyz.mederi.tools.sandbox.CommandSandbox.WrappedCommand(
                    listOf(sandbox?.shell ?: "sh", "-c", command), null, false
                )
            val workDir = cwd?.takeIf { it.isNotBlank() }?.let { java.io.File(it) } ?: java.io.File(allowedDirectories.first())
            val builder = ProcessBuilder(wrapped.argv)
            builder.directory(workDir)
            builder.redirectErrorStream(true)

            val process = builder.start()
            xyz.mederi.tools.sandbox.ProcessRegistry.register(
                pid = process.pid(),
                pgid = if (wrapped.processGroupLeader) process.pid() else null,
                command = command,
                workDir = workDir.absolutePath
            )

            val taskId = backgroundTaskManager!!.trackCommand(
                sessionId = sessionId,
                process = process,
                command = command,
                workDir = workDir.absolutePath,
                timeoutSeconds = timeoutSeconds
            )
            "Background task started: $taskId\nCommand: $command\nYou will be notified with the output when it completes. You can continue working with the user."
        }.getOrElse { e ->
            "Error starting background command: ${e.message}"
        }
    }

    @Serializable
    data class BashArgs(
        @LLMDescription("Shell command to execute.")
        val command: String = "",
        @LLMDescription("Working directory (absolute or project-relative path). Empty = project root.")
        val cwd: String = "",
        @LLMDescription("Timeout in seconds; default 120.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 120,
        @LLMDescription("Set true for long-running commands (compilation, tests, servers). The command runs in the background; you get notified with the output when it completes and can continue working meanwhile. Default false blocks until the command finishes.")
        val background: Boolean = false
    )

    inner class BashTool : SimpleTool<BashArgs>(
        argsType = typeToken<BashArgs>(),
        name = ToolName.BASH.value,
        description = "Execute a shell command in the given working directory (empty cwd = project root) and return its stdout and stderr. Writes are sandboxed: project directories, temp dirs, and build caches only. Set background=true for long-running commands (compilation, tests, servers) — runs in background, you get notified with output when done, and can continue working meanwhile. Default background=false blocks until the command finishes."
    ) {
        override suspend fun execute(args: BashArgs): String {
            if (args.background && backgroundTaskManager != null) {
                return startBackgroundCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() })
            }
            val result = runCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() })
            val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""
            return exitPrefix + result.output
        }
    }

    // ===== web 工具共享基础 =====

    /**
     * curl HTTP GET 抓取：-s 静默、-L 跟随重定向、--max-time 超时、-A 浏览器 UA、-- 终止选项解析。
     *
     * 复用 [ShellTools.runCommand]：沙箱包装、进程注册、超时处理全一致。
     * 调用方（web_search / web_fetch）负责先校验协议白名单 + 拒绝单引号（防 shell 注入——
     * 单引号是 shell 引用的唯一危险字符）。
     */
    private suspend fun curlGet(url: String, timeoutSeconds: Int): CommandResult {
        val command = "curl -sL --max-time $timeoutSeconds -A '$BROWSER_UA' -- '$url'"
        return runCommand(command, timeoutSeconds + 5)
    }

    /** 按 maxChars 截断，超长时追加省略提示（不丢信息：提示写明省略了多少字符）。 */
    private fun truncate(raw: String, maxChars: Int): String =
        if (raw.length > maxChars) {
            raw.take(maxChars) + "\n... [truncated, ${raw.length - maxChars} chars omitted]"
        } else {
            raw
        }

    /**
     * 解码常用 HTML 实体：命名实体（&amp; &lt; &gt; &#39; &apos; &quot; &nbsp; &#0183; &ndash; &mdash;）
     * + 十进制数字实体（&#\d+;）。尽力而为，未知实体原样保留，解码失败安全降级。
     */
    private fun decodeHtmlEntities(text: String): String {
        var result = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&#0183;", "·")
            .replace("&ndash;", "-")
            .replace("&mdash;", "-")
        result = Regex("""&#(\d+);""").replace(result) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 0..0xFFFF) code.toChar().toString() else m.value
        }
        return result
    }

    /** 清洗 HTML 片段：剥离标签 + 解码实体 + 去首尾空白（用于搜索结果的 title/URL/snippet 提取）。 */
    private fun cleanHtmlText(raw: String): String {
        val stripped = Regex("""<[^>]*>""").replace(raw, "")
        return decodeHtmlEntities(stripped).trim()
    }

    @Serializable
    data class WebSearchArgs(
        @LLMDescription("Search query keywords. The tool searches Bing and returns structured results (title, URL, snippet for each hit).")
        val query: String = "",
        @LLMDescription("Maximum characters to return; default 8000. Truncates response to fit context.")
        @kotlinx.serialization.SerialName("max_chars")
        val maxChars: Int = 8000,
        @LLMDescription("Timeout in seconds; default 30.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 30
    )

    /**
     * 只读 Bing 搜索工具（仅注册给 RESEARCHER 子代理）。
     *
     * 底层经 curl HTTP GET 拉取 Bing 搜索结果页，正则结构化提取每条结果的
     * title / URL / snippet——提取是尽力而为：提取失败（0 条结果）时兜底返回原始 HTML 截断，不丢信息。
     * 只读、无写、无通用 shell。
     * 安全约束：
     * - query 不得为空
     * - query 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - curl 命令用 -- 终止选项解析，URL 以单引号包裹，URL 编码后的 query 不含单引号
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.curlGet] / [ShellTools.runCommand]：沙箱包装、进程注册、超时处理全一致。
     */
    inner class WebSearchTool : SimpleTool<WebSearchArgs>(
        argsType = typeToken<WebSearchArgs>(),
        name = ToolName.WEB_SEARCH.value,
        description = "Search the web via Bing. Returns structured results: title, URL, snippet for each hit. " +
            "Read-only: no POST/PUT/DELETE, no file writes. Use for finding documentation, API references, " +
            "release notes, and current information."
    ) {
        override suspend fun execute(args: WebSearchArgs): String {
            val query = args.query.trim()
            if (query.isEmpty()) return "Error: query must not be empty."

            // 安全：拒绝含单引号的 query（单引号是 shell 引用的唯一危险字符）
            if (query.contains("'")) return "Error: query must not contain single quotes."

            // URL 编码 query 后拼 Bing 搜索 URL（q 参数 + count=20）
            val encoded = URLEncoder.encode(query, "UTF-8")
            val bingUrl = "https://www.bing.com/search?q=$encoded&count=20"

            val result = curlGet(bingUrl, args.timeoutSeconds)
            if (result.exitCode != 0) return "[exit code: ${result.exitCode}]\n${result.output}"
            if (result.output.isBlank()) {
                return "Error: Bing returned empty response. The search may have failed or Bing is unreachable."
            }

            val hits = extractBingResults(result.output)
            val raw = if (hits.isEmpty()) {
                // 正则提取失败兜底：返回原始 HTML 截断，不丢信息
                result.output
            } else {
                buildString {
                    append("Search results for \"$query\":\n")
                    for ((index, hit) in hits.withIndex()) {
                        append("\n${index + 1}. ${hit.title}\n")
                        append("   URL: ${hit.url}\n")
                        append("   Snippet: ${hit.snippet}\n")
                    }
                }
            }
            return truncate(raw, args.maxChars.coerceAtLeast(100))
        }
    }

    @Serializable
    data class WebFetchArgs(
        @LLMDescription("URL to fetch via curl HTTP GET (http or https only). The tool fetches page content and cleans HTML (strips scripts, styles, tags) to return readable text.")
        val url: String = "",
        @LLMDescription("Maximum characters to return; default 8000. Truncates response to fit context.")
        @kotlinx.serialization.SerialName("max_chars")
        val maxChars: Int = 8000,
        @LLMDescription("Timeout in seconds; default 30.")
        @kotlinx.serialization.SerialName("timeout_seconds")
        val timeoutSeconds: Int = 30
    )

    /**
     * 只读 URL 抓取工具（仅注册给 RESEARCHER 子代理）。
     *
     * 底层经 curl HTTP GET 拉取页面，清洗 HTML（移除 script/style/svg/noscript/head 块与自闭合标签、
     * 剥离剩余标签、解码实体、压缩空白）后返回可读文本。只读、无写、无通用 shell。
     * 安全约束：
     * - URL 必须为 http/https 协议（拒绝 file://、ftp://、data: 等）
     * - URL 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - curl 命令用 -- 终止选项解析，URL 以单引号包裹
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.curlGet] / [ShellTools.runCommand]：沙箱包装、进程注册、超时处理全一致。
     */
    inner class WebFetchTool : SimpleTool<WebFetchArgs>(
        argsType = typeToken<WebFetchArgs>(),
        name = ToolName.WEB_FETCH.value,
        description = "Fetch a URL via curl HTTP GET and return cleaned text content. Strips scripts, styles, SVG, " +
            "and HTML tags to return readable text. Read-only: no POST/PUT/DELETE, no file writes."
    ) {
        override suspend fun execute(args: WebFetchArgs): String {
            val url = args.url.trim()
            if (url.isEmpty()) return "Error: url must not be empty."

            // 协议白名单：只允许 http/https（拒绝 file://、ftp://、data: 等）
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "Error: url must start with http:// or https://"
            }

            // 安全：拒绝含单引号的 URL（单引号是 shell 引用的唯一危险字符）
            if (url.contains("'")) return "Error: url must not contain single quotes."

            val result = curlGet(url, args.timeoutSeconds)
            if (result.exitCode != 0) return "[exit code: ${result.exitCode}]\n${result.output}"
            if (result.output.isBlank()) return "Error: empty response from server."

            val cleaned = cleanHtml(result.output)
            return truncate(cleaned, args.maxChars.coerceAtLeast(100))
        }
    }

    /**
     * HTML 清理：移除 script/style/svg/noscript/head 整块、移除自闭合/空标签、
     * 剥离所有剩余 HTML 标签、解码 HTML 实体、压缩空白，返回可读文本。
     */
    private fun cleanHtml(raw: String): String {
        var text = raw
        // 移除整块标签（DOT_MATCHES_ALL 跨行 + IGNORE_CASE，防内容里出现同名标签干扰）
        for (tag in listOf("script", "style", "svg", "noscript", "head")) {
            text = Regex("""<\s*$tag\b[^>]*>.*?</\s*$tag\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .replace(text, "")
        }
        // 移除自闭合/无内容标签
        text = Regex("""<(?:link|meta|img|br|hr|source|input|col|area|base|param)[^>]*/?>""", RegexOption.IGNORE_CASE)
            .replace(text, "")
        // 剥离所有剩余 HTML 标签 → 空格
        text = Regex("""<[^>]+>""").replace(text, " ")
        // 解码 HTML 实体
        text = decodeHtmlEntities(text)
        // 压缩空白：连续空格/制表符 → 单空格；连续空行（含行首尾空白）→ 单换行；strip 首尾空白
        text = Regex("""[ \t]+""").replace(text, " ")
        text = Regex("""\s*\n\s*""").replace(text, "\n")
        return text.trim()
    }

    /** Bing 单条搜索结果（正则提取产物）。 */
    private data class SearchHit(val title: String, val url: String, val snippet: String)

    /**
     * 尽力而为地正则提取 Bing 搜索结果：先按 `<li class="b_algo">` 切块，
     * 每块再提取 title（h2>a）/ URL（cite）/ snippet（p.b_lineclamp2）。
     * 一个都提取不到时返回空列表（调用方兜底返回原始 HTML 截断）。
     */
    private fun extractBingResults(html: String): List<SearchHit> {
        val blockRegex = Regex("""<li class="b_algo".*?</li>""", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("""<h2><a[^>]*>(.*?)</a></h2>""", RegexOption.DOT_MATCHES_ALL)
        val urlRegex = Regex("""<cite[^>]*>(.*?)</cite>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<p class="b_lineclamp2[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        return blockRegex.findAll(html)
            .map { block ->
                val blockHtml = block.value
                SearchHit(
                    title = cleanHtmlText(titleRegex.find(blockHtml)?.groupValues?.get(1).orEmpty()),
                    url = cleanHtmlText(urlRegex.find(blockHtml)?.groupValues?.get(1).orEmpty()),
                    snippet = cleanHtmlText(snippetRegex.find(blockHtml)?.groupValues?.get(1).orEmpty())
                )
            }
            .filter { it.title.isNotEmpty() || it.url.isNotEmpty() || it.snippet.isNotEmpty() }
            .toList()
    }
}
