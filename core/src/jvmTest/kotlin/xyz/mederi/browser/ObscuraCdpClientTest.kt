package xyz.mederi.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.mederi.browser.cdp.FakeCdpServer
import xyz.mederi.browser.obscura.CdpCommandException
import xyz.mederi.browser.obscura.CdpEvent
import xyz.mederi.browser.obscura.ObscuraCdpClient
import java.io.File

/**
 * ObscuraCdpClient 单测（ST2，观测落档）：连 [FakeCdpServer]（不起真 obscura——真二进制的进程
 * 生命周期由 ST1 探针 / ST6 e2e 验证，本模块经 `ObscuraCdpClient.connect(endpoint)` 注入直连路径）。
 *
 * 顺序验证并逐项落档 `core/build/obscura-cdp-client-test.json`（4 空格缩进，照
 * obscura-probe.json 落档模式；键值精确匹配验收字面量）：
 * - `wire_roundtrip`：send Page.navigate → 假服务端收到 `{"id":N,"method":"Page.navigate","params":{...}}`，
 *   客户端拿到对应 result
 * - `event_flow`：假服务端推事件帧（Page.loadEventFired 带 sessionId；Page.screencastFrame 的
 *   params.sessionId 为 integer 内部句柄）→ 客户端 events 订阅者收到 CdpEvent（method/params/sessionId
 *   正确，integer 句柄原样透传不转字符串）
 * - `session_routing`：attachToTarget 封装返回显式 session；带 sessionId 的 send → 服务端收到的帧含
 *   "sessionId" 字段值正确（flat 路由）
 * - `close_idempotent`：close() 两次不抛异常；WS 关闭（服务端 session 清零）；close 后 send 抛明确
 *   异常；start() 可再次调用安全（重连）
 * - `error_propagation`（可选项）：error 应答 → send 抛携带 code + message 的 CdpCommandException
 *
 * 全部场景在一个 @Test 内顺序执行（JUnit 每方法新实例，单实例保观测 JSON 全键——照探针测试模式）。
 * FakeCdpServer 全程本地回环、随机端口，finally 一律 stop 释放端口。
 */
class ObscuraCdpClientTest {

    private val obsFile = File("build/obscura-cdp-client-test.json")
    private val observations = linkedMapOf<String, String>()
    private val json = Json { prettyPrint = true }

    /** 观测落档（照 ObscuraMcpProbeTest / ObscuraCdpProbeTest 的 record/persist 模式，每键即写）。 */
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
    fun `cdp client wire and event flows`() = runBlocking {
        val server = FakeCdpServer(attachSessionId = "page-1-session-1")
        server.start()
        try {
            wireRoundTrip(server)
            eventFlow(server)
            sessionRouting(server)
            closeIdempotent(server)
            errorPropagation(server)
        } finally {
            server.stop() // 端口释放
        }
    }

    // ==================== 场景 1：wire_roundtrip ====================

    private suspend fun wireRoundTrip(server: FakeCdpServer) {
        server.onMethod("Page.navigate") { params ->
            buildJsonObject {
                put("frameId", "page-1")
                put("loaderId", "loader-test")
                put("url", params["url"] ?: JsonPrimitive("<missing>")) // 回显收到的 url，证明 params 送达
            }
        }
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        try {
            client.start()
            val result = client.send(
                "Page.navigate",
                buildJsonObject { put("url", "data:text/html,ok") },
            )
            // 客户端拿到对应 result
            assertEquals("page-1", (result["frameId"] as? JsonPrimitive)?.content)
            assertEquals("data:text/html,ok", (result["url"] as? JsonPrimitive)?.content)
            // 服务端收到的帧形状：{"id":N,"method":"Page.navigate","params":{"url":...}}
            val cmds = server.receivedCommandsFor("Page.navigate")
            assertEquals(1, cmds.size)
            val cmd = cmds.single()
            assertNotNull(cmd.id, "命令帧必须带 id")
            assertEquals("data:text/html,ok", (cmd.params["url"] as? JsonPrimitive)?.content)
            assertNull(cmd.sessionId, "未带 session 时帧上不应有 sessionId 字段")
            record("wire_roundtrip", "ok")
        } finally {
            runCatching { client.close() }
        }
    }

    // ==================== 场景 2：event_flow ====================

    private suspend fun eventFlow(server: FakeCdpServer) {
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        client.start()
        try {
            val scope = SupervisorJob()
            val loadEvent = CompletableDeferred<CdpEvent>()
            val frameEvent = CompletableDeferred<CdpEvent>()
            val subscribed = CompletableDeferred<Unit>()
            val collector = CoroutineScope(scope + Dispatchers.Default).launch {
                // 订阅就位信号：先发信号再开始处理事件（SharedFlow replay=0：无订阅者推送即丢帧）
                client.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { ev ->
                        when (ev.method) {
                            "Page.loadEventFired" -> if (!loadEvent.isCompleted) loadEvent.complete(ev)
                            "Page.screencastFrame" -> if (!frameEvent.isCompleted) frameEvent.complete(ev)
                        }
                    }
            }
            withTimeout(5_000) { subscribed.await() }
            // ① 带字符串 sessionId（flat 路由身份）的事件
            server.pushEvent(
                "Page.loadEventFired",
                buildJsonObject { put("timestamp", 1794806468.840024) },
                sessionId = "page-1-session-1",
            )
            // ② screencastFrame：params.sessionId 是 integer 内部句柄——模块必须原样透传
            //   （ST4 上层 Ack 时原样转发；转字符串会报 "sessionId must be an integer"）
            server.pushEvent(
                "Page.screencastFrame",
                buildJsonObject {
                    put("data", "iVBORw0KGgoAAAANSUhEUg==")
                    put("sessionId", JsonPrimitive(1)) // integer 句柄
                    put("metadata", buildJsonObject { put("timestamp", 1794806470.3) })
                },
                sessionId = "page-1-session-1",
            )
            val ev1 = withTimeout(5_000) { loadEvent.await() }
            val ev2 = withTimeout(5_000) { frameEvent.await() }
            assertEquals("Page.loadEventFired", ev1.method)
            assertEquals("page-1-session-1", ev1.sessionId)
            assertEquals(1794806468.840024, (ev1.params["timestamp"] as? JsonPrimitive)?.content?.toDoubleOrNull())
            assertEquals("page-1-session-1", ev2.sessionId)
            val handle = ev2.params["sessionId"]
            assertTrue(
                handle is JsonPrimitive && !handle.isString,
                "screencastFrame params.sessionId 必须原样透传（integer 句柄），实际: $handle",
            )
            collector.cancel()
            scope.cancel()
            record("event_flow", "ok")
        } finally {
            runCatching { client.close() }
        }
    }

    // ==================== 场景 3：session_routing ====================

    private suspend fun sessionRouting(server: FakeCdpServer) {
        server.onMethod("Page.navigate") { buildJsonObject { put("frameId", "page-1") } }
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        client.start()
        try {
            // 封装：显式 attach 返回显式 session（假服务端固定 sessionId = 唯一路由身份）
            val sid = client.attachToTarget("page-1")
            assertEquals("page-1-session-1", sid)
            // attach 帧形状：Target.attachToTarget {"targetId":"page-1","flatten":true}
            val attach = server.receivedCommandsFor("Target.attachToTarget").single()
            assertEquals("page-1", (attach.params["targetId"] as? JsonPrimitive)?.content)
            assertEquals("true", (attach.params["flatten"] as? JsonPrimitive)?.content)
            assertNull(attach.sessionId)
            // 带 sessionId 的后续命令 → 帧含 "sessionId" 字段且值正确（flat 路由）
            client.send(
                "Page.navigate",
                buildJsonObject { put("url", "about:blank") },
                sessionId = sid,
            )
            val nav = server.receivedCommandsFor("Page.navigate").last()
            assertEquals("page-1-session-1", nav.sessionId)
            record("session_routing", "ok")
        } finally {
            runCatching { client.close() }
        }
    }

    // ==================== 场景 4：close_idempotent ====================

    private suspend fun closeIdempotent(server: FakeCdpServer) {
        server.onMethod("Page.enable") { buildJsonObject {} }
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        client.start()
        client.close()
        client.close() // 第二次 close：不抛异常（幂等）
        // WS 确实关闭：服务端 session 清零
        withTimeout(5_000) {
            while (server.activeSessions > 0) delay(50)
        }
        // close 后 send 抛明确异常（不挂死、不静默）
        val afterClose: CdpCommandException? = try {
            client.send("Page.enable")
            null
        } catch (e: CdpCommandException) {
            e
        }
        assertNotNull(afterClose, "close 后 send 必须抛 CdpCommandException")
        assertNull(afterClose.code, "本地通讯层失败（未连接）不携带 CDP error code")
        // start() 再次调用安全：重连后命令可用
        client.start()
        val empty = client.send("Page.enable")
        assertEquals(0, empty.size)
        client.close()
        record("close_idempotent", "ok")
    }

    // ==================== 场景 5：error_propagation（可选键） ====================

    private suspend fun errorPropagation(server: FakeCdpServer) {
        // error 形状照 ST1 探针实测的真 obscura 禁用错误（-32601 原文）
        server.onMethodError(
            "DOM.setFileInputFiles",
            -32601,
            "DOM.setFileInputFiles is disabled. Restart with `obscura serve --allow-file-access` to enable local file uploads.",
        )
        val client = ObscuraCdpClient.connect(server.endpoint, commandTimeoutMs = 5_000)
        client.start()
        try {
            val ex: CdpCommandException? = try {
                client.send("DOM.setFileInputFiles", buildJsonObject { put("nodeId", 0) })
                null
            } catch (e: CdpCommandException) {
                e
            }
            val thrown = ex ?: throw AssertionError("error 应答必须抛 CdpCommandException")
            assertEquals(-32601, thrown.code)
            assertTrue(thrown.message!!.contains("DOM.setFileInputFiles is disabled"))
            record("error_propagation", "ok")
        } finally {
            runCatching { client.close() }
        }
    }
}
