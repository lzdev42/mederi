package xyz.mederi.browser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    val message: String = "",
    val content: String = "",
    val instruction: String = "",
    /** execute_drill：skill ID 字符串或 inline DrillScript JSON 对象。 */
    val script: JsonElement? = null,
    /** wait_for：等待出现/可见的 CSS/文本选择器。 */
    val selector: String = "",
    /** tabs：NEW / CLOSE / LIST / SELECT。 */
    val tabAction: String = "",
    /** tabs：目标标签页 id（CLOSE / SELECT 用）。 */
    val tabId: String = "",
    /** sleep：休眠毫秒数。 */
    val sleepMs: Long = 0
)

/** 解析后的浏览器 action。 */
sealed class BrowserAction {
    data class Navigate(val url: String) : BrowserAction()
    data class Click(val elementRef: String) : BrowserAction()
    data class Type(val elementRef: String, val text: String) : BrowserAction()
    data class Scroll(val elementRef: String, val deltaX: Int, val deltaY: Int) : BrowserAction()
    data class Done(val message: String) : BrowserAction()
    data class Judge(val content: String, val instruction: String) : BrowserAction()
    /** execute_drill：执行确定性批量脚本（script 为 skill ID 字符串或 DrillScript JSON）。 */
    data class ExecuteDrill(val script: JsonElement) : BrowserAction()
    /** wait_for：等待选择器对应元素出现/可见。 */
    data class WaitFor(val selector: String) : BrowserAction()
    /** tabs：标签页管理（NEW/CLOSE/LIST/SELECT）。 */
    data class Tabs(val action: String, val tabId: String = "") : BrowserAction()
    /** screenshot：截图（供视觉模型/留档）。 */
    object Screenshot : BrowserAction()
    /** navigate_back：返回上一页（当前后端无 navigateBack 时 noop）。 */
    object NavigateBack : BrowserAction()
    /** close：关闭浏览器。 */
    object Close : BrowserAction()
    /** sleep：休眠指定毫秒数。 */
    data class Sleep(val ms: Long) : BrowserAction()
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
                "judge", "ask_brain", "ask_ai" -> {
                    val content = arg.content.ifBlank { arg.text }
                    val instruction = arg.instruction.ifBlank { arg.message }
                    if (content.isNotBlank() || instruction.isNotBlank()) {
                        BrowserAction.Judge(content, instruction)
                    } else null
                }
                "execute_drill", "drill" -> arg.script?.let { BrowserAction.ExecuteDrill(it) }
                "wait_for" -> if (arg.selector.isNotBlank()) BrowserAction.WaitFor(arg.selector) else null
                "tabs", "browser_tabs" -> if (arg.tabAction.isNotBlank()) BrowserAction.Tabs(arg.tabAction, arg.tabId) else null
                "screenshot" -> BrowserAction.Screenshot
                "navigate_back", "back" -> BrowserAction.NavigateBack
                "close" -> BrowserAction.Close
                "sleep" -> BrowserAction.Sleep(arg.sleepMs)
                else -> null
            }
        }
    }
}
