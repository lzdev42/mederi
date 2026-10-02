package xyz.mederi.tools.subagent

/**
 * 子代理流式 progress 的**窗口聚合器**（纯逻辑、无协程，可单测）。
 *
 * 由 [SubagentRunnerImpl] 的 progress 订阅驱动，把高频 MESSAGE_DELTA 帧聚合成低频
 * SUBAGENT_PROGRESS 事件，解决「事件量与带宽随 token 数线性增长」的问题。
 *
 * 规则：
 * - 文本 / 推理帧先落入 [buffer]，距上次 flush ≥ [throttleMillis] 时 [flushIfDue] 才真正产出
 *   一帧（窗口吸收高频增量）；
 * - `tool_call` 帧只记录工具名与活动态，**不把参数 JSON 分片放进 delta**（碎片会污染 UI
 *   实时输出视窗）；TOOL_CALLED 事件同此处理；
 * - 尾部不丢：会话终止前调用 [flushNow] 冲刷残留（SubagentRunnerImpl 的 finally 兜底）。
 *
 * [nowMillis] 可注入假时钟，测试即可确定性覆盖「窗口未到 / 窗口已到 / 尾部冲刷」三种时序。
 */
class SubagentProgressBatch(
    private val throttleMillis: Long = 120L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    /** 聚合后的一帧（delta 为窗口内累积文本；tool 帧的 delta 恒为空串）。 */
    data class Frame(
        val activity: String,
        val delta: String,
        val toolName: String?,
        val isMessage: Boolean
    )

    private var lastFlushAt = 0L
    private var dirty = false
    private var frameActivity = SubagentActivity.THINKING
    private var frameTool: String? = null
    private var frameIsMessage = false
    private val buffer = StringBuilder()

    /**
     * 喂入一帧原始事件。
     * @param activity 活动词表值（SubagentActivity.*）。
     * @param delta 原始增量。
     * @param toolName 工具名（工具活动时）。
     * @param isMessage 是否正文帧（决定 UI 的 lastMessage 提取）。
     * @param accumulateDelta 是否把 [delta] 累积进输出视窗：正文与推理帧为 true，
     *   工具调用参数分片为 false（JSON 碎片会污染视窗）。
     */
    fun add(activity: String, delta: String, toolName: String?, isMessage: Boolean, accumulateDelta: Boolean = isMessage) {
        frameActivity = activity
        if (toolName != null) frameTool = toolName
        frameIsMessage = isMessage
        if (accumulateDelta && delta.isNotBlank()) buffer.append(delta)
        dirty = true
    }

    /**
     * 窗口到期时产出一帧；未到期或有内容变化但窗口未满返回 null。
     * 调用方在每个事件处理后调用一次即可。
     */
    fun flushIfDue(): Frame? {
        if (!dirty) return null
        val now = nowMillis()
        if (now - lastFlushAt < throttleMillis) return null
        return flush(now)
    }

    /** 无条件冲刷（尾部兜底）：有未 flush 内容时产出一帧，否则 null。 */
    fun flushNow(): Frame? {
        if (!dirty) return null
        return flush(nowMillis())
    }

    private fun flush(now: Long): Frame {
        val frame = Frame(
            activity = frameActivity,
            delta = buffer.toString(),
            toolName = frameTool,
            isMessage = frameIsMessage
        )
        lastFlushAt = now
        buffer.setLength(0)
        frameTool = null
        frameIsMessage = false
        frameActivity = SubagentActivity.THINKING
        dirty = false
        return frame
    }
}
