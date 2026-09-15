package xyz.mederi.browser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BROWSER agent 的 action 模型。
 *
 * 浏览器操作不走 Koog 的 tool-calling loop——LLM 一次输出所有 action + memory（JSON），
 * BrowserAgentRunner 自己解析执行。这是 BrowserPilot 验证过的模式：每步一次 LLM 调用，
 * prompt 完全重建，页面快照不累积。
 */

/** LLM 决策 JSON（每步一次）。 */
@Serializable
data class BrowserDecision(
    val thought: String = "",
    val memory: String = "",
    val actions: List<BrowserActionArg> = emptyList(),
    @SerialName("is_done")
    val isDone: Boolean = false
)

/** action 参数（松散 JSON，用于容忍模型错形输出）。 */
@Serializable
data class BrowserActionArg(
    val type: String = "",
    val url: String = "",
    val elementRef: String = "",
    val text: String = "",
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val message: String = ""
)

/** 解析后的浏览器 action。 */
sealed class BrowserAction {
    data class Navigate(val url: String) : BrowserAction()
    data class Click(val elementRef: String) : BrowserAction()
    data class Type(val elementRef: String, val text: String) : BrowserAction()
    data class Scroll(val elementRef: String, val deltaX: Int, val deltaY: Int) : BrowserAction()
    data class Done(val message: String) : BrowserAction()
    object NoOp : BrowserAction()
}

/** action 执行结果（进 step history）。 */
data class ActionResult(
    val action: String,
    val success: Boolean,
    val detail: String = ""
)

object BrowserDecisionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * 解析 LLM 输出的决策 JSON。解析失败抛异常（调用方捕获并转为错误步）。
     */
    fun parse(raw: String): BrowserDecision {
        val root = json.parseToJsonElement(raw).jsonObject
        return json.decodeFromJsonElement(BrowserDecision.serializer(), root)
    }

    /** 从决策里提取可执行 action 列表（跳过无法识别的 type，容忍顺序错误）。 */
    fun toActions(decision: BrowserDecision): List<BrowserAction> {
        return decision.actions.mapNotNull { arg ->
            when (arg.type.lowercase()) {
                "navigate" -> if (arg.url.isNotBlank()) BrowserAction.Navigate(arg.url) else null
                "click" -> if (arg.elementRef.isNotBlank()) BrowserAction.Click(arg.elementRef) else null
                "type", "type_text" -> if (arg.elementRef.isNotBlank()) BrowserAction.Type(arg.elementRef, arg.text) else null
                "scroll" -> BrowserAction.Scroll(arg.elementRef, arg.deltaX, arg.deltaY)
                "done", "finish" -> BrowserAction.Done(arg.message.ifBlank { "任务完成" })
                else -> null
            }
        }
    }
}
