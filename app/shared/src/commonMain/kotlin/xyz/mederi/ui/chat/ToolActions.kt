package xyz.mederi.ui.chat

import mederi.app.shared.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.expandBatchToolCall
import mederi.app.shared.generated.resources.tool_action_web_search
import mederi.app.shared.generated.resources.tool_action_web_search_running
import mederi.app.shared.generated.resources.tool_action_web_fetch
import mederi.app.shared.generated.resources.tool_action_web_fetch_running
import mederi.app.shared.generated.resources.tool_action_load_skill
import mederi.app.shared.generated.resources.tool_action_load_skill_running
import mederi.app.shared.generated.resources.tool_action_unload_skill
import mederi.app.shared.generated.resources.tool_action_unload_skill_running
import mederi.app.shared.generated.resources.tool_action_context_remaining
import mederi.app.shared.generated.resources.tool_action_context_remaining_running
import mederi.app.shared.generated.resources.tool_action_new_context
import mederi.app.shared.generated.resources.tool_action_new_context_running
import mederi.app.shared.generated.resources.tool_action_plan_draft
import mederi.app.shared.generated.resources.tool_action_plan_draft_running
import mederi.app.shared.generated.resources.tool_action_plan_draft_running_target
import mederi.app.shared.generated.resources.tool_action_plan_draft_target
import mederi.app.shared.generated.resources.tool_action_plan_update
import mederi.app.shared.generated.resources.tool_action_plan_update_running
import mederi.app.shared.generated.resources.tool_action_plan_update_running_target
import mederi.app.shared.generated.resources.tool_action_plan_update_target
import mederi.app.shared.generated.resources.tool_action_generate_spec
import mederi.app.shared.generated.resources.tool_action_generate_spec_running
import mederi.app.shared.generated.resources.tool_action_generate_spec_running_target
import mederi.app.shared.generated.resources.tool_action_generate_spec_target
import mederi.app.shared.generated.resources.tool_action_write_log
import mederi.app.shared.generated.resources.tool_action_write_log_running
import mederi.app.shared.generated.resources.tool_action_converge_plan
import mederi.app.shared.generated.resources.tool_action_converge_plan_running
import mederi.app.shared.generated.resources.tool_action_update_verify
import mederi.app.shared.generated.resources.tool_action_update_verify_running
import mederi.app.shared.generated.resources.tool_action_update_verify_running_target
import mederi.app.shared.generated.resources.tool_action_update_verify_target
import mederi.app.shared.generated.resources.tool_action_subagent_status
import mederi.app.shared.generated.resources.tool_action_subagent_status_running
import mederi.app.shared.generated.resources.tool_action_subagent_stop
import mederi.app.shared.generated.resources.tool_action_subagent_stop_running
import mederi.app.shared.generated.resources.tool_action_browser_run
import mederi.app.shared.generated.resources.tool_action_browser_run_running
import mederi.app.shared.generated.resources.tool_action_browser_status
import mederi.app.shared.generated.resources.tool_action_browser_status_running
import mederi.app.shared.generated.resources.tool_action_browser_stop
import mederi.app.shared.generated.resources.tool_action_browser_stop_running
import mederi.app.shared.generated.resources.tool_action_browser_info
import mederi.app.shared.generated.resources.tool_action_browser_info_running
import mederi.app.shared.generated.resources.tool_action_list_processes
import mederi.app.shared.generated.resources.tool_action_list_processes_running
import mederi.app.shared.generated.resources.tool_action_stop_process
import mederi.app.shared.generated.resources.tool_action_stop_process_running
import mederi.app.shared.generated.resources.tool_action_set_timer
import mederi.app.shared.generated.resources.tool_action_set_timer_running
import mederi.app.shared.generated.resources.tool_action_subagent_research
import mederi.app.shared.generated.resources.tool_action_subagent_research_running

/** 工具时间线的动作分类与本地化文案。 */
enum class ToolActionKind(
    internal val completedLabel: StringResource? = null,
    internal val runningLabel: StringResource? = null,
    internal val completedTargetLabel: StringResource? = null,
    internal val runningTargetLabel: StringResource? = null,
) {
    COMMAND, READ, EDIT, SEARCH, LIST, SUBAGENT, MCP, ASK, TODO, VERIFY, OTHER,
    WEB_SEARCH(Res.string.tool_action_web_search, Res.string.tool_action_web_search_running),
    WEB_FETCH(Res.string.tool_action_web_fetch, Res.string.tool_action_web_fetch_running),
    LOAD_SKILL(Res.string.tool_action_load_skill, Res.string.tool_action_load_skill_running),
    UNLOAD_SKILL(Res.string.tool_action_unload_skill, Res.string.tool_action_unload_skill_running),
    CONTEXT_REMAINING(Res.string.tool_action_context_remaining, Res.string.tool_action_context_remaining_running),
    NEW_CONTEXT(Res.string.tool_action_new_context, Res.string.tool_action_new_context_running),
    PLAN_DRAFT(
        Res.string.tool_action_plan_draft, Res.string.tool_action_plan_draft_running,
        Res.string.tool_action_plan_draft_target, Res.string.tool_action_plan_draft_running_target,
    ),
    PLAN_UPDATE(
        Res.string.tool_action_plan_update, Res.string.tool_action_plan_update_running,
        Res.string.tool_action_plan_update_target, Res.string.tool_action_plan_update_running_target,
    ),
    GENERATE_SPEC(
        Res.string.tool_action_generate_spec, Res.string.tool_action_generate_spec_running,
        Res.string.tool_action_generate_spec_target, Res.string.tool_action_generate_spec_running_target,
    ),
    WRITE_LOG(Res.string.tool_action_write_log, Res.string.tool_action_write_log_running),
    CONVERGE_PLAN(Res.string.tool_action_converge_plan, Res.string.tool_action_converge_plan_running),
    UPDATE_VERIFY(
        Res.string.tool_action_update_verify, Res.string.tool_action_update_verify_running,
        Res.string.tool_action_update_verify_target, Res.string.tool_action_update_verify_running_target,
    ),
    SUBAGENT_STATUS(Res.string.tool_action_subagent_status, Res.string.tool_action_subagent_status_running),
    SUBAGENT_STOP(Res.string.tool_action_subagent_stop, Res.string.tool_action_subagent_stop_running),
    BROWSER_RUN(Res.string.tool_action_browser_run, Res.string.tool_action_browser_run_running),
    BROWSER_STATUS(Res.string.tool_action_browser_status, Res.string.tool_action_browser_status_running),
    BROWSER_STOP(Res.string.tool_action_browser_stop, Res.string.tool_action_browser_stop_running),
    BROWSER_INFO(Res.string.tool_action_browser_info, Res.string.tool_action_browser_info_running),
    LIST_PROCESSES(Res.string.tool_action_list_processes, Res.string.tool_action_list_processes_running),
    STOP_PROCESS(Res.string.tool_action_stop_process, Res.string.tool_action_stop_process_running),
    SET_TIMER(Res.string.tool_action_set_timer, Res.string.tool_action_set_timer_running),
    SUBAGENT_RESEARCH(Res.string.tool_action_subagent_research, Res.string.tool_action_subagent_research_running),
}

/** 对内置工具及已知历史别名按实际动作分类。 */
fun classifyToolAction(name: String): ToolActionKind {
    return when (val normalized = name.lowercase()) {
        "bash", "run_command", "exec_command" -> ToolActionKind.COMMAND
        "read_file", "view_file", "cat", "office_read" -> ToolActionKind.READ
        "write_file", "edit_file", "apply_patch", "replace_file", "office_write" -> ToolActionKind.EDIT
        "grep", "grep_search", "search_code", "find_files" -> ToolActionKind.SEARCH
        "list_directory", "list_dir", "tree" -> ToolActionKind.LIST
        "subagent", "spawn_agent" -> ToolActionKind.SUBAGENT
        "spawn_researcher" -> ToolActionKind.SUBAGENT_RESEARCH
        "agent_status" -> ToolActionKind.SUBAGENT_STATUS
        "stop_agent" -> ToolActionKind.SUBAGENT_STOP
        "ask", "ask_user", "ask_question" -> ToolActionKind.ASK
        "verify_subtask" -> ToolActionKind.VERIFY
        "update_todo", "session_todo" -> ToolActionKind.TODO
        "web_search" -> ToolActionKind.WEB_SEARCH
        "web_fetch" -> ToolActionKind.WEB_FETCH
        "load_skill" -> ToolActionKind.LOAD_SKILL
        "unload_skill" -> ToolActionKind.UNLOAD_SKILL
        "get_context_remaining" -> ToolActionKind.CONTEXT_REMAINING
        "new_context" -> ToolActionKind.NEW_CONTEXT
        "write_plan", "create_plan" -> ToolActionKind.PLAN_DRAFT
        "generate_spec" -> ToolActionKind.GENERATE_SPEC
        "write_log" -> ToolActionKind.WRITE_LOG
        "converge_plan" -> ToolActionKind.CONVERGE_PLAN
        "update_verify", "update_verification" -> ToolActionKind.UPDATE_VERIFY
        "run_browser_task" -> ToolActionKind.BROWSER_RUN
        "browser_task_status" -> ToolActionKind.BROWSER_STATUS
        "stop_browser_task" -> ToolActionKind.BROWSER_STOP
        "browser_info" -> ToolActionKind.BROWSER_INFO
        "list_processes" -> ToolActionKind.LIST_PROCESSES
        "stop_process" -> ToolActionKind.STOP_PROCESS
        "set_timer" -> ToolActionKind.SET_TIMER
        else -> if (normalized.startsWith("mcp_")) ToolActionKind.MCP else ToolActionKind.OTHER
    }
}

internal fun classifyToolAction(call: ToolCallUi): ToolActionKind {
    val input = when (val state = call.state) {
        is ToolCallState.Pending -> state.input
        is ToolCallState.Running -> state.input
        is ToolCallState.Completed -> state.input
        is ToolCallState.Failed -> state.input
    }
    return when (call.name.lowercase()) {
        "subagent" -> when (input["action"]) {
            "SPAWN" -> ToolActionKind.SUBAGENT
            "SPAWN_RESEARCHER" -> ToolActionKind.SUBAGENT_RESEARCH
            "STATUS" -> ToolActionKind.SUBAGENT_STATUS
            "STOP" -> ToolActionKind.SUBAGENT_STOP
            else -> ToolActionKind.OTHER
        }
        "browser" -> when (input["action"]) {
            "RUN" -> ToolActionKind.BROWSER_RUN
            "STATUS" -> ToolActionKind.BROWSER_STATUS
            "STOP" -> ToolActionKind.BROWSER_STOP
            "INFO" -> ToolActionKind.BROWSER_INFO
            else -> ToolActionKind.OTHER
        }
        "write_plan" -> if (input["planId"].isNullOrBlank()) ToolActionKind.PLAN_DRAFT else ToolActionKind.PLAN_UPDATE
        else -> classifyToolAction(call.name)
    }
}

/**
 * 连续同类工具动作聚合组。
 */
data class ToolActionGroup(
    val kind: ToolActionKind,
    val calls: List<ToolCallUi>,
)

/**
 * 将工具调用列表转换为单项动作组（严格保持时序不汇总，如实展示每个工具调用）。
 */
fun groupToolCallsByAction(calls: List<ToolCallUi>): List<ToolActionGroup> {
    return calls.flatMap { expandBatchToolCall(it) }.map { call ->
        ToolActionGroup(classifyToolAction(call), listOf(call))
    }
}
