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
 * - macOS Seatbelt / Linux bubblewrap+seccomp：受限模式白名单可写、默认禁止命令联网，读全盘
 * - 受限后端不可用：返回原始错误并阻止命令；完全访问或已批准off才裸跑
 * - shell 探测链：bash → sh（Windows: bash.exe → cmd /c）
 *
 * 宿主权限由 ExecutionPermissions 判断；前后台命令与验证共用一次性授权入口。
 *
 * @param allowedDirectories 项目目录列表（绝对路径），命令的工作目录为第一个目录。
 * @param sandbox 命令沙箱（每 turn 由 ToolFactory 新建）。
 */
class ShellTools(
    private val allowedDirectories: List<String>,
    private val sandbox: CommandSandbox? = null,
    private val backgroundTaskManager: BackgroundTaskManager? = null,
    private val sessionId: String = "",
    private val executionPermissions: xyz.mederi.tools.sandbox.ExecutionPermissions? = null,
    private val webReader: suspend (String, Int) -> String = { url, timeout ->
        xyz.mederi.http.WebHttpReader.get(url, timeout, BROWSER_UA)
    },
    /** RF-07/R11: 工作区执行租约管理器——后台命令在 turn 结束后仍会写盘，必须持有进程生命周期租约。 */
    private val leaseManager: xyz.mederi.workspace.WorkspaceLeaseManager? = null,
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
    private data class PreparedCommand(
        val wrapped: CommandSandbox.WrappedCommand,
        val workDir: File,
        val grant: xyz.mederi.tools.sandbox.ExecutionPermissions.Grant?,
    )

    private suspend fun prepareCommand(command: String, cwd: String?, sandboxMode: String, tool: String): PreparedCommand {
        val rawDir = cwd?.takeIf { it.isNotBlank() }?.let(::File)
        val project = File(allowedDirectories.first())
        val workDir = (if (rawDir == null) project else if (rawDir.isAbsolute) rawDir else File(project, rawDir.path)).canonicalFile
        require(workDir.isDirectory) { "Working directory does not exist: $workDir" }
        require(sandboxMode in listOf("default", "network", "off")) { "sandbox must be default, network, or off" }
        val grant = executionPermissions?.authorizeCommand(command, workDir.path, sandboxMode, tool)
        // 缺少授权上下文不能构成裸跑后门；仅显式FULL或已批准off能解除OS沙盒。
        if (executionPermissions == null && sandboxMode != "default") {
            throw xyz.mederi.api.exception.MederiStateException("An execution approval channel is required for elevated commands.")
        }
        // R02: 把经校验的工作区凭证传到实际启动后端——精确产物根和映射拒绝规则沿同一凭证应用。
        val wsRoot = executionPermissions?.verifiedWorkspaceRootProvider?.invoke()
        val runner = grant?.let { CommandSandbox(allowedDirectories, it.settings.extraWritablePaths,
            executionPermissions!!.manager.protectedDirectories, it.settings.deniedPaths, wsRoot) }
            ?: sandbox ?: CommandSandbox(allowedDirectories, emptyList())
        val full = grant?.settings?.mode == xyz.mederi.tools.sandbox.ExecutionPermissionMode.FULL_ACCESS
        val wrapped = runner.wrap(command, networkAccess = sandboxMode == "network", unsandboxed = full || sandboxMode == "off")
        grant?.let { executionPermissions!!.checkGrant(it) }
        return PreparedCommand(wrapped, workDir, grant)
    }

    suspend fun runCommand(
        command: String, timeoutSeconds: Int = 120, cwd: String? = null,
        sandboxMode: String = "default", toolName: String = "bash",
        reapDescendantsOnCompletion: Boolean = leaseManager != null,
    ): CommandResult {
        if (command.isBlank()) return CommandResult("Error: command is empty", -1)
        require(timeoutSeconds > 0) { "timeout_seconds must be positive" }
        val prepared = try { prepareCommand(command, cwd, sandboxMode, toolName) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { return CommandResult("[sandbox] ${failure.message}", -1) }
        val wrapped = prepared.wrapped
        prepared.grant?.let { executionPermissions!!.checkGrant(it) }
        val process = ProcessBuilder(wrapped.argv).directory(prepared.workDir).redirectErrorStream(true).start()
        ProcessRegistry.register(process.pid(), if (wrapped.processGroupLeader) process.pid() else null, command, prepared.workDir.path)
        val output = java.util.concurrent.FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val captured = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (captured.length < 4 * 1024 * 1024) captured.append(buffer, 0, minOf(count, 4 * 1024 * 1024 - captured.length))
                }
                captured.toString()
            }
        }
        Thread(output, "mederi-command-output").apply { isDaemon = true; start() }
        var keepRegisteredGroup = false
        try {
            val completed = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process.waitFor(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            }
            if (!completed) {
                if (wrapped.processGroupLeader) ProcessRegistry.killGroup(process.pid(), process.pid(), true)
                process.destroyForcibly()
            }
            val text = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { output.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault("[output collection did not finish]")
            }
            val hint = if (completed && process.exitValue() != 0) {
                "\nIf sandbox permissions are the cause, request sandbox=network for networking, or sandbox=off for this exact unsandboxed command. User approval is required."
            } else ""
            keepRegisteredGroup = completed && wrapped.processGroupLeader && !reapDescendantsOnCompletion
            return CommandResult((wrapped.warning ?: "") + text + hint, if (completed) process.exitValue() else -1, !completed)
        } finally {
            // 取消同样收割命令树，不能留下继续写盘的后台孤儿。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                if (!keepRegisteredGroup && wrapped.processGroupLeader && ProcessRegistry.isAlive(process.pid(), process.pid())) {
                    ProcessRegistry.killGroup(process.pid(), process.pid(), true)
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                if (!keepRegisteredGroup) ProcessRegistry.remove(process.pid())
                process.inputStream.close()
                output.cancel(true)
            }
        }
    }

    /**
     * 非阻塞命令执行：用与 [runCommand] 相同的沙箱包装启动进程，但不等待——
     * 进程交给 [BackgroundTaskManager] 的后台协程跟踪（waitFor + 输出读取 + 终态事件），
     * 立即返回 task ID，AI 可继续与用户对话。
     */
    private suspend fun startBackgroundCommand(command: String, timeoutSeconds: Int, cwd: String?, sandboxMode: String): String {
        if (command.isBlank()) return "Error: command is empty"

        return runCatching {
            require(timeoutSeconds > 0) { "timeout_seconds must be positive" }
            val prepared = prepareCommand(command, cwd, sandboxMode, "bash")
            val wrapped = prepared.wrapped
            val workDir = prepared.workDir
            prepared.grant?.let { executionPermissions!!.checkGrant(it) }

            // R11: 后台命令在父 turn 结束后仍可能写盘——为进程生命周期持有执行租约，
            // 归档/配额的 withManagementIntent 因此会等待后台进程真正退出后才快照/强删 checkout。
            // 租约根取经校验的工作区 checkoutRoot（无权限上下文时回退到项目主目录）。
            // 若工作区正处独占管理意图（如归档启动），acquire 抛 WORKSPACE_BUSY——此时不应启动
            // 会写该目录的后台命令，错误原样上抛（runCatching 转成错误串，process 未启动）。
            val leaseRoot = executionPermissions?.verifiedWorkspaceRootProvider?.invoke()?.checkoutRoot?.toString()
                ?: allowedDirectories.firstOrNull()
            val lease = leaseManager?.let { lm ->
                leaseRoot?.let { root -> lm.acquireExecutionLease(root) }
            }

            var process: Process? = null
            var leaseReleased = false
            try {
                val builder = ProcessBuilder(wrapped.argv)
                builder.directory(workDir)
                builder.redirectErrorStream(true)

                process = builder.start()
                ProcessRegistry.register(
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
                if (lease != null) {
                    val hookOk = backgroundTaskManager.attachTerminalHook(taskId) { lease.close() }
                    if (hookOk) {
                        leaseReleased = true
                    } else {
                        // attach 失败是异常态（entry 应刚由 trackCommand 创建）；
                        // 立即释放 lease，接受进程可能仍在写的极短窗口。
                        lease.close()
                        leaseReleased = true
                    }
                }
                (wrapped.warning ?: "") +
                    "Background task started: $taskId\nCommand: $command\nYou will be notified with the output when it completes. You can continue working with the user."
            } catch (e: Exception) {
                // start 之后 track/register 失败：收割已启动进程，lease 由 finally 释放。
                process?.let { p ->
                    p.destroyForcibly()
                    runCatching { ProcessRegistry.remove(p.pid()) }
                }
                throw e
            } finally {
                // lease 在 attachTerminalHook 成功时由 hook 释放（leaseReleased=true）；
                // 其他路径（start 前失败 / start 后失败 / attach 失败已立即 close）在此统一释放。
                if (lease != null && !leaseReleased) {
                    lease.close()
                }
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        val background: Boolean = false,
        @LLMDescription("Set true for build/compile commands (./gradlew, mvn, make, cargo build, etc.). The tool holds a per-project lock so concurrent compile commands from parallel subagents queue and wait instead of colliding (one compile at a time per project). Waiting does not count against timeout_seconds. Only effective when background=false. Default false.")
        val compilation: Boolean = false,
        @LLMDescription("default: restricted filesystem and no command networking. network: request networking for this command, keeping filesystem restrictions. off: request unsandboxed execution for this exact command. Elevated requests require user approval unless Full Access is selected.")
        val sandbox: String = "default",
    )

    inner class BashTool : SimpleTool<BashArgs>(
        argsType = typeToken<BashArgs>(),
        name = ToolName.BASH.value,
        description = "Execute a shell command in the given working directory (empty cwd = project root) and return its stdout and stderr. In Needs Approval mode, writes are restricted to project/temp/explicit writable roots and command networking is disabled. Use sandbox=network or sandbox=off to request a one-time permission grant. Set background=true for long-running commands (compilation, tests, servers) — runs in background, you get notified with output when done, and can continue working meanwhile. Set compilation=true for build/compile commands — concurrent compiles from parallel subagents queue per-project (one at a time) instead of colliding; waiting does not count against timeout_seconds. Default background=false blocks until the command finishes."
    ) {
        override suspend fun execute(args: BashArgs): String {
            if (args.background && backgroundTaskManager != null) {
                return startBackgroundCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() }, args.sandbox)
            }
            val run: suspend () -> String = {
                val result = runCommand(args.command, args.timeoutSeconds, args.cwd.takeIf { it.isNotBlank() }, args.sandbox)
                val exitPrefix = if (result.exitCode != 0) "[exit code: ${result.exitCode}]\n" else ""
                exitPrefix + result.output
            }
            return if (args.compilation) {
                val projectKey = File(allowedDirectories.first()).canonicalFile.absolutePath
                CompilationCoordinator.acquire(projectKey).use { run() }
            } else run()
        }
    }

    // ===== web 工具共享基础 =====

    /** 调研专用只读 HTTP：不经过 shell；超时、重定向及响应字节上限由 WebHttpReader 执行。 */
    private suspend fun httpGet(url: String, timeoutSeconds: Int): CommandResult = try {
        CommandResult(webReader(url, timeoutSeconds), 0)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { CommandResult("Web request failed: ${failure.message}", -1) }

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
     * 底层经 HTTP GET 拉取 Bing 搜索结果页，正则结构化提取每条结果的
     * title / URL / snippet——提取是尽力而为：提取失败（0 条结果）时兜底返回原始 HTML 截断，不丢信息。
     * 只读、无写、无通用 shell。
     * 安全约束：
     * - query 不得为空
     * - query 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.httpGet]；独立于命令网络授权，不启动子进程。
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


            // URL 编码 query 后拼 Bing 搜索 URL（q 参数 + count=20）
            val encoded = URLEncoder.encode(query, "UTF-8")
            val bingUrl = "https://www.bing.com/search?q=$encoded&count=20"

            val result = httpGet(bingUrl, args.timeoutSeconds)
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
        @LLMDescription("URL to fetch via HTTP GET (http or https only). The tool fetches page content and cleans HTML (strips scripts, styles, tags) to return readable text.")
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
     * 底层经 HTTP GET 拉取页面，清洗 HTML（移除 script/style/svg/noscript/head 块与自闭合标签、
     * 剥离剩余标签、解码实体、压缩空白）后返回可读文本。只读、无写、无通用 shell。
     * 安全约束：
     * - URL 必须为 http/https 协议（拒绝 file://、ftp://、data: 等）
     * - URL 不得包含单引号（防 shell 注入——单引号内 shell 不展开元字符）
     * - 不传 -o（不写文件）、不传 -d/-X（不发数据、不改方法）
     *
     * 复用 [ShellTools.httpGet]；独立于命令网络授权，不启动子进程。
     */
    inner class WebFetchTool : SimpleTool<WebFetchArgs>(
        argsType = typeToken<WebFetchArgs>(),
        name = ToolName.WEB_FETCH.value,
        description = "Fetch a URL via HTTP GET and return cleaned text content. Strips scripts, styles, SVG, " +
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

            val result = httpGet(url, args.timeoutSeconds)
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
