package xyz.mederi.browser.bidi

import xyz.mederi.debug.DebugLog

/**
 * BiDi 层的日志 shim（从 BrowserPilot 的 AILogger 移植时统一替换用）。
 *
 * BiDi 协议层属于 mederi core，统一走 [DebugLog]，layer 固定为 "BiDi"。
 * 只保留 BiDi 层实际用到的四个方法（info / warn / success / error）。
 */
internal object BiDiLog {
    private const val LAYER = "BiDi"

    fun info(msg: String) = DebugLog.info(LAYER, msg)
    fun warn(msg: String) = DebugLog.info(LAYER, "WARN: $msg")
    fun success(msg: String) = DebugLog.info(LAYER, "SUCCESS: $msg")
    fun error(msg: String, detail: String? = null) = DebugLog.error(LAYER, msg + (detail?.let { " | $it" } ?: ""))
}
