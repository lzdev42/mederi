package xyz.mederi.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mederi 内置工具名称枚举。
 *
 * 统一收敛所有工具名称，杜绝散落的硬编码字符串。
 * 每个枚举值的 [value] 对应向底层 Koog 引擎与 LLM 注册的真实工具名。
 */
@Serializable
enum class ToolName(val value: String) {
    // 文件系统工具（FileSystemTools）
    @SerialName("read_file")
    READ_FILE("read_file"),

    @SerialName("write_file")
    WRITE_FILE("write_file"),

    @SerialName("edit_file")
    EDIT_FILE("edit_file"),

    @SerialName("list_directory")
    LIST_DIRECTORY("list_directory"),

    @SerialName("apply_patch")
    APPLY_PATCH("apply_patch"),

    // 终端与 Web 检索工具（ShellTools）
    @SerialName("bash")
    BASH("bash"),

    @SerialName("web_search")
    WEB_SEARCH("web_search"),

    @SerialName("web_fetch")
    WEB_FETCH("web_fetch"),

    // Agent 状态与交互工具（AgentTools）
    @SerialName("update_todo")
    UPDATE_TODO("update_todo"),

    @SerialName("ask_user")
    ASK_USER("ask_user"),

    @SerialName("get_context_remaining")
    GET_CONTEXT_REMAINING("get_context_remaining"),

    @SerialName("new_context")
    NEW_CONTEXT("new_context"),

    // 计划系统工具（PlanTools）
    @SerialName("write_plan")
    WRITE_PLAN("write_plan"),

    @SerialName("generate_spec")
    GENERATE_SPEC("generate_spec"),

    @SerialName("write_log")
    WRITE_LOG("write_log"),

    @SerialName("converge_plan")
    CONVERGE_PLAN("converge_plan"),

    @SerialName("update_verify")
    UPDATE_VERIFY("update_verify"),

    // 计划验证工具（VerifyTools）
    @SerialName("verify_subtask")
    VERIFY_SUBTASK("verify_subtask"),

    // 子代理编排工具（SubagentTool 及内部委派实现）
    @SerialName("subagent")
    SUBAGENT("subagent"),

    @SerialName("spawn_agent")
    SPAWN_AGENT("spawn_agent"),

    @SerialName("spawn_researcher")
    SPAWN_RESEARCHER("spawn_researcher"),

    @SerialName("agent_status")
    AGENT_STATUS("agent_status"),

    @SerialName("stop_agent")
    STOP_AGENT("stop_agent"),

    // 浏览器自动化工具（BrowserTaskTools 及内部委派实现）
    @SerialName("browser")
    BROWSER("browser"),

    @SerialName("run_browser_task")
    RUN_BROWSER_TASK("run_browser_task"),

    @SerialName("browser_task_status")
    BROWSER_TASK_STATUS("browser_task_status"),

    @SerialName("stop_browser_task")
    STOP_BROWSER_TASK("stop_browser_task"),

    @SerialName("browser_info")
    BROWSER_INFO("browser_info"),

    // Office 文档读写工具（OfficeTools）
    @SerialName("office_read")
    OFFICE_READ("office_read"),

    @SerialName("office_write")
    OFFICE_WRITE("office_write"),

    // 进程与后台任务管理（ProcessTools / BackgroundTaskTools）
    @SerialName("list_processes")
    LIST_PROCESSES("list_processes"),

    @SerialName("stop_process")
    STOP_PROCESS("stop_process"),

    @SerialName("set_timer")
    SET_TIMER("set_timer");

    override fun toString(): String = value

    companion object {
        private val byValueMap = entries.associateBy { it.value }

        fun fromValue(value: String): ToolName? = byValueMap[value]
    }
}
