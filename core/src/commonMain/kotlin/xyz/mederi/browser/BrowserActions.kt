package xyz.mederi.browser

import kotlinx.serialization.Serializable

/**
 * 浏览器操作结果（进 step history 与事件 payload）。
 *
 * 浏览器操作不再有独立的文本 JSON action 模型——页面操作经通用工具集
 * [buildBrowserOperatorTools] 装配，原生 Koog tool-calling 是唯一执行路径，
 * 每步的工具执行结果统一收敛为 [ActionResult]。
 */

/** action 执行结果（进 step history；@Serializable 供事件 payload 序列化）。 */
@Serializable
data class ActionResult(
    val action: String,
    val success: Boolean,
    val detail: String = ""
)