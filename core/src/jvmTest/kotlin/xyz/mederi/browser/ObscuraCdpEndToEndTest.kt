package xyz.mederi.browser

import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import xyz.mederi.browser.obscura.CdpCommandException
import xyz.mederi.browser.obscura.ObscuraCdpClient
import xyz.mederi.browser.obscura.ObscuraCdpControl
import xyz.mederi.debug.DebugLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Obscura CDP 真二进制 e2e 全链路整体验收（ST6）：用 release 真 obscura 二进制走
 * `ObscuraCdpControl(ObscuraCdpClient(binary))` **完整路径（含新后端自己的 spawn 进程管理**——
 * 不 connect 注入、不手动起进程）驱动 [BrowserControl] 契约全链路，证明 MCP 后端替换后在真实
 * 环境可用，且进程生命周期干净（无孤儿）。
 *
 * 观测落档 `core/build/obscura-cdp-e2e.json`（4 空格缩进，逐键即写——ST1/ST3 模式）。
 * 八个验收键（键名精确）：e2e_navigate / e2e_click_counter / e2e_fill_input / e2e_scroll /
 * e2e_tabs / e2e_screenshot / e2e_screencast_frame / e2e_process_reaped——值 "ok"；
 * 任一契约调用抛异常/断言不成立 → 该键落档 fail + 错误原文，**继续后续键**（acceptance 采集要全貌暴露）；
 * 最后统一断言八键全 ok，任一 fail 则测试 fail。
 *
 * 失败语义：
 * - 二进制找不到 → assumeTrue 整类跳过
 * - 基础设施坏（进程起不来 / ready-file / WS 连接 / attach 抛异常）→ 记档后 throw（测试 fail）
 * - 步骤内契约异常/断言失败 → 该键 fail + 原文，链路不中断
 *
 * 竞态纪律（ST1/ST2/ST4 教训）：
 * - screencast **先订阅后触发**：等收集协程就位（producer 订阅 → startScreencast 先于出帧）再触发
 *   页面活动（活动驱动出帧：纯 sleep 无帧，冒烟实测需 wheel/DOM 变化）；withTimeout(10s) 上界收第一帧；
 *   收集取消后给 fire-and-forget 的 stopScreencast（ST4 报告）200ms 缓冲再 close。
 * - evaluateJavascript 数值一律 Double 解析断言（ST1 实测：CDP 数值字面量 0.0/1.0，
 *   evalString 字符串化为 "1.0"/"400.0"——断数值相等，不断字符串相等）。
 *
 * 副作用补偿：e2e_tabs 关的是**当前 tab**（先 selectTab 后 closeTab）→ 会话作废，下一个
 * 会话调用按契约自愈出新 about:blank tab；screenshot 步先重新导航回测试页（恢复全链路页面
 * 上下文，也是 screencast 活动驱动的前置条件）。
 *
 * 前置事实（2026-11-16 真二进制人工冒烟 + ST1 探针，全部通过，直接采用）：
 * ready-file JSON {pid,host,port,websocket_path}；data:URL 导航正常；Input 真实点击计数器 0→1；
 * wheel 真实滚动 scrollY 0→400；screencast 活动驱动出帧（~26KB PNG）；tabs 开/列/关正常
 * （serve 无自动初始页：createTarget 后 getTargets 恰含所建 target）；getFullAXTree 节点齐全。
 */
class ObscuraCdpEndToEndTest {

    private val obsFile = File("build/obscura-cdp-e2e.json")
    private val observations = linkedMapOf<String, String>()
    private val json = Json { prettyPrint = true }

    private var client: ObscuraCdpClient? = null
    private var control: ObscuraCdpControl? = null
    private var lastProcess: Process? = null
    private var binaryPath: String? = null
    /** 测试前已存在的 obscura 匹配进程基线（孤儿判定排除项）。 */
    private var baselinePids: Set<Int> = emptySet()

    /** 主链 control（步骤内断言已初始化；未初始化是测试基建错误，直接抛）。 */
    private fun ctl(): ObscuraCdpControl = control ?: error("control not initialized")

    /** 主链 client（process 回收断言用）。 */
    private fun client(): ObscuraCdpClient = client ?: error("client not initialized")

    // ==================== 观测落档（ST1/ST3 record/persist 模式，每键即写）====================

    private fun record(key: String, value: String) {
        observations[key] = value
        obsFile.parentFile?.mkdirs()
        obsFile.writeText(
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(observations.mapValues { JsonPrimitive(it.value) }),
            ),
        )
    }

    /** 步骤执行器：成功落 ok；异常落 fail + 原文，**不中断链路**（acceptance 全貌暴露）。 */
    private suspend fun step(key: String, block: suspend () -> Unit) {
        try {
            block()
            record(key, "ok")
        } catch (e: TimeoutCancellationException) {
            record(key, "fail:timeout: ${e.message ?: "withTimeout 上界"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: CdpCommandException) {
            record(key, "fail:${e.message?.take(200)}${e.raw?.let { "; raw=${it.take(200)}" } ?: ""}")
        } catch (e: Exception) {
            record(key, "fail:${e.message?.take(300) ?: e.javaClass.simpleName}")
        }
    }

    @AfterTest
    fun after() {
        // 双保险：close 幂等（进程回收 SIGTERM→2s→SIGKILL 已封装）；仍活强杀
        runCatching { runBlocking { control?.close() } }
        val p = lastProcess
        runCatching {
            if (p != null && p.isAlive) {
                p.destroy()
                if (!p.waitFor(1, TimeUnit.SECONDS) && p.isAlive) p.destroyForcibly()
            }
        }
    }

    // ==================== 测试主体（单 @Test 顺序跑全链路：单实例保观测完整）====================

    @Test
    fun `e2e full chain on real obscura binary`() = runBlocking {
        // ---- 1. 前置：二进制定位（ST1 模式）+ assumeTrue 门控 ----
        val binary = locateObscura()
        assumeTrue("obscura binary not found under release/Mederi.app/Contents/Resources", binary != null)
        binaryPath = binary
        baselinePids = pgrep("obscura").toSet() // 基线：排除测试前已存在的匹配（如执行 harness 命令行自匹配）
        record("e2e_infra", "binary=$binary | baseline_obscura_pids=$baselinePids")

        // ---- 完整路径：ObscuraCdpControl(ObscuraCdpClient(binary))——含 spawn（测的就是新后端自己的进程管理）----
        val c = ObscuraCdpClient(binary)
        val ctl = ObscuraCdpControl(c)
        client = c
        control = ctl
        try {
            ctl.start() // spawn + ready-file + WS 连接 + attach
        } catch (e: Throwable) {
            record("e2e_start", "fail: ${e.message?.take(300)}")
            DebugLog.error("ObscuraCdpE2E", "e2e start failed", e)
            runCatching { c.close() }
            throw AssertionError("e2e infra: obscura start failed: ${e.message}", e)
        }

        try {
            // ---- 2~9. 全链路顺序执行（任一键 fail 继续采集全貌）----
            e2eNavigate()
            e2eClickCounter()
            e2eFillInput()
            e2eScroll()
            e2eTabs()
            e2eScreenshot()
            e2eScreencastFrame()
            e2eProcessReaped()
        } finally {
            runCatching { ctl.close() } // 幂等双保险
        }

        // ---- 最终验收：八键全 ok ----
        val failed = REQUIRED_KEYS.filter { observations[it] != "ok" }
        assertTrue(
            failed.isEmpty(),
            "e2e acceptance: 八键未全 ok: ${failed.associateWith { observations[it] ?: "<missing>" }}",
        )
    }

    // ==================== 2. e2e_navigate：data:URL 导航 ====================

    /** data:URL 测试页导航 → getCurrentUrl 含 data: 前缀 + getTitle 不抛（title 取 <title> 或空，断言不强制——决策）。 */
    private suspend fun e2eNavigate() = step("e2e_navigate") {
        val ctl = ctl()
        val res = ctl.navigate(testPageUrl())
        if (!res.success) error("navigate not success: $res")
        val url = ctl.getCurrentUrl()
        if (!url.startsWith("data:")) error("getCurrentUrl missing data: prefix: $url")
        val title = ctl.getTitle()
        record("e2e_navigate_detail", "url=${url.take(80)}… | title=$title")
    }

    /** 测试页 data:URL（spec 固定 HTML；URLEncoder UTF-8，空格 + → %20）。 */
    private fun testPageUrl(): String =
        "data:text/html;charset=utf-8," + URLEncoder.encode(pageHtml, Charsets.UTF_8).replace("+", "%20")

    // ==================== 3. e2e_click_counter：快照 → ref → 真实点击闭环 ====================

    /** snapshot（URL:/Title: 前缀 + `- button "go" [ref=eN]` 行）→ 取 ref → click → window.__c == 1。 */
    private suspend fun e2eClickCounter() = step("e2e_click_counter") {
        val ctl = ctl()
        val snap = ctl.snapshot()
        val lines = snap.yaml.lines()
        if (lines.firstOrNull()?.startsWith("URL: ") != true) {
            error("snapshot 缺 URL: 首行: ${lines.firstOrNull()}")
        }
        if (lines.none { it.startsWith("Title: ") }) error("snapshot 缺 Title: 行: ${lines.take(3)}")
        // 快照格式最终确认（可选键，验收字面量不含——落档即可）
        val fmtLine = lines.firstOrNull { it.matches(SNAP_LINE_FORMAT) }
        record("e2e_snapshot_format", if (fmtLine != null) "ok" else "fail:no-line-matching-format")
        val line = lines.firstOrNull { it.matches(BUTTON_GO_LINE) }
            ?: error("no `- button \"go\" [ref=eN]` line: ${snap.yaml.take(400)}")
        val ref = line.substringAfter("ref=").substringBefore("]")
        val cr = ctl.click(ref)
        if (!cr.success) error("click($ref) not success: $cr")
        delay(300) // 等 onclick 生效（ST1 探针实测宽限）
        // 数值经 evalString 字符串化为 "1.0"（ST1：CDP 数值字面量 0.0/1.0）——断数值相等
        val counter = ctl.evaluateJavascript("window.__c")
        if (counter.toDoubleOrNull() != 1.0) error("window.__c after click: $counter (expected 1)")
        record("e2e_click_detail", "ref=$ref | counter=$counter | line=$line | pageTitle=${snap.title}")
    }

    // ==================== 4. e2e_fill_input：locator fill ====================

    /** locator("input").fill("hello") → evaluateJavascript 读 input.value == "hello"（fill = evaluate 设值 + input/change 事件）。 */
    private suspend fun e2eFillInput() = step("e2e_fill_input") {
        val ctl = ctl()
        val fr = ctl.locator("input").fill("hello")
        if (!fr.success) error("fill not success: $fr")
        val value = ctl.evaluateJavascript("document.getElementById('i').value")
        if (value != "hello") error("input value after fill: $value (expected hello)")
        record("e2e_fill_detail", "fill=$fr | value=$value")
    }

    // ==================== 5. e2e_scroll：真实滚轮页面级滚动 ====================

    /** scrollPage(0,400)（视口中心真实 mouseWheel）→ window.scrollY == 400（ST1 实测 1:1）。 */
    private suspend fun e2eScroll() = step("e2e_scroll") {
        val ctl = ctl()
        val sr = ctl.scrollPage(0, 400)
        if (!sr.success) error("scrollPage not success: $sr")
        delay(300)
        // scrollY 数值同样走 "400.0" 字符串化（ST1）——断数值相等
        val y = ctl.evaluateJavascript("window.scrollY")
        if (y.toDoubleOrNull()?.toInt() != 400) error("scrollY after scrollPage(0,400): $y (expected 400)")
        record("e2e_scroll_detail", "scrollY=$y")
    }

    // ==================== 6. e2e_tabs：Target 域开/列/切/关 ====================

    /** openTab → listTabs==2 → selectTab(新 tab) → closeTab(新 tab) → listTabs==1（serve 无自动初始页，ST1 实测）。 */
    private suspend fun e2eTabs() = step("e2e_tabs") {
        val ctl = ctl()
        val before = ctl.listTabs()
        if (before.size != 1) error("listTabs before openTab: ${before.size} (expected 1): $before")
        val open = ctl.openTab()
        if (!open.success) error("openTab not success: $open")
        val after = ctl.listTabs()
        if (after.size != 2) error("listTabs after openTab: ${after.size} (expected 2): $after")
        val newId = after.firstOrNull { it !in before }
            ?: error("no new tab id: after=$after before=$before")
        val sel = ctl.selectTab(newId)
        if (!sel.success) error("selectTab not success: $sel")
        val cl = ctl.closeTab(newId)
        if (!cl.success) error("closeTab not success: $cl")
        val finalTabs = ctl.listTabs()
        if (finalTabs.size != 1) error("listTabs after closeTab: ${finalTabs.size} (expected 1): $finalTabs")
        record("e2e_tabs_detail", "before=$before | afterOpen=$after | selectedClosed=$newId | final=$finalTabs")
    }

    // ==================== 7. e2e_screenshot：PNG 魔数 ====================

    /**
     * screenshot() 非空且前 8 字节为 PNG 魔数（实际断前 4 字节 0x89 0x50 0x4E 0x47 + 长度 ≥8）。
     * 前置补偿：e2e_tabs 关的是当前 tab → 会话作废自愈出新 about:blank tab（契约语义），
     * 先重新导航回测试页（恢复全链路页面上下文，也是 screencast 活动驱动的前置条件）。
     */
    private suspend fun e2eScreenshot() = step("e2e_screenshot") {
        val ctl = ctl()
        val nav = ctl.navigate(testPageUrl())
        if (!nav.success) error("post-tabs re-navigate not success: $nav")
        val png = ctl.screenshot() ?: error("screenshot returned null")
        if (png.size < 8) error("screenshot too small: ${png.size} bytes")
        val head = png.take(4).map { it.toInt() and 0xFF }
        if (head != listOf(0x89, 0x50, 0x4E, 0x47)) {
            error("not PNG magic: ${head.joinToString("") { "%02X".format(it) }}")
        }
        record("e2e_screenshot_detail", "bytes=${png.size} | magic=${head.joinToString("") { "%02X".format(it) }}")
    }

    // ==================== 8. e2e_screencast_frame：活动驱动第一帧 ====================

    /**
     * 先订阅（收集协程就位 = 订阅 → startScreencast 先于出帧）再触发页面活动（活动驱动：纯 sleep 无帧）；
     * withTimeout(10s) 收第一帧，断 data.size>0 且 width/height>0；
     * 取消收集（stopScreencast 随取消传导）后给 200ms 缓冲（stop 是 fire-and-forget，ST4 报告）。
     */
    private suspend fun e2eScreencastFrame() = step("e2e_screencast_frame") {
        val ctl = ctl()
        coroutineScope {
            val frameDeferred = CompletableDeferred<ScreencastFrame>()
            val collector = launch {
                try {
                    frameDeferred.complete(ctl.screencastFrames().first())
                } catch (e: CancellationException) {
                    if (!frameDeferred.isCompleted) frameDeferred.completeExceptionally(e)
                    throw e
                } catch (e: Throwable) {
                    if (!frameDeferred.isCompleted) frameDeferred.completeExceptionally(e)
                }
            }
            try {
                val frame = withTimeout(10_000) {
                    delay(500) // 收集协程就位（先订阅后触发）
                    while (true) {
                        if (frameDeferred.isCompleted) break
                        // 活动驱动出帧：真实滚轮触发页面变化（ST1/冒烟实测 wheel 出帧）
                        runCatching { ctl.scrollPage(0, 50) }
                            .onFailure { DebugLog.info("ObscuraCdpE2E", "screencast activity scroll: ${it.message}") }
                        withTimeoutOrNull(500) { frameDeferred.await() }?.let { break }
                    }
                    frameDeferred.await()
                }
                if (frame.data.isEmpty()) error("screencast frame data empty")
                if (frame.width <= 0 || frame.height <= 0) error("screencast frame size invalid: ${frame.width}x${frame.height}")
                record(
                    "e2e_screencast_frame_detail",
                    "bytes=${frame.data.size} | ${frame.width}x${frame.height} | metadata=${frame.metadata}",
                )
            } finally {
                collector.cancel() // 取消收集 → stopScreencast（传导）
                delay(200) // stop fire-and-forget：close 前给缓冲
            }
        }
    }

    // ==================== 9. e2e_process_reaped：进程回收 + 无孤儿 ====================

    /** close 前取 process → close → 2.5s 宽限内 !isAlive（SIGTERM 2s 窗口）+ pgrep 全局无 obscura 孤儿。 */
    private suspend fun e2eProcessReaped() = step("e2e_process_reaped") {
        val proc = client().process ?: error("client.process is null before close (spawn 路径未走?)")
        lastProcess = proc
        if (!proc.isAlive) error("obscura process already dead before close: 无法验证回收")
        val t0 = System.currentTimeMillis()
        ctl().close()
        val deadline = System.currentTimeMillis() + 2_500 // SIGTERM 2s 宽限
        while (proc.isAlive && System.currentTimeMillis() < deadline) delay(100)
        if (proc.isAlive) error("obscura process still alive ${System.currentTimeMillis() - t0}ms after close")
        // 全局检查：无以 obscura 二进制启动的孤儿进程（至多 3 次 × 500ms 复查，容忍关停下瞬态子进程）
        var orphans = orphanObscuraPids()
        var rechecks = 0
        while (orphans.isNotEmpty() && rechecks < 3) {
            delay(500)
            orphans = orphanObscuraPids()
            rechecks++
        }
        if (orphans.isNotEmpty()) error("orphan obscura processes after close: $orphans")
        record(
            "e2e_process_reaped_detail",
            "pid=${proc.pid()} | closeMs=${System.currentTimeMillis() - t0} | orphanPids=<empty> | rechecks=$rechecks | aliveAfterClose=false",
        )
    }

    /**
     * 孤儿判定：`pgrep -f obscura` 候选中仅保留**以 obscura 二进制启动**的进程（命令行以二进制定位路径开头）——
     * 排除执行 harness 的命令行自匹配（测试 JVM 祖先进程命令行可含 "obscura" 字样，实测 pgrep 会命中）、
     * 测试前已存在的基线进程、测试 JVM 自身。
     */
    private fun orphanObscuraPids(): List<Int> {
        val bin = binaryPath ?: return emptyList()
        val selfPid = (ManagementFactory.getRuntimeMXBean().name as String).split("@").firstOrNull()?.toIntOrNull()
        return pgrep("obscura").filter { it != selfPid && it !in baselinePids && processCommand(it).startsWith(bin) }
    }

    private fun pgrep(pattern: String): List<Int> {
        val p = ProcessBuilder("pgrep", "-f", pattern).start()
        val out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        runCatching { p.waitFor() }
        return out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList()
    }

    /** 进程命令行（ps -ww -o command=；进程已退出为空串）。 */
    private fun processCommand(pid: Int): String {
        val p = ProcessBuilder("ps", "-ww", "-o", "command=", "-p", pid.toString()).start()
        val s = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        runCatching { p.waitFor() }
        return s.trim()
    }

    // ==================== 二进制定位（ST1 模式：≤7 级向上找 release/Mederi.app）====================

    private fun locateObscura(): String? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(7) {
            val d = dir ?: return null
            val candidate = File(d, "release/Mederi.app/Contents/Resources/obscura")
            if (candidate.isFile && candidate.canExecute()) return candidate.canonicalPath
            dir = d.parentFile
        }
        return null
    }

    // ==================== 测试页（spec 固定）：按钮计数器 + input + 4000px 高内容（可滚动）+ __c 初值 ====================

    private val pageHtml =
        "<html><head><title>E2E</title></head><body>" +
            "<button id=b onclick=\"window.__c=(window.__c||0)+1\">go</button>" +
            "<input id=i value=x>" +
            "<div id=tall style=\"height:4000px\">t</div>" +
            "<script>window.__c=0</script></body></html>"

    companion object {
        /** 八个验收键（键名精确；任一非 ok 则测试 fail）。 */
        val REQUIRED_KEYS = listOf(
            "e2e_navigate", "e2e_click_counter", "e2e_fill_input", "e2e_scroll",
            "e2e_tabs", "e2e_screenshot", "e2e_screencast_frame", "e2e_process_reaped",
        )

        /** 点击步主链路行：`- button "go" [ref=eN]`。 */
        val BUTTON_GO_LINE = Regex("- button \"go\" \\[ref=e\\d+\\]")

        /** 快照格式最终确认正则（可选键）：`- <role> "<name>" [ref=eN]`。 */
        val SNAP_LINE_FORMAT = Regex("- \\w+ \".*\" \\[ref=e\\d+\\]")
    }
}
