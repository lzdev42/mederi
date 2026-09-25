package xyz.mederi.debug

/**
 * 统一调试日志工具：总开关 + 分级（DEBUG/INFO/ERROR）+ 调用点位置。
 *
 * 每条日志输出示例：
 * ```
 * [mederi:Frame] DEBUG frame[12] TextDelta: '...' (TurnExecutor.runTurn TurnExecutor.kt:399)
 * [mederi:SSE]   ERROR HTTP POST failed: ... (MederiOpenAILLMClient.execute MederiOpenAILLMClient.kt:88)
 * ```
 * 位置 = 调用点的 类.方法(文件:行号)，定位日志来源不用猜。
 *
 * 控制逻辑（埋点只管按语义选等级，输出与否由这两级控制决定）：
 * 1. [enabled] 总开关：false 时**什么都不输出**（inline + 前置判断，关闭后零 I/O、零堆栈捕获）
 * 2. [minLevel] 最低等级：DEBUG=全量（开发）；INFO=只看业务事件（常规排查）；ERROR=只看错误
 * 3. [layers] 可选 layer 白名单，null = 不过滤，只盯某几层时用
 *
 * 默认值跟随 JVM 属性 `mederi.debug`（缺省开启，开发/测试环境有日志）；
 * 发布入口（desktop main）显式缺省关闭，需要时 `-Dmederi.debug=true` 打开。
 *
 * 埋点约定：
 * - [debug] — 高频/细节路径：流式帧、每事件快照、请求行等
 * - [info] — 业务生命周期：发送/压缩开始结束、工具调用、模型同步等
 * - [error] — 失败与异常
 * - [section]/[data] 是历史语义别名：section≈info、data≈debug，老代码不必改
 */
object DebugLog {

    enum class Level { DEBUG, INFO, ERROR }

    /** 总开关：false 时任何等级都不输出 */
    @Volatile
    var enabled: Boolean = System.getProperty("mederi.debug")?.toBoolean() ?: true

    /** 最低输出等级，低于它的不打印。DEBUG=全量、INFO=业务事件、ERROR=只看错误 */
    @Volatile
    var minLevel: Level = Level.DEBUG

    /** layer 白名单；null = 全部输出 */
    @Volatile
    var layers: Set<String>? = null

    // ==================== 分级埋点 ====================

    inline fun debug(layer: String, message: String) {
        log(Level.DEBUG, layer, message, null)
    }

    inline fun info(layer: String, message: String) {
        log(Level.INFO, layer, message, null)
    }

    inline fun error(layer: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, layer, message, throwable)
    }

    // ==================== 历史语义别名（老调用点无需改动） ====================

    /** ≈ [info]：处理阶段的开始标记 */
    inline fun section(layer: String, name: String) = info(layer, "=== $name ===")

    /** ≈ [debug]：键值对细节 */
    inline fun data(layer: String, key: String, value: Any?) = debug(layer, "$key = $value")

    /** ≈ [info]：事件发生 */
    inline fun event(layer: String, message: String) = info(layer, message)

    // ==================== 输出通道 ====================

    /**
     * inline 展开：等级判断在调用方帧内完成——不满足时直接跳过，
     * 堆栈捕获（[callsite]）和字符串拼接之外的 I/O 都不会发生。
     */
    @PublishedApi
    internal inline fun log(level: Level, layer: String, message: String, throwable: Throwable?) {
        if (!enabled || level < minLevel) return
        val ls = layers
        if (ls != null && layer !in ls) return
        val site = callsite()
        val line = "[mederi:$layer] $level $message${site.formatSite()}"
        if (level == Level.ERROR) {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        } else {
            println(line)
        }
    }

    /** 调用点：log 是 inline，展开后堆栈 [1] 就是真正的调用方代码 */
    @PublishedApi
    internal fun callsite(): StackTraceElement? = Throwable().stackTrace.getOrNull(1)

    @PublishedApi
    internal fun StackTraceElement?.formatSite(): String {
        if (this == null) return ""
        val cls = className.substringAfterLast('.')
        val method = methodName.takeIf { it != cls }?.let { "$cls.$it" } ?: cls
        val file = fileName ?: "<unknown>"
        return " ($method $file:$lineNumber)"
    }
}
