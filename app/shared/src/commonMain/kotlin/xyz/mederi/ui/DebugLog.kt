package xyz.mederi.ui

import kotlin.concurrent.Volatile

/**
 * UI 层调试日志工具（commonMain 无法依赖 core 的 DebugLog，此处保持 API 同步的镜像实现）。
 *
 * 与 core 的 `xyz.mederi.debug.DebugLog` 差异：commonMain 拿不到调用点堆栈，
 * 因此无文件/行号位置信息；总开关/等级/layer 过滤语义完全一致。
 *
 * 注意：两处 DebugLog 的 enabled 是独立的——打包发布时需在入口同时关闭
 * （见 desktopApp main.kt）。
 *
 * 埋点约定：
 * - [debug] — 高频/细节路径（流式快照、派生重算等）
 * - [info] — 业务生命周期（attach/send/审批流转等）
 * - [error] — 失败与异常
 * - [section]/[data] 为历史语义别名（section≈info、data≈debug）
 */
object DebugLog {

    enum class Level { DEBUG, INFO, ERROR }

    /** 总开关：false 时任何等级都不输出 */
    @Volatile
    var enabled: Boolean = true

    /** 最低输出等级，低于它的不打印 */
    @Volatile
    var minLevel: Level = Level.DEBUG

    /** layer 白名单；null = 全部输出 */
    @Volatile
    var layers: Set<String>? = null

    fun debug(layer: String, message: String) {
        log(Level.DEBUG, layer, message, null)
    }

    fun info(layer: String, message: String) {
        log(Level.INFO, layer, message, null)
    }

    fun error(layer: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, layer, message, throwable)
    }

    // ==================== 历史语义别名（老调用点无需改动） ====================

    fun section(layer: String, name: String) = info(layer, "=== $name ===")

    fun data(layer: String, key: String, value: Any?) = debug(layer, "$key = $value")

    fun event(layer: String, message: String) = info(layer, message)

    internal fun log(level: Level, layer: String, message: String, throwable: Throwable?) {
        if (!enabled || level < minLevel) return
        val ls = layers
        if (ls != null && layer !in ls) return
        val line = "[mederi:$layer] $level $message"
        if (level == Level.ERROR) {
            printError(line, throwable)
        } else {
            printLine(line)
        }
    }
}

/**
 * 错误输出通道（stderr 语义）。平台化：JVM/Android 走 System.err，
 * JS/wasmJs 走 println（stdlib 直接映射 console）。
 * 公开可见性：被 @PublishedApi inline 的 [DebugLog.log] 调用。
 */
expect fun printError(line: String, throwable: Throwable?)

/** 标准输出通道（stdout 语义）。 */
expect fun printLine(line: String)
