package xyz.mederi.debug

/**
 * 流式时间戳/速率统计器。
 *
 * 回答"流式输出是渐进到达还是瞬间爆发"这个核心问题，每次 close 打一条汇总：
 * ```
 * [mederi:SSE-Timing] INFO sse-lines done: blocks=340 chars=23110 ttfb=412ms duration=9800ms maxGap=210ms avgGap=29ms
 * ```
 * 判读方法：
 * - duration ≈ 0ms：所有块同一时刻到达（端点一次性吐完 / 假流式）
 * - duration 大、maxGap 大：端点中途憋住后集中吐（缓冲特征）
 * - duration 大、avgGap 稳定：真流式，正常渐进输出
 *
 * 采样只做计数，每 [heartbeatMs] 打一条 progress（长流可观察推进节奏），
 * 不会像逐帧打印那样淹没终端。日志走 [DebugLog] INFO 级。
 *
 * 注意：本类是有状态的单次统计对象，**每次 flow 收集都要新建实例**
 * （cold flow 可能被多次收集，共享实例会串数据），推荐写法：
 * ```
 * flow {
 *     val timing = StreamTimingLog("SSE-Timing", "sse-lines")
 *     try { emitAll(...) } finally { timing.close() }
 * }
 * ```
 */
class StreamTimingLog(
    private val layer: String,
    private val tag: String,
    private val heartbeatMs: Long = 1000
) {
    private val createdNs = System.nanoTime()
    private var firstNs = 0L
    private var lastNs = 0L
    private var count = 0L
    private var chars = 0L
    private var maxGapMs = 0f
    private var lastHeartbeatNs = 0L

    /** 记录一块数据到达。[bytes] 为该块内容长度（用于判断吞吐，0 表示无内容帧） */
    fun sample(bytes: Int = 0) {
        val now = System.nanoTime()
        if (firstNs == 0L) {
            firstNs = now
            lastHeartbeatNs = now
        } else {
            val gapMs = (now - lastNs) / 1_000_000f
            if (gapMs > maxGapMs) maxGapMs = gapMs
        }
        count++
        chars += bytes
        lastNs = now
        if (now - lastHeartbeatNs >= heartbeatMs * 1_000_000) {
            lastHeartbeatNs = now
            DebugLog.info(layer, "$tag progress: blocks=$count chars=$chars elapsed=${elapsedMs(now)}ms")
        }
    }

    /** 打汇总。异常/取消路径也会走到（finally），此时打的是已收集部分的数据 */
    fun close() {
        if (count == 0L) {
            DebugLog.info(
                layer,
                "$tag done: blocks=0 (no data received), waited=${(System.nanoTime() - createdNs) / 1_000_000}ms"
            )
            return
        }
        val durationMs = (lastNs - firstNs) / 1_000_000f
        val avgGapMs = if (count > 1) durationMs / (count - 1) else 0f
        DebugLog.info(
            layer,
            "$tag done: blocks=$count chars=$chars ttfb=${ttfbMs()}ms " +
                "duration=${durationMs.formatted()}ms maxGap=${maxGapMs.formatted()}ms avgGap=${avgGapMs.formatted()}ms"
        )
    }

    private fun ttfbMs(): Long = if (firstNs == 0L) -1 else (firstNs - createdNs) / 1_000_000

    private fun elapsedMs(now: Long): Long = if (firstNs == 0L) 0 else (now - firstNs) / 1_000_000

    private fun Float.formatted(): String = "%.0f".format(this)
}
