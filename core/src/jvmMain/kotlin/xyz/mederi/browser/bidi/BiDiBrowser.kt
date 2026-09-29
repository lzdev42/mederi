package xyz.mederi.browser.bidi

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import xyz.mederi.browser.bidi.BiDiLog

@Serializable
data class CamoufoxConfig(
    // ── 指纹覆盖组（全可空，null = 不注入，用 Camoufox 默认指纹） ──
    val userAgent: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val geolocationLat: Double? = null,
    val geolocationLon: Double? = null,
    val webglVendor: String? = null,
    val webglRenderer: String? = null,
    val webrtcIpv4: String? = null,
    val webrtcIpv6: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenAvailWidth: Int? = null,
    val screenAvailHeight: Int? = null,
    val windowOuterWidth: Int? = null,
    val windowOuterHeight: Int? = null,
    val windowInnerWidth: Int? = null,
    val windowInnerHeight: Int? = null,
    val hardwareConcurrency: Int? = null,
    val maxTouchPoints: Int? = null,
    val fonts: List<String> = emptyList(),

    // ── 人类化与行为 ──
    val humanize: Boolean = false,
    val humanizeMaxSeconds: Double? = null,

    // ── 资源屏蔽 / COOP ──
    val blockWebgl: Boolean = false,
    val blockWebrtc: Boolean = true,
    val disableCoop: Boolean = true,
    val blockImages: Boolean = false,

    // ── 高级组（原样透传，优先级最高） ──
    val advancedConfig: Map<String, JsonElement> = emptyMap()
) {
    /**
     * 仅注入非空字段。空 config（全默认）产出空 JsonObject——
     * 不注入 navigator.platform：raw binary 无 browserforge，写死 platform 会与自动 UA 冲突致泄漏。
     */
    fun toConfigJson(): JsonObject {
        val config = mutableMapOf<String, JsonElement>()
        userAgent?.let { config["navigator.userAgent"] = JsonPrimitive(it) }
        locale?.let { config["locale:language"] = JsonPrimitive(it) }
        timezone?.let { config["timezone"] = JsonPrimitive(it) }
        geolocationLat?.let { config["geolocation:latitude"] = JsonPrimitive(it) }
        geolocationLon?.let { config["geolocation:longitude"] = JsonPrimitive(it) }
        webglVendor?.let { config["webGl:vendor"] = JsonPrimitive(it) }
        webglRenderer?.let { config["webGl:renderer"] = JsonPrimitive(it) }
        webrtcIpv4?.let { config["webrtc:ipv4"] = JsonPrimitive(it) }
        webrtcIpv6?.let { config["webrtc:ipv6"] = JsonPrimitive(it) }
        screenWidth?.let { config["screen.width"] = JsonPrimitive(it) }
        screenHeight?.let { config["screen.height"] = JsonPrimitive(it) }
        screenAvailWidth?.let { config["screen.availWidth"] = JsonPrimitive(it) }
        screenAvailHeight?.let { config["screen.availHeight"] = JsonPrimitive(it) }
        windowOuterWidth?.let { config["window.outerWidth"] = JsonPrimitive(it) }
        windowOuterHeight?.let { config["window.outerHeight"] = JsonPrimitive(it) }
        windowInnerWidth?.let { config["window.innerWidth"] = JsonPrimitive(it) }
        windowInnerHeight?.let { config["window.innerHeight"] = JsonPrimitive(it) }
        hardwareConcurrency?.let { config["navigator.hardwareConcurrency"] = JsonPrimitive(it) }
        maxTouchPoints?.let { config["navigator.maxTouchPoints"] = JsonPrimitive(it) }
        if (fonts.isNotEmpty()) {
            config["fonts"] = JsonArray(fonts.map { JsonPrimitive(it) })
        }
        if (humanize) {
            config["humanize"] = JsonPrimitive(true)
        }
        humanizeMaxSeconds?.let { config["humanize:maxTime"] = JsonPrimitive(it) }
        config.putAll(advancedConfig)
        return JsonObject(config)
    }

    fun toFirefoxPrefs(): Map<String, Any> {
        val prefs = mutableMapOf<String, Any>()
        if (disableCoop) {
            prefs["browser.tabs.remote.useCrossOriginOpenerPolicy"] = false
        }
        if (blockWebgl) {
            prefs["webgl.disabled"] = true
        }
        if (blockWebrtc) {
            prefs["media.peerconnection.enabled"] = false
        }
        if (blockImages) {
            prefs["permissions.default.image"] = 2
        }
        return prefs
    }

    fun toEnvVars(): Map<String, String> {
        val configJson = toConfigJson()
        if (configJson.isEmpty()) return emptyMap()
        val jsonStr = Json.encodeToString(JsonObject.serializer(), configJson)
        val chunkSize = if (System.getProperty("os.name").lowercase().contains("win")) 2047 else 32767
        val envVars = mutableMapOf<String, String>()
        for ((i, from) in (0 until jsonStr.length step chunkSize).withIndex()) {
            val to = minOf(from + chunkSize, jsonStr.length)
            envVars["CAMOU_CONFIG_${i + 1}"] = jsonStr.substring(from, to)
        }
        return envVars
    }
}

class BiDiBrowser(
    val binaryPath: String,
    val profilePath: Path,
    val headless: Boolean = true,
    val downloadDir: Path? = null,
    val extraArgs: List<String> = emptyList(),
    val config: CamoufoxConfig = CamoufoxConfig(),
    // 已展开的 Firefox proxy prefs（由上游把 ProxyConfig 展开成 user_pref 键值对传入，避免类型依赖）
    val proxyPrefs: Map<String, Any> = emptyMap()
) : AutoCloseable {

    private val _pages = mutableListOf<BiDiPage>()
    val pages: List<BiDiPage> get() = _pages.toList()

    @Volatile var isAlive: Boolean = false
        private set

    private var process: Process? = null
    private var transport: BiDiTransport? = null
    private var sessionId: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start() {
        if (isAlive) return
        BiDiLog.info("BiDiBrowser.start: BEGIN, binaryPath=$binaryPath, profilePath=$profilePath, headless=$headless")
        try {
            prepareProfile()
            BiDiLog.info("BiDiBrowser.start: prepareProfile OK")
        } catch (e: Exception) {
            BiDiLog.error("BiDiBrowser.start: prepareProfile FAILED: ${e.message}")
            throw ProfileCorruptedException("Failed to prepare profile at $profilePath: ${e.message}")
        }

        val port = findFreePort()
        BiDiLog.info("BiDiBrowser.start: chosen debug port=$port")

        val args = buildList {
            if (headless) add("--headless")
            add("--no-remote")
            add("-profile")
            add(profilePath.toString())
            add("--remote-debugging-port")
            add(port.toString())
            add("--marionette")
            addAll(extraArgs)
        }
        BiDiLog.info("BiDiBrowser.start: launch args=${args.joinToString(" ")}")

        val pb = ProcessBuilder(binaryPath, *args.toTypedArray())
        pb.redirectErrorStream(true)
        val camouEnv = config.toEnvVars()
        if (camouEnv.isNotEmpty()) {
            val pbEnv = pb.environment()
            pbEnv.putAll(camouEnv)
            BiDiLog.info("BiDiBrowser.start: injected CAMOU_CONFIG env vars: keys=${camouEnv.keys.joinToString(",")}, totalBytes=${camouEnv.values.sumOf { it.length }}")
        } else {
            BiDiLog.info("BiDiBrowser.start: no CAMOU_CONFIG to inject (config empty)")
        }
        process = pb.start()
        BiDiLog.info("BiDiBrowser.start: process started, pid=${process?.pid()}")

        val stdoutGobbler = scope.launch {
            val reader = process?.inputStream?.bufferedReader()
            if (reader != null) {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        BiDiLog.info("[Camoufox] $line")
                    }
                    BiDiLog.info("BiDiBrowser.start: stdout EOF (process exited?)")
                } catch (e: Exception) {
                    BiDiLog.warn("BiDiBrowser.start: stdout reader exception: ${e.message}")
                }
            }
        }

        if (!waitForPort(port, timeoutMs = 20_000)) {
            BiDiLog.error("BiDiBrowser.start: waitForPort FAILED (port=$port), process.alive=${process?.isAlive}")
            process?.destroyForcibly()
            throw BrowserStartTimeoutException()
        }
        BiDiLog.info("BiDiBrowser.start: waitForPort OK (port=$port), process.alive=${process?.isAlive}")

        val t = BiDiTransport("ws://127.0.0.1:$port/session", scope)
        t.connect()
        transport = t
        BiDiLog.info("BiDiBrowser.start: BiDiTransport connected to ws://127.0.0.1:$port/session, transport.connected=${t.isConnected}")

        val sessionResult = t.send("session.new", buildJsonObject {
            put("capabilities", buildJsonObject {
                put("alwaysMatch", buildJsonObject {
                    put("webSocketUrl", JsonPrimitive(true))
                    put("acceptInsecureCerts", JsonPrimitive(true))
                })
            })
        })
        sessionId = sessionResult["sessionId"]?.jsonPrimitive?.contentOrNull
        BiDiLog.info("BiDiBrowser.start: session.new OK, sessionId=$sessionId")

        val treeResult = t.send("browsingContext.getTree", buildJsonObject {})
        val initialContext = treeResult["contexts"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("context")?.jsonPrimitive?.content
            ?: run {
                BiDiLog.error("BiDiBrowser.start: browsingContext.getTree returned no context! raw=${treeResult.toString().take(500)}")
                throw BrowserStartTimeoutException()
            }
        BiDiLog.info("BiDiBrowser.start: browsingContext.getTree OK, initialContext=$initialContext")

        val initialPage = BiDiPage(t, initialContext, this)
        _pages.add(initialPage)

        isAlive = true
        BiDiLog.success("BiDiBrowser.start: COMPLETE, isAlive=$isAlive, pages=${_pages.size}")
    }

    suspend fun newPage(url: String? = null): BiDiPage {
        val t = transport ?: throw BiDiException("not_started", "Browser not started")
        val openerPage = _pages.firstOrNull()
            ?: throw BiDiException("no_opener", "No opener page available to create new tab via window.open")
        BiDiLog.info("BiDiBrowser.newPage: BEGIN window.open (url=$url), openerContext=${openerPage.contextId}, process.alive=${process?.isAlive}, transport.connected=${t.isConnected}, pages=${_pages.size}")

        val targetUrl = url ?: "about:blank"
        val escapedUrl = targetUrl.replace("\\", "\\\\").replace("'", "\\'")
        val expression = "window.open('$escapedUrl')"

        val result = try {
            t.send("script.evaluate", buildJsonObject {
                put("expression", JsonPrimitive(expression))
                put("target", buildJsonObject { put("context", JsonPrimitive(openerPage.contextId)) })
                put("awaitPromise", JsonPrimitive(false))
            })
        } catch (e: Exception) {
            BiDiLog.error("BiDiBrowser.newPage: script.evaluate window.open FAILED: ${e::class.simpleName}: ${e.message}; process.alive=${process?.isAlive}, transport.connected=${t.isConnected}")
            throw e
        }

        val newContextId = result["result"]?.jsonObject
            ?.get("value")?.jsonObject
            ?.get("context")?.jsonPrimitive?.contentOrNull
            ?: run {
                BiDiLog.error("BiDiBrowser.newPage: window.open returned no context! raw=${result.toString().take(500)}")
                throw BiDiException("no_context", "window.open() did not return a new browsing context")
            }

        val page = BiDiPage(t, newContextId, this)
        synchronized(_pages) { _pages.add(page) }
        BiDiLog.success("BiDiBrowser.newPage: OK via window.open, newContextId=$newContextId, pages=${_pages.size}")
        return page
    }

    internal suspend fun closePage(contextId: String) {
        val t = transport ?: return
        try {
            t.send("browsingContext.close", buildJsonObject { put("context", JsonPrimitive(contextId)) })
        } catch (_: Exception) {
        }
        synchronized(_pages) { _pages.removeIf { it.contextId == contextId } }
    }

    suspend fun debugRaw(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val t = transport ?: throw BiDiException("not_started", "Browser not started")
        return t.send(method, params)
    }

    // 设计意图：删除整个 profile 目录后重建空白配置。这是"重置浏览器到出厂状态"的破坏性操作，
    // 不是普通初始化路径。start() 走的是 prepareProfile()（仅确保目录存在 + 覆盖 user.js），
    // 不会动已有登录态。本方法仅在用户显式要求重置 profile 时调用。
    suspend fun newProfile() {
        if (Files.exists(profilePath)) {
            profilePath.toFile().deleteRecursively()
        }
        Files.createDirectories(profilePath)
        writeUserJs()
    }

    private fun prepareProfile() {
        if (!Files.exists(profilePath)) {
            Files.createDirectories(profilePath)
        }
        writeUserJs()
    }

    private fun writeUserJs() {
        val sb = StringBuilder()
        sb.appendLine("""user_pref("remote.active-protocols", 1);""")
        sb.appendLine("""user_pref("remote.debugger.remote-enabled", true);""")
        sb.appendLine("""user_pref("devtools.debugger.remote-enabled", true);""")
        sb.appendLine("""user_pref("devtools.debugger.prompt-connection", false);""")
        sb.appendLine("""user_pref("marionette.enabled", true);""")
        sb.appendLine("""user_pref("marionette.port", 2828);""")
        sb.appendLine("""user_pref("remote.http.enabled", true);""")
        sb.appendLine("""user_pref("layout.css.devPixelsPerPx", "1");""")
        config.toFirefoxPrefs().forEach { (key, value) ->
            sb.appendLine(prefLine(key, value))
        }
        // proxy prefs（已展开的 firefox prefs，来自上游 ProxyConfig 展开；空 Map = 不写代理）
        proxyPrefs.forEach { (key, value) ->
            sb.appendLine(prefLine(key, value))
        }
        // 禁用 popup blocker：newPage() 依赖 window.open()，需保证非用户手势触发的 open 不被拦截。
        sb.appendLine("""user_pref("dom.disable_open_during_load", false);""")
        sb.appendLine("""user_pref("dom.popup_allowed_events", "click mousedown mouseup");""")
        if (downloadDir != null) {
            val abs = downloadDir.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"")
            sb.appendLine("""user_pref("browser.download.dir", "$abs");""")
            sb.appendLine("""user_pref("browser.download.folderList", 2);""")
            sb.appendLine("""user_pref("browser.download.useDownloadDir", true);""")
            sb.appendLine("""user_pref("browser.helperApps.neverAsk.saveToDisk", "text/plain,application/octet-stream,text/csv");""")
            sb.appendLine("""user_pref("browser.download.manager.showWhenStarting", false);""")
        }
        Files.writeString(profilePath.resolve("user.js"), sb.toString())
    }

    /** 渲染一条 `user_pref("key", value);` 行，照搬原有转义规则。 */
    private fun prefLine(key: String, value: Any): String {
        val v = when (value) {
            is Boolean -> if (value) "true" else "false"
            is Number -> value.toString()
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            else -> value.toString()
        }
        return """user_pref("$key", $v);"""
    }

    private fun findFreePort(): Int {
        while (true) {
            val port = Random.nextInt(10000, 60000)
            if (isPortAvailable(port)) return port
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use {
                it.reuseAddress = true
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = process
            if (p != null && !p.isAlive) return false
            try {
                java.net.Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(300)
            }
        }
        return false
    }

    override fun close() {
        isAlive = false
        val t = transport
        val procs = process
        kotlinx.coroutines.runBlocking {
            pages.toList().forEach { runCatching { it.close() } }
            t?.let { runCatching { it.close() } }
        }
        procs?.destroyForcibly()
        scope.cancel()
    }
}
