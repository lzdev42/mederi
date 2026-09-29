package xyz.mederi.browser.bidi

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class BiDiPage internal constructor(
    private val transport: BiDiTransport,
    val contextId: String,
    private val browser: BiDiBrowser
) : AutoCloseable {

    val uuid: String = Random.nextLong().toString()

    private val nodeCache = ConcurrentHashMap<String, AxNode>()

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    @Volatile private var closed = false

    var onNewPage: ((url: String) -> Unit)? = null

    // ────────────────────────────────────────────────
    // 导航
    // ────────────────────────────────────────────────

    suspend fun navigate(url: String, wait: String = "complete") {

        _loadingState.value = LoadingState.LOADING
        transport.send("browsingContext.navigate", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("url", JsonPrimitive(url))
            put("wait", JsonPrimitive(wait))
        })
        _currentUrl.value = url
        refreshMeta()
        _loadingState.value = LoadingState.FINISHED
    }

    suspend fun loadUrl(url: String) {
        navigate(url, wait = "complete")
    }

    suspend fun goBack() {

        transport.send("browsingContext.traverseHistory", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("delta", JsonPrimitive(-1))
        })
        refreshMeta()
    }

    suspend fun goForward() {

        transport.send("browsingContext.traverseHistory", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("delta", JsonPrimitive(1))
        })
        refreshMeta()
    }

    suspend fun reload(wait: String = "complete") {

        _loadingState.value = LoadingState.LOADING
        transport.send("browsingContext.reload", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("wait", JsonPrimitive(wait))
        })
        refreshMeta()
        _loadingState.value = LoadingState.FINISHED
    }

    suspend fun getUrl(): String {

        val result = runCatching {
            transport.send("browsingContext.getTree", buildJsonObject {})
        }.getOrNull()
        val treeUrl = result?.let { r ->
            val contexts = r["contexts"]?.jsonArray
            contexts?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        }
        if (!treeUrl.isNullOrEmpty() && treeUrl != "about:blank") return treeUrl
        return evaluateJavascript("location.href")
    }

    suspend fun getTitle(): String {

        return evaluateJavascript("document.title")
    }

    // ────────────────────────────────────────────────
    // 快照
    // ────────────────────────────────────────────────

    suspend fun snapshot(mode: SnapshotMode = SnapshotMode.CLEAN): SnapshotResult {

        val raw = evaluateJavascript(BiDiJsScripts.EXTRACT_SNAPSHOT)
        val tree = Json.decodeFromString<AxTreeData>(raw)
        nodeCache.clear()
        tree.nodes.forEach { nodeCache[it.refid] = it }
        refreshMetaFromTree(tree)
        val yaml = tree.toYamlSnapshot(clean = (mode == SnapshotMode.CLEAN))
        return SnapshotResult(yaml, tree)
    }

    suspend fun getRawAxTree(): AxTreeData {

        val raw = evaluateJavascript(BiDiJsScripts.EXTRACT_SNAPSHOT)
        val tree = Json.decodeFromString<AxTreeData>(raw)
        nodeCache.clear()
        tree.nodes.forEach { nodeCache[it.refid] = it }
        return tree
    }

    suspend fun evaluateJavascript(script: String): String {

        val result = transport.send("script.evaluate", buildJsonObject {
            put("expression", JsonPrimitive(script))
            put("target", buildJsonObject { put("context", JsonPrimitive(contextId)) })
            put("awaitPromise", JsonPrimitive(false))
        })
        val resObj = result["result"]?.jsonObject ?: return ""
        val type = resObj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "undefined" || type == "null") return ""
        return resObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    // ────────────────────────────────────────────────
    // refId 操作（从 nodeCache 查坐标）
    // ────────────────────────────────────────────────

    suspend fun click(refid: String): OperationResult {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        return clickByCoordinates(node.centerX, node.centerY)
    }

    suspend fun jsClick(refid: String) {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        evaluateJavascript("var __e=document.querySelector('${escapeJsString(node.selector)}');if(__e)__e.click();")
    }

    suspend fun hover(refid: String): OperationResult {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        hoverByCoordinates(node.centerX, node.centerY)
        return OperationResult.Acknowledged
    }

    suspend fun jsHover(refid: String) {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        evaluateJavascript(
            "var __e=document.querySelector('${escapeJsString(node.selector)}');" +
            "if(__e){__e.dispatchEvent(new MouseEvent('mouseover',{bubbles:true,cancelable:true}));" +
            "__e.dispatchEvent(new MouseEvent('mouseenter',{bubbles:false,cancelable:true}));}"
        )
    }

    suspend fun scroll(refid: String, deltaX: Int, deltaY: Int): OperationResult {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        return scrollByCoordinates(node.centerX, node.centerY, deltaX, deltaY)
    }

    suspend fun jsScroll(refid: String, deltaX: Int, deltaY: Int) {

        evaluateJavascript(
            "var __e=document.querySelector('${escapeJsString(nodeCache[refid]?.selector ?: "")}');" +
            "if(__e)__e.scrollBy($deltaX,$deltaY);"
        )
    }

    suspend fun drag(startRefid: String, endRefid: String) {

        val src = nodeCache[startRefid] ?: throw ElementNotFoundException(startRefid)
        val dst = nodeCache[endRefid] ?: throw ElementNotFoundException(endRefid)
        dragByCoordinates(src.centerX, src.centerY, dst.centerX, dst.centerY)
    }

    suspend fun jsDrag(startRefid: String, endRefid: String) {

        val src = nodeCache[startRefid] ?: throw ElementNotFoundException(startRefid)
        val dst = nodeCache[endRefid] ?: throw ElementNotFoundException(endRefid)
        evaluateJavascript(
            "var __s=document.querySelector('${escapeJsString(src.selector)}')," +
            "__d=document.querySelector('${escapeJsString(dst.selector)}');" +
            "if(__s&&__d){var __e=new DragEvent('dragstart',{dataTransfer:new DataTransfer()});__s.dispatchEvent(__e);" +
            "var __e2=new DragEvent('drop',{dataTransfer:__e.dataTransfer});__d.dispatchEvent(__e2);}"
        )
    }

    suspend fun uploadFile(refid: String, filePaths: List<String>) {

        val node = nodeCache[refid] ?: throw ElementNotFoundException(refid)
        val selector = node.selector
        val fileSelector = if (selector.isNotEmpty() && selector.lowercase().contains("input")) {
            selector
        } else {
            "input[type=file]"
        }
        val locateResult = transport.send("browsingContext.locateNodes", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("locator", buildJsonObject {
                put("type", JsonPrimitive("css"))
                put("value", JsonPrimitive(fileSelector))
            })
        })
        val nodes = locateResult["nodes"]?.jsonArray
        val sharedId = nodes?.firstOrNull()?.jsonObject?.get("sharedId")?.jsonPrimitive?.contentOrNull
        if (sharedId != null) {
            transport.send("input.setFiles", buildJsonObject {
                put("context", JsonPrimitive(contextId))
                put("element", buildJsonObject { put("sharedId", JsonPrimitive(sharedId)) })
                put("files", buildJsonArray { filePaths.forEach { add(JsonPrimitive(it)) } })
            })
        } else {
            val fallbackSelector = "input[type=file]"
            val fallbackResult = transport.send("browsingContext.locateNodes", buildJsonObject {
                put("context", JsonPrimitive(contextId))
                put("locator", buildJsonObject {
                    put("type", JsonPrimitive("css"))
                    put("value", JsonPrimitive(fallbackSelector))
                })
            })
            val fallbackNodes = fallbackResult["nodes"]?.jsonArray
            val fallbackSharedId = fallbackNodes?.firstOrNull()?.jsonObject?.get("sharedId")?.jsonPrimitive?.contentOrNull
            if (fallbackSharedId != null) {
                transport.send("input.setFiles", buildJsonObject {
                    put("context", JsonPrimitive(contextId))
                    put("element", buildJsonObject { put("sharedId", JsonPrimitive(fallbackSharedId)) })
                    put("files", buildJsonArray { filePaths.forEach { add(JsonPrimitive(it)) } })
                })
            } else {
                throw BiDiException("upload_file_failed", "Cannot locate file input element via BiDi locateNodes")
            }
        }
    }

    suspend fun uploadFileBySelector(selector: String, filePaths: List<String>) {

        val locateResult = transport.send("browsingContext.locateNodes", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("locator", buildJsonObject {
                put("type", JsonPrimitive("css"))
                put("value", JsonPrimitive(selector))
            })
        })
        val nodes = locateResult["nodes"]?.jsonArray
        val sharedId = nodes?.firstOrNull()?.jsonObject?.get("sharedId")?.jsonPrimitive?.contentOrNull
        if (sharedId != null) {
            transport.send("input.setFiles", buildJsonObject {
                put("context", JsonPrimitive(contextId))
                put("element", buildJsonObject { put("sharedId", JsonPrimitive(sharedId)) })
                put("files", buildJsonArray { filePaths.forEach { add(JsonPrimitive(it)) } })
            })
        } else {
            throw BiDiException("upload_file_failed", "Cannot locate file input element by selector: $selector")
        }
    }

    // ────────────────────────────────────────────────
    // 坐标操作（文档坐标 → 视口坐标转换）
    // ────────────────────────────────────────────────

    suspend fun clickByCoordinates(x: Int, y: Int): OperationResult {

        val vp = smartScrollIntoView(x, y)
        if (browser.config.humanize) {
            performHumanClick(vp.first, vp.second)
        } else {
            performPointerClick(vp.first, vp.second)
        }
        delay(200)
        return OperationResult.Acknowledged
    }

    suspend fun hoverByCoordinates(x: Int, y: Int) {

        val vp = ensureInViewport(x, y)
        if (browser.config.humanize) {
            performHumanHover(vp.first, vp.second)
        } else {
            performPointerHover(vp.first, vp.second)
        }
    }

    suspend fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int): OperationResult {

        val scroll = getScroll()
        performWheelScroll(x - scroll.x, y - scroll.y, deltaX, deltaY)
        delay(300)
        return OperationResult.Acknowledged
    }

    suspend fun dragByCoordinates(startDocX: Int, startDocY: Int, endDocX: Int, endDocY: Int) {

        val vp = smartScrollIntoView(startDocX, startDocY)
        val scroll = getScroll()
        val ex = endDocX - scroll.x
        val ey = endDocY - scroll.y
        performHumanDrag(vp.first, vp.second, ex, ey)
    }

    // ────────────────────────────────────────────────
    // 键盘
    // ────────────────────────────────────────────────

    suspend fun press(key: KeyboardKey) {

        val code = key.toBiDiCode()
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("key"))
                    put("id", JsonPrimitive("k1"))
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("keyDown")); put("value", JsonPrimitive(code)) })
                        add(buildJsonObject { put("type", JsonPrimitive("keyUp")); put("value", JsonPrimitive(code)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    suspend fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {

        val modCode = modifier.toBiDiCode()
        val keyCode = key.toBiDiCode()
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("key"))
                    put("id", JsonPrimitive("k1"))
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("keyDown")); put("value", JsonPrimitive(modCode)) })
                        add(buildJsonObject { put("type", JsonPrimitive("keyDown")); put("value", JsonPrimitive(keyCode)) })
                        add(buildJsonObject { put("type", JsonPrimitive("keyUp")); put("value", JsonPrimitive(keyCode)) })
                        add(buildJsonObject { put("type", JsonPrimitive("keyUp")); put("value", JsonPrimitive(modCode)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    suspend fun typeChar(char: Char) {

        val value = char.toString()
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("key"))
                    put("id", JsonPrimitive("k1"))
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("keyDown")); put("value", JsonPrimitive(value)) })
                        add(buildJsonObject { put("type", JsonPrimitive("keyUp")); put("value", JsonPrimitive(value)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    suspend fun type(text: String) {

        for (ch in text) {
            typeChar(ch)
            delay(Random.nextLong(30, 150))
        }
    }

    // ────────────────────────────────────────────────
    // Locator 工厂
    // ────────────────────────────────────────────────

    fun locator(selector: String): BiDiLocator {
        return if (selector.startsWith("xpath=")) {
            BiDiLocator(this, selector.removePrefix("xpath="), SelectorType.XPATH)
        } else {
            val css = selector.removePrefix("css=")
            BiDiLocator(this, css, SelectorType.CSS)
        }
    }

    fun getByRole(role: String, name: String? = null): BiDiLocator =
        BiDiLocator(this, role, SelectorType.ROLE, name = name)

    fun getByText(text: String, exact: Boolean = true): BiDiLocator =
        BiDiLocator(this, text, SelectorType.TEXT, exact = exact)

    fun getByLabel(label: String): BiDiLocator =
        BiDiLocator(this, label, SelectorType.LABEL)

    fun getByPlaceholder(text: String): BiDiLocator =
        BiDiLocator(this, text, SelectorType.PLACEHOLDER)

    fun getByAltText(text: String): BiDiLocator =
        BiDiLocator(this, text, SelectorType.ALT_TEXT)

    fun getByTitle(title: String): BiDiLocator =
        BiDiLocator(this, title, SelectorType.TITLE)

    fun getByTestId(testId: String): BiDiLocator =
        BiDiLocator(this, testId, SelectorType.TEST_ID)

    // ────────────────────────────────────────────────
    // 截图
    // ────────────────────────────────────────────────

    suspend fun screenshot(): ByteArray? {

        val result = transport.send("browsingContext.captureScreenshot", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("origin", JsonPrimitive("viewport"))
        })
        val data = result["data"]?.jsonPrimitive?.contentOrNull ?: return null
        return java.util.Base64.getDecoder().decode(data)
    }

    suspend fun screenshotClip(docX: Int, docY: Int, width: Int, height: Int): ByteArray? {

        val scroll = getScroll()
        val vx = docX - scroll.x
        val vy = docY - scroll.y
        val result = transport.send("browsingContext.captureScreenshot", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("origin", JsonPrimitive("viewport"))
            put("clip", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("x", JsonPrimitive(vx))
                put("y", JsonPrimitive(vy))
                put("width", JsonPrimitive(width))
                put("height", JsonPrimitive(height))
            })
        })
        val data = result["data"]?.jsonPrimitive?.contentOrNull ?: return null
        return java.util.Base64.getDecoder().decode(data)
    }

    // ────────────────────────────────────────────────
    // Cookie
    // ────────────────────────────────────────────────

    suspend fun clearCacheAndCookies() {

        runCatching {
            transport.send("storage.deleteCookies", buildJsonObject {})
        }.onFailure {
            evaluateJavascript(
                "try{localStorage.clear();sessionStorage.clear();}catch(e){}" +
                "document.cookie.split(';').forEach(function(c){" +
                "document.cookie=c.replace(/^ +/,'').replace(/=.*/,'=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/');});"
            )
        }
    }

    suspend fun setCookie(domain: String, name: String, value: String, path: String = "/", httpOnly: Boolean = false, secure: Boolean = false, sameSite: String = "none", expiry: Long? = null) {

        val cookieObj = buildJsonObject {
            put("domain", JsonPrimitive(domain))
            put("name", JsonPrimitive(name))
            put("value", buildJsonObject { put("type", JsonPrimitive("string")); put("value", JsonPrimitive(value)) })
            put("path", JsonPrimitive(path))
            put("httpOnly", JsonPrimitive(httpOnly))
            put("secure", JsonPrimitive(secure))
            put("sameSite", JsonPrimitive(sameSite))
            expiry?.let { put("expiry", JsonPrimitive(it)) }
        }
        val nativeOk = runCatching {
            transport.send("storage.setCookie", buildJsonObject { put("cookie", cookieObj) })
            true
        }.getOrDefault(false)
        if (!nativeOk) {
            evaluateJavascript("document.cookie='${escapeJsString(name)}=${escapeJsString(value)};domain=${escapeJsString(domain)};path=${escapeJsString(path)}${if (secure) ";secure" else ""}${if (httpOnly) ";httpOnly" else ""}';")
        }
    }

    suspend fun setCookieViaJs(cookieString: String) {

        evaluateJavascript("document.cookie='${escapeJsString(cookieString)}';")
    }

    // ────────────────────────────────────────────────
    // 生命周期
    // ────────────────────────────────────────────────

    override fun close() {
        if (closed) return
        closed = true
        kotlinx.coroutines.runBlocking {
            runCatching { browser.closePage(contextId) }
        }
    }

    // ────────────────────────────────────────────────
    // 内部实现
    // ────────────────────────────────────────────────

    private suspend fun refreshMeta() {
        val raw = evaluateJavascript("JSON.stringify({u:location.href,t:document.title})")
        if (raw.isNotEmpty()) {
            try {
                val obj = Json.parseToJsonElement(raw).jsonObject
                _currentUrl.value = obj["u"]?.jsonPrimitive?.contentOrNull
                _title.value = obj["t"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshMetaFromTree(tree: AxTreeData) {
        _currentUrl.value = tree.url
    }

    internal suspend fun getScroll(): Point {
        val raw = evaluateJavascript("JSON.stringify({x:window.scrollX,y:window.scrollY})")
        if (raw.isEmpty()) return Point(0, 0)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            Point(obj["x"]?.jsonPrimitive?.intOrNull ?: 0, obj["y"]?.jsonPrimitive?.intOrNull ?: 0)
        } catch (_: Exception) {
            Point(0, 0)
        }
    }

    suspend fun getViewportInfo(): ViewportInfo {
        val raw = evaluateJavascript(
            "JSON.stringify({scrollX:window.scrollX,scrollY:window.scrollY,innerWidth:window.innerWidth,innerHeight:window.innerHeight})"
        )
        if (raw.isEmpty()) return ViewportInfo(0, 0, 1280, 800)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            ViewportInfo(
                obj["scrollX"]?.jsonPrimitive?.intOrNull ?: 0,
                obj["scrollY"]?.jsonPrimitive?.intOrNull ?: 0,
                obj["innerWidth"]?.jsonPrimitive?.intOrNull ?: 1280,
                obj["innerHeight"]?.jsonPrimitive?.intOrNull ?: 800
            )
        } catch (_: Exception) {
            ViewportInfo(0, 0, 1280, 800)
        }
    }

    private suspend fun smartScrollIntoView(docX: Int, docY: Int): Pair<Int, Int> {
        val info = getViewportInfo()
        val clientX = docX - info.scrollX
        val clientY = docY - info.scrollY

        val outOfViewport = clientX < 0 || clientX > info.viewW || clientY < 0 || clientY > info.viewH

        if (outOfViewport) {
            val targetScrollX = ((docX - info.viewW / 2).coerceAtLeast(0)).toInt()
            val targetScrollY = ((docY - info.viewH / 2).coerceAtLeast(0)).toInt()
            evaluateJavascript("window.scrollTo($targetScrollX, $targetScrollY);")
            delay(150)
        }

        val infoAfter = getViewportInfo()
        val cx = docX - infoAfter.scrollX
        val cy = docY - infoAfter.scrollY

        if (cx in 0..infoAfter.viewW && cy in 0..infoAfter.viewH) {
            return Pair(cx, cy)
        }

        return Pair(clientX.coerceIn(0, info.viewW), clientY.coerceIn(0, info.viewH))
    }

    private suspend fun ensureInViewport(docX: Int, docY: Int): Pair<Int, Int> {
        val info = getViewportInfo()
        val clientX = docX - info.scrollX
        val clientY = docY - info.scrollY
        val outOfViewport = clientX < 0 || clientX > info.viewW || clientY < 0 || clientY > info.viewH
        if (!outOfViewport) return Pair(clientX, clientY)
        return smartScrollIntoView(docX, docY)
    }

    private suspend fun performPointerClick(vx: Int, vy: Int) {
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("pointer"))
                    put("id", JsonPrimitive("p1"))
                    put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("pointerMove")); put("x", JsonPrimitive(vx)); put("y", JsonPrimitive(vy)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pointerDown")); put("button", JsonPrimitive(0)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pause")); put("duration", JsonPrimitive(50)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pointerUp")); put("button", JsonPrimitive(0)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    private suspend fun performPointerHover(vx: Int, vy: Int) {
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("pointer"))
                    put("id", JsonPrimitive("p1"))
                    put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("pointerMove")); put("x", JsonPrimitive(vx)); put("y", JsonPrimitive(vy)); put("duration", JsonPrimitive(120)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pause")); put("duration", JsonPrimitive(100)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    // humanize=true 时的拟人化点击：从目标附近抖动起点沿贝塞尔曲线移动到 (vx, vy) 再按下/释放。
    private suspend fun performHumanClick(vx: Int, vy: Int) {
        val (sx, sy) = HumanMouse.microJitter(25)
        val path = HumanMouse.bezierPath(sx.toDouble(), sy.toDouble(), vx.toDouble(), vy.toDouble(), steps = 25)
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("pointer"))
                    put("id", JsonPrimitive("p1"))
                    put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                    put("actions", buildJsonArray {
                        for (p in path) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("pointerMove"))
                                put("x", JsonPrimitive(p.x))
                                put("y", JsonPrimitive(p.y))
                                put("duration", JsonPrimitive(p.durationMs))
                            })
                        }
                        add(buildJsonObject { put("type", JsonPrimitive("pointerDown")); put("button", JsonPrimitive(0)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pause")); put("duration", JsonPrimitive(50)) })
                        add(buildJsonObject { put("type", JsonPrimitive("pointerUp")); put("button", JsonPrimitive(0)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    // humanize=true 时的拟人化悬停：沿贝塞尔曲线移动到目标点再停留。
    private suspend fun performHumanHover(vx: Int, vy: Int) {
        val (sx, sy) = HumanMouse.microJitter(25)
        val path = HumanMouse.bezierPath(sx.toDouble(), sy.toDouble(), vx.toDouble(), vy.toDouble(), steps = 25)
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("pointer"))
                    put("id", JsonPrimitive("p1"))
                    put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                    put("actions", buildJsonArray {
                        for (p in path) {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("pointerMove"))
                                put("x", JsonPrimitive(p.x))
                                put("y", JsonPrimitive(p.y))
                                put("duration", JsonPrimitive(p.durationMs))
                            })
                        }
                        add(buildJsonObject { put("type", JsonPrimitive("pause")); put("duration", JsonPrimitive(100)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    private suspend fun performWheelScroll(vx: Int, vy: Int, deltaX: Int, deltaY: Int) {
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("wheel"))
                    put("id", JsonPrimitive("w1"))
                    put("actions", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("scroll"))
                            put("x", JsonPrimitive(vx))
                            put("y", JsonPrimitive(vy))
                            put("deltaX", JsonPrimitive(deltaX))
                            put("deltaY", JsonPrimitive(deltaY))
                        })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })
    }

    private suspend fun performHumanDrag(sx: Int, sy: Int, ex: Int, ey: Int) {
        evaluateJavascript(
            "(function(){" +
            "window.__capturedDt=null;" +
            "document.addEventListener('dragstart',function(e){" +
            "window.__capturedDt=e.dataTransfer;" +
            "},true);" +
            "document.addEventListener('dragover',function(e){e.preventDefault();},true);" +
            "})()"
        )
        delay(50)

        val path = HumanMouse.bezierPath(sx.toDouble(), sy.toDouble(), ex.toDouble(), ey.toDouble(), steps = 30)
        val (jx, jy) = HumanMouse.microJitter(5)
        val dragActions = buildJsonArray {
            val seq = buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("pointerMove")); put("x", JsonPrimitive(sx)); put("y", JsonPrimitive(sy)); put("duration", JsonPrimitive(100)) })
                add(buildJsonObject { put("type", JsonPrimitive("pointerDown")); put("button", JsonPrimitive(0)) })
                add(buildJsonObject { put("type", JsonPrimitive("pointerMove")); put("x", JsonPrimitive(sx + jx)); put("y", JsonPrimitive(sy + jy)); put("duration", JsonPrimitive(60)) })
                for (p in path) {
                    if (p.x == sx && p.y == sy) continue
                    add(buildJsonObject {
                        put("type", JsonPrimitive("pointerMove"))
                        put("x", JsonPrimitive(p.x))
                        put("y", JsonPrimitive(p.y))
                        put("duration", JsonPrimitive(p.durationMs))
                    })
                }
                add(buildJsonObject { put("type", JsonPrimitive("pause")); put("duration", JsonPrimitive(80)) })
            }
            add(buildJsonObject {
                put("type", JsonPrimitive("pointer"))
                put("id", JsonPrimitive("p1"))
                put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                put("actions", seq)
            })
        }
        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", dragActions)
        })

        runCatching {
            evaluateJavascript(
                "(function(){" +
                "if(!window.__capturedDt)return JSON.stringify({err:'no_dt'});" +
                "var dst=document.elementFromPoint($ex,$ey);" +
                "if(!dst)return JSON.stringify({err:'no_dst'});" +
                "dst.dispatchEvent(new DragEvent('dragover',{bubbles:true,cancelable:true,dataTransfer:window.__capturedDt,clientX:$ex,clientY:$ey}));" +
                "dst.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:window.__capturedDt,clientX:$ex,clientY:$ey}));" +
                "return JSON.stringify({ok:true});" +
                "})()"
            )
        }

        transport.send("input.performActions", buildJsonObject {
            put("context", JsonPrimitive(contextId))
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("pointer"))
                    put("id", JsonPrimitive("p1"))
                    put("parameters", buildJsonObject { put("pointerType", JsonPrimitive("mouse")) })
                    put("actions", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("pointerUp")); put("button", JsonPrimitive(0)) })
                    })
                })
            })
        })
        transport.send("input.releaseActions", buildJsonObject { put("context", JsonPrimitive(contextId)) })

        evaluateJavascript(
            "(function(){" +
            "delete window.__capturedDt;" +
            "})()"
        )
    }
}

internal fun escapeJsString(s: String): String {
    return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
}
