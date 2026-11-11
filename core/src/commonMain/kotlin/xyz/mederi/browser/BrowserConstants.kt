package xyz.mederi.browser

/**
 * 浏览器自动化模块常量收敛（杜绝魔法数字与魔法字符串）。
 */
object BrowserConstants {
    const val DEFAULT_MAX_STEPS = 50
    const val MAX_HISTORY_ITEMS = 10
    const val MAX_TOOL_RESULT_ROUNDS = 10
    const val DEFAULT_VIEWPORT_WIDTH = 1280
    const val DEFAULT_VIEWPORT_HEIGHT = 720

    const val DEFAULT_BROWSER_OBSCURA = "obscura"
    const val DEFAULT_SESSION_ID = "__default__"

    object ToolNames {
        const val NAVIGATE = "navigate"
        const val CLICK = "click"
        const val TYPE = "type"
        const val SCROLL = "scroll"
        const val WAIT_FOR = "wait_for"
        const val TABS = "tabs"
        const val DONE = "done"
        const val JUDGE = "judge"
        const val DRILL = "drill"
        const val UPDATE_MEMORY = "update_memory"
        const val COLLECT_INFO = "collect_info"
    }

    object ProtocolTags {
        const val AGENT_HISTORY = "agent_history"
        const val AGENT_STATE = "agent_state"
        const val USER_REQUEST = "user_request"
        const val MEMORY = "memory"
        const val STEP_INFO = "step_info"
        const val LAST_ACTION_RESULTS = "last_action_results"
        const val BROWSER_STATE = "browser_state"
        const val ACTIVE_TAB_SNAPSHOT = "active_tab_snapshot"
        const val TABS = "tabs"
        const val URL = "url"
        const val TITLE = "title"
        const val ERROR = "error"
    }

    object EventPayloadKeys {
        const val TASK_ID = "taskId"
        const val STATUS = "status"
        const val BROWSER = "browser"
        const val STEP = "step"
        const val THOUGHT = "thought"
        const val MEMORY = "memory"
        const val RESULTS = "results"
        const val RESULTS_JSON = "resultsJson"
        const val MESSAGE = "message"
    }
}
