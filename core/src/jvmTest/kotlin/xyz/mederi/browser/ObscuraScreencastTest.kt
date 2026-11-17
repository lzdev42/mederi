package xyz.mederi.browser

import java.io.File
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.cdp.FakeCdpServer
import xyz.mederi.browser.obscura.ObscuraCdpClient
import xyz.mederi.browser.obscura.ObscuraCdpControl

/**
 * ObscuraCdpControl screencast 单测（ST4，观测落档）：[FakeCdpServer] 脚本化驱动，不起真 obscura 进程
 * （经 [ObscuraCdpClient.connect] 注入 WS 端点，同 ST2/ST3 模式；真二进制验证在 ST6）。
 *
 * 顺序验证并逐项落档 `core/build/obscura-screencast-test.json`（4 空格缩进，键值精确匹配验收字面量）：
 * - `contract_default_empty`：不 override [BrowserControl.screencastFrames] 的最小实现 →
 *   默认空流：`first()` 500ms 内无帧 → TimeoutCancellationException（空流且不抛错）
 * - `frames_flow`：订阅者就位（Page.startScreencast 到达服务端）后推 2 帧
 *   `Page.screencastFrame`（顶层 sessionId=page-1-session-1 + params.sessionId=integer 句柄 1）→
 *   [ScreencastFrame] 到达（data base64 解码一致、width/height = metadata.deviceWidth/Height）
 * - `ack_per_frame`：`Page.screencastFrameAck` 恰 2 次，params.sessionId 是 integer JsonPrimitive
 *   （!isString——原样转发，禁止取 content 转字符串/数字重构，ST1 实测坑）
 * - `stop_screencast`：订阅取消后服务端收到 `Page.stopScreencast`（恰 1 次且在全部 Ack 之后）
 *
 * 竞态纪律：实现内部保证"先订阅 events 后发 startScreencast"（onSubscription 确认，navigate 同款）；
 * 测试侧以"Page.startScreencast 命令已到达服务端"作为订阅确认信号，之后才 pushEvent（replay=0 不丢帧）。
 * 全部场景在一个 @Test 内顺序执行（单实例保观测 JSON 全键——照 ST3 测试模式）；
 * 每场景独立 FakeCdpServer + client + control（命令日志隔离），finally 一律关闭释放端口。
 */
class ObscuraScreencastTest {

    private val obsFile = File("build/obscura-screencast-test.json")
    private val observations = linkedMapOf<String, String>()
    private val json = Json { prettyPrint = true }

    /** 观测落档（照 ST3 record/persist 模式，每键即写）。 */
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

    @Test
    fun `screencast contract and cdp flow`() = runBlocking {
        contractDefaultEmpty()
        framesFlowAndAcks()
        stopScreencast()
    }

    // ==================== 测试基建 ====================

    /** 不 override screencastFrames 的最小 BrowserControl（直测契约 default 空流）。 */
    private val minimalControl = object : BrowserControl {
        override suspend fun start() = throw UnsupportedOperationException("minimal")
        override suspend fun close() = throw UnsupportedOperationException("minimal")
        override suspend fun getCurrentUrl(): String = throw UnsupportedOperationException("minimal")
        override suspend fun getTitle(): String = throw UnsupportedOperationException("minimal")
        override suspend fun navigate(url: String): OperationResult = throw UnsupportedOperationException("minimal")
        override suspend fun click(elementRef: String): OperationResult = throw UnsupportedOperationException("minimal")
        override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult = throw UnsupportedOperationException("minimal")
        override suspend fun type(elementRef: String, text: String): OperationResult = throw UnsupportedOperationException("minimal")
        override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult =
            throw UnsupportedOperationException("minimal")
        override suspend fun press(key: KeyboardKey): OperationResult = throw UnsupportedOperationException("minimal")
        override suspend fun snapshot(): PageSnapshot = throw UnsupportedOperationException("minimal")
        override suspend fun screenshot(): ByteArray? = throw UnsupportedOperationException("minimal")
    }

    /** 标准场景起法：脚本 Target.createTarget（page-1）+ connect 注入 + 构造 control。 */
    private fun newControl(server: FakeCdpServer): ObscuraCdpControl {
        server.onMethod("Target.createTarget") { buildJsonObject { put("targetId", "page-1") } }
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        return ObscuraCdpControl(client)
    }

    private suspend fun newScenario(): FakeCdpServer {
        val server = FakeCdpServer(attachSessionId = "page-1-session-1")
        server.start()
        return server
    }

    /** 探针实测形状：帧数据 base64 + metadata（数值统一 0.0 字面量）+ integer 句柄 sessionId。 */
    private fun frameParams(dataBase64: String, frameSeq: Int): JsonObject = buildJsonObject {
        put("data", JsonPrimitive(dataBase64))
        put(
            "metadata",
            buildJsonObject {
                put("offsetTop", 0.0)
                put("pageScaleFactor", 1.0)
                put("deviceWidth", 1280.0)
                put("deviceHeight", 720.0)
                put("scrollOffsetX", 0.0)
                put("scrollOffsetY", 0.0)
                put("timestamp", frameSeq.toDouble())
            },
        )
        put("sessionId", JsonPrimitive(1)) // integer 内部句柄（ST1 实测 =1），Ack 必须原样转发
    }

    /** 等 [method] 命令到达服务端（订阅确认信号 + 命令到达断言）。 */
    private suspend fun FakeCdpServer.awaitCommand(method: String) {
        withTimeout(5_000) {
            while (receivedCommandsFor(method).isEmpty()) delay(10)
        }
    }

    // ==================== 场景 1：contract_default_empty ====================

    private suspend fun contractDefaultEmpty() {
        val flow = minimalControl.screencastFrames()
        // 默认实现必须是空流：first() 拿不到任何帧。拍板 default = emptyFlow()（决策 1），
        // emptyFlow 立即完成 → first() 抛 NoSuchElementException（零帧）；不挂起实现的语义等价于
        // TimeoutCancellationException。两者都证明"无帧且不抛 UnsupportedOperationException"。
        val e = runCatching {
            withTimeout(5_000) { flow.first() }
        }.exceptionOrNull()
        assertTrue(
            e is NoSuchElementException || e is TimeoutCancellationException,
            "契约 default 必须是空流（first() 零帧完成/挂起超时）而非抛错，实际: ${e?.let { it::class.simpleName }} ${e?.message}",
        )
        record("contract_default_empty", "ok")
    }

    // ==================== 场景 2：frames_flow + ack_per_frame ====================

    private suspend fun framesFlowAndAcks() {
        val server = newScenario()
        try {
            server.onMethod("Page.startScreencast") { buildJsonObject { put("obscuraFrameSource", "activity-driven") } }
            server.onMethod("Page.screencastFrameAck") { buildJsonObject {} }
            server.onMethod("Page.stopScreencast") { buildJsonObject {} }
            val control = newControl(server)
            try {
                control.start()
                val expected = "hello world".toByteArray()
                val b64 = java.util.Base64.getEncoder().encodeToString(expected)

                val frames = mutableListOf<ScreencastFrame>()
                val done = CompletableDeferred<Unit>()
                coroutineScope {
                    val consumer = launch {
                        // 冷流：本收集触发 Page.startScreencast；take 耗尽即取消收集。
                        // collect 逐帧追加（toList 会缓冲满 2 帧才返回，导致单帧断言死等）
                        control.screencastFrames().take(2).collect { frames += it }
                        done.complete(Unit)
                    }
                    // 订阅确认：实现内部先订阅后发命令，startScreencast 到达服务端即订阅已就位（replay=0 不丢帧）
                    server.awaitCommand("Page.startScreencast")
                    // 活动驱动帧（顶层 sessionId = flat 路由身份；params.sessionId = integer 句柄）；
                    // 逐帧推并等消费（防止循环补推导致多余帧/多余 Ack，保证恰 2 帧 2 Ack）
                    server.pushEvent("Page.screencastFrame", frameParams(b64, 1), sessionId = "page-1-session-1")
                    withTimeout(5_000) {
                        while (frames.size < 1) delay(10)
                    }
                    server.pushEvent("Page.screencastFrame", frameParams(b64, 2), sessionId = "page-1-session-1")
                    withTimeout(5_000) { done.await() }
                    consumer.join()
                }

                // frames_flow：2 帧到达，data base64 解码一致，width/height 取 metadata
                assertEquals(2, frames.size, "take(2) 后必须收到 2 帧")
                frames.forEach { f ->
                    assertTrue(Arrays.equals(f.data, expected), "帧 data 必须 base64 解码一致")
                    assertEquals(1280, f.width, "width 必须取 metadata.deviceWidth")
                    assertEquals(720, f.height, "height 必须取 metadata.deviceHeight")
                    assertEquals("1.0", f.metadata["pageScaleFactor"], "metadata 数值统一字符串化: ${f.metadata}")
                }
                record("frames_flow", "ok")

                // ack_per_frame：恰 2 次 Ack，params.sessionId 原样 integer JsonPrimitive（!isString）
                val acks = server.receivedCommandsFor("Page.screencastFrameAck")
                assertEquals(2, acks.size, "每帧必须 Ack 一次: ${acks.map { it.params }}")
                acks.forEach { cmd ->
                    val sid = cmd.params["sessionId"] as? JsonPrimitive
                    assertTrue(sid != null && !sid.isString, "Ack sessionId 必须原样 integer JsonPrimitive: ${cmd.params}")
                    assertEquals("1", sid?.content)
                    assertEquals("page-1-session-1", cmd.sessionId, "Ack 帧必须携带 page session（flat 路由）")
                }
                record("ack_per_frame", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }

    // ==================== 场景 3：stop_screencast ====================

    private suspend fun stopScreencast() {
        val server = newScenario()
        try {
            server.onMethod("Page.startScreencast") { buildJsonObject { put("obscuraFrameSource", "activity-driven") } }
            server.onMethod("Page.screencastFrameAck") { buildJsonObject {} }
            server.onMethod("Page.stopScreencast") { buildJsonObject {} }
            val control = newControl(server)
            try {
                control.start()
                val b64 = java.util.Base64.getEncoder().encodeToString("frame".toByteArray())
                val done = CompletableDeferred<Unit>()
                coroutineScope {
                    val consumer = launch {
                        control.screencastFrames().take(1).toList()
                        done.complete(Unit)
                    }
                    server.awaitCommand("Page.startScreencast")
                    server.pushEvent("Page.screencastFrame", frameParams(b64, 1), sessionId = "page-1-session-1")
                    withTimeout(5_000) { done.await() }
                    consumer.join()
                }
                // 收集取消 → Page.stopScreencast（恰 1 次，且在全部 Ack 之后；stop 在独立 scope 发出，轮询等待）
                withTimeout(5_000) {
                    while (server.receivedCommandsFor("Page.stopScreencast").size < 1) delay(10)
                }
                val stops = server.receivedCommandsFor("Page.stopScreencast")
                assertEquals(1, stops.size, "取消收集后必须恰好一次 stopScreencast")
                val all = server.receivedCommands
                val stopIdx = all.indexOf(stops.single())
                val lastAckIdx = all.indexOfLast { it.method == "Page.screencastFrameAck" }
                assertTrue(lastAckIdx >= 0 && stopIdx > lastAckIdx, "stop 必须在全部 Ack 之后: $all")
                record("stop_screencast", "ok")
            } finally {
                runCatching { control.close() }
            }
        } finally {
            server.stop()
        }
    }
}
