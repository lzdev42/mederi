package xyz.mederi.debug

/**
 * 流式链路结构化记录器：内存 ring buffer 存事件，导出即排查，不刷终端。
 *
 * 解决两个痛点：
 * 1. DebugLog 逐帧打屏没法过滤、终端滚没就丢 → 按事件元数据存内存（环形覆盖），
 *    出问题时一次性导出 JSON / 摘要，终端零噪音
 * 2. "一字母一行"这类症状靠人肉翻日志比对 delta 粒度太费劲 → 记录器自动统计
 *    delta 粒度与类型交错，结束时直接给出 suspicion 结论
 *
 * 覆盖链路（按 layer 分桶，各自环形）：
 * - Stream.Delta   — 每个 MESSAGE_DELTA（type + 长度，不存内容）
 * - Stream.Summary — 每条流的 begin/close 汇总（粒度/交错/断流结论）
 *
 * 用法：
 * ```
 * val session = StreamTrace.beginSession("sess_x")
 * StreamTrace.delta(session, "reasoning", 3)
 * StreamTrace.closeSession(session, note = "normal")
 * // 排查时：
 * println(StreamTrace.summary())      // 终端快速看
 * StreamTrace.exportJson()            // 全量事件导出
 * ```
 */
object StreamTrace {

    /** 单 layer 最大事件数（环形覆盖最旧的）。事件只存元数据不存内容，够回放整条流 */
    private const val CAPACITY = 2048

    /** 最多保留最近几条流的会话统计 */
    private const val MAX_SESSIONS = 8

    /** 单条事件（t = 相对进程起点的毫秒） */
    data class Entry(val t: Long, val layer: String, val fields: Map<String, String>)

    /** 一条 LLM 流的会话统计（beginSession 到 closeSession） */
    class StreamSession(val id: String) {
        var deltaCount = 0L; private set
        var charCount = 0L; private set
        var minDeltaLen = Int.MAX_VALUE; private set
        var maxGapMs = 0L; private set
        var transitions = 0L; private set
        var suspicion: String? = null; private set

        private var lastNs = 0L
        private var lastContentType: String? = null

        /** 记一个内容 delta。type ∈ reasoning/text/tool_call */
        fun onDelta(type: String, len: Int) {
            deltaCount++
            charCount += len
            if (len in 1 until minDeltaLen) minDeltaLen = len
            val now = System.nanoTime()
            if (lastNs != 0L) {
                val gap = (now - lastNs) / 1_000_000
                if (gap > maxGapMs) maxGapMs = gap
            }
            lastNs = now
            if (len > 0) {
                if (lastContentType != null && type != lastContentType) transitions++
                lastContentType = type
            }
        }

        /** 流结束时自动判别：类型交错或逐字符分片 = "一字母一行"两大特征 */
        fun finalizeVerdict() {
            if (suspicion != null) return
            if (deltaCount >= 30 && transitions >= 20) {
                suspicion = "type-interleave: $transitions 次 reasoning/text 交替 → 块被切碎"
            } else if (deltaCount >= 100 && minDeltaLen <= 2) {
                suspicion = "tiny-delta: minDeltaLen=$minDeltaLen / $deltaCount 个 delta → 逐字符分片"
            }
        }
    }

    private val lock = Any()
    private val buffers = HashMap<String, ArrayDeque<Entry>>()
    private val sessions = ArrayDeque<StreamSession>()
    private val t0 = System.nanoTime()

    /** 新开一条流式会话（一条 LLM 流 = 一个 session，begin 到 close 期间的所有 delta 归属它） */
    fun beginSession(id: String): StreamSession {
        val s = StreamSession(id)
        synchronized(lock) {
            sessions.addLast(s)
            while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
        }
        record("Stream.Summary", mapOf("event" to "begin", "id" to id))
        return s
    }

    fun record(layer: String, fields: Map<String, String>) {
        val e = Entry((System.nanoTime() - t0) / 1_000_000, layer, fields)
        synchronized(lock) {
            val buf = buffers.getOrPut(layer) { ArrayDeque() }
            buf.addLast(e)
            while (buf.size > CAPACITY) buf.removeFirst()
        }
    }

    /** delta 采样：记事件 + 更新会话统计。 */
    fun delta(session: StreamSession, type: String, len: Int) {
        session.onDelta(type, len)
        record("Stream.Delta", mapOf("id" to session.id, "type" to type, "len" to len.toString()))
    }

    fun closeSession(session: StreamSession, note: String? = null) {
        session.finalizeVerdict()
        record(
            "Stream.Summary",
            buildMap {
                put("event", "close")
                put("id", session.id)
                put("deltas", session.deltaCount.toString())
                put("chars", session.charCount.toString())
                put("minDeltaLen", if (session.minDeltaLen == Int.MAX_VALUE) "-" else session.minDeltaLen.toString())
                put("maxGapMs", session.maxGapMs.toString())
                put("transitions", session.transitions.toString())
                session.suspicion?.let { put("SUSPICION", it) }
                note?.let { put("note", it) }
            }
        )
    }

    /** 导出全部事件为 JSON（按时间排序，含每条流结论），排查时调用 */
    fun exportJson(): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("{\n  \"sessions\": [\n")
        sessions.forEachIndexed { i, s ->
            sb.append("    {\"id\": \"${s.id}\", \"deltas\": ${s.deltaCount}, \"chars\": ${s.charCount}, ")
            sb.append("\"minDeltaLen\": ${if (s.minDeltaLen == Int.MAX_VALUE) -1 else s.minDeltaLen}, ")
            sb.append("\"maxGapMs\": ${s.maxGapMs}, \"transitions\": ${s.transitions}, ")
            sb.append("\"suspicion\": ${s.suspicion?.let { "\"$it\"" } ?: "null"}}")
            sb.append(if (i == sessions.lastIndex) "\n" else ",\n")
        }
        sb.append("  ],\n  \"events\": [\n")
        val all = buffers.flatMap { (layer, buf) -> buf.map { layer to it } }.sortedBy { it.second.t }
        all.forEachIndexed { i, (_, e) ->
            sb.append("    {\"t\": ${e.t}, \"layer\": \"${e.layer}\", ")
            sb.append(e.fields.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" })
            sb.append("}")
            sb.append(if (i == all.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n}")
        sb.toString()
    }

    /** 人类可读摘要（终端快速查看最近几条流的结论） */
    fun summary(): String = synchronized(lock) {
        if (sessions.isEmpty()) return "[StreamTrace] no sessions"
        sessions.joinToString("\n") { s ->
            "[${s.id}] deltas=${s.deltaCount} chars=${s.charCount} " +
                "minDeltaLen=${if (s.minDeltaLen == Int.MAX_VALUE) "-" else s.minDeltaLen} " +
                "maxGapMs=${s.maxGapMs} transitions=${s.transitions} " +
                "suspicion=${s.suspicion ?: "-"}"
        }
    }
}
