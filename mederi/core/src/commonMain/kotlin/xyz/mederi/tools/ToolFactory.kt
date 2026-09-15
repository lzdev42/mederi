package xyz.mederi.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import xyz.mederi.browser.BrowserInfoTool
import xyz.mederi.browser.BrowserTaskService
import xyz.mederi.browser.BrowserTaskStatusTool
import xyz.mederi.browser.RunBrowserTaskTool
import xyz.mederi.browser.StopBrowserTaskTool
import xyz.mederi.office.OfficeTools
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStore
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.SessionStore
import xyz.mederi.tools.diff.TurnDiffTracker
import xyz.mederi.tools.subagent.AgentStatusTool
import xyz.mederi.tools.subagent.SpawnAgentTool
import xyz.mederi.tools.subagent.SpawnResearcherTool
import xyz.mederi.tools.subagent.StopAgentTool
import xyz.mederi.tools.subagent.SubagentManager
import xyz.mederi.tools.subagent.WaitAgentTool
import java.util.concurrent.atomic.AtomicBoolean

object ToolFactory {

    val FS_TOOL_NAMES = listOf("read_file", "write_file", "edit_file", "list_directory", "execute_command", "apply_patch")
    // get_context_remaining / new_context 未开放给 AI（AgentTools 实现保留），恢复时取消注释
    val AGENT_TOOL_NAMES = listOf(
        "update_todo",
        // "get_context_remaining",
        // "new_context",
        "ask_user"
    )
    val PLAN_TOOL_NAMES = listOf("create_plan", "generate_spec", "write_log", "converge_plan")
    val SUBAGENT_TOOL_NAMES = listOf("spawn_agent", "spawn_researcher")
    val SUBAGENT_MGMT_TOOL_NAMES = listOf("agent_status", "stop_agent", "wait_agent")
    val BROWSER_TASK_TOOL_NAMES = listOf("run_browser_task", "browser_task_status", "stop_browser_task", "browser_info")
    val OFFICE_TOOL_NAMES = listOf("office_read", "office_write")
    val VERIFY_TOOL_NAMES = listOf("verify_subtask")
    val PROCESS_TOOL_NAMES = listOf("list_processes", "stop_process")
    val ALL_TOOL_NAMES = FS_TOOL_NAMES + AGENT_TOOL_NAMES + PLAN_TOOL_NAMES + VERIFY_TOOL_NAMES + SUBAGENT_TOOL_NAMES + SUBAGENT_MGMT_TOOL_NAMES + BROWSER_TASK_TOOL_NAMES + OFFICE_TOOL_NAMES + PROCESS_TOOL_NAMES

    fun build(
        toolNames: List<String>,
        directories: List<String>,
        sessionId: String,
        historyStore: HistoryStore,
        eventBus: MutableSharedFlow<MederiEvent>,
        modelContextWindow: Int?,
        newContextWindowFlag: AtomicBoolean,
        diffTracker: TurnDiffTracker? = null,
        subagentManager: SubagentManager? = null,
        browserTaskService: BrowserTaskService? = null,
        aiModel: AIModel? = null,
        reasoningLevel: ReasoningLevel? = null,
        projectId: String? = null,
        questionRequester: xyz.mederi.question.QuestionRequester? = null,
        agentMode: AgentMode = AgentMode.AUTONOMOUS,
        workType: xyz.mederi.domain.model.WorkType? = null,
        subagentRole: SubagentRole? = null,
        planApprovalRequester: PlanApprovalRequester? = null,
        planStore: PlanStore? = null,
        notebook: Notebook? = null,
        commandSandbox: xyz.mederi.tools.sandbox.CommandSandbox? = null,
        sessionStore: SessionStore? = null,
        mcpTools: List<ToolBase<*, *>> = emptyList()
    ): ToolRegistry {
        val fsTools = FileSystemTools(directories, diffTracker)
        val shellTools = ShellTools(directories, commandSandbox)
        val agentTools = AgentTools(sessionId, historyStore, sessionStore, eventBus, modelContextWindow, newContextWindowFlag, questionRequester, planStore)

        // 主代理：全量工具（Triage Flow——是否建计划由 AI 判断，无代码门禁）。子代理按角色裁剪：
        // - EXECUTOR：执行计划内子任务——全量写/执行工具，但无 plan/spawn/verify/ask_user。
        // - RESEARCHER：只读调研——read_file/list_directory 之外一律不给（无写、无命令、无计划工具）。
        val isSubagent = subagentRole != null
        val isResearcher = subagentRole == SubagentRole.RESEARCHER
        val canWrite = !isResearcher
        val canExecute = !isResearcher
        val canPlan = !isSubagent && planStore != null && planApprovalRequester != null && notebook != null
        val canSpawn = !isSubagent && subagentManager != null && aiModel != null && reasoningLevel != null && projectId != null
        val canAskUser = !isSubagent && questionRequester != null
        // todo 工具仅主代理：子代理的进度单是 spec 清单，不养第二份进度
        val canTodo = !isSubagent && sessionStore != null

        // 文件系统工具
        val fsToolMap = mutableMapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
            "read_file" to { fsTools.ReadFileTool() },
            "list_directory" to { fsTools.ListDirectoryTool() }
        )
        if (canWrite) {
            // write_file/edit_file 属于非高危操作（可由 diff 追踪回滚），不触发授权
            fsToolMap["write_file"] = { fsTools.WriteFileTool() }
            fsToolMap["edit_file"] = { fsTools.EditFileTool() }
        }
        if (canExecute) {
            // 非破坏命令自由执行；OS 沙箱（macOS Seatbelt / Linux bwrap）锁写白名单
            fsToolMap["execute_command"] = { shellTools.ExecuteCommandTool() }
            fsToolMap["apply_patch"] = { fsTools.ApplyPatchTool() }
        }

        // 进程管理：只对能执行命令的角色开放（主代理 + EXECUTOR）。
        // 宿主侧按 ProcessRegistry 回收 mederi 自己启动的进程组（沙箱内无法互杀，见 sandbox-plan.md）
        val processTools = ProcessTools()
        val processToolMap = if (canExecute) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "list_processes" to { processTools.ListProcessesTool() },
                "stop_process" to { processTools.StopProcessTool() }
            )
        } else emptyMap()

        val agentToolMap = if (canTodo) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "update_todo" to { agentTools.UpdateTodoTool() }
            )
        } else {
            // 未开放给 AI：AgentTools 实现保留，恢复时取消注释
            // "get_context_remaining" to { agentTools.GetContextRemainingTool() },
            // "new_context" to { agentTools.NewContextWindowTool() },
            emptyMap()
        }

        val askUserToolMap = if (canAskUser) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "ask_user" to { agentTools.AskUserTool() }
            )
        } else emptyMap()

        val planTools = if (canPlan) {
            PlanTools(sessionId, agentMode, workType ?: xyz.mederi.domain.model.WorkType.CODE, planStore!!, planApprovalRequester!!, notebook!!, eventBus)
        } else null
        val planToolMap = if (planTools != null) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "create_plan" to { planTools.CreatePlanTool() },
                "generate_spec" to { planTools.GenerateSpecTool() },
                "write_log" to { planTools.WriteLogTool() },
                "converge_plan" to { planTools.ConvergePlanTool() }
            )
        } else emptyMap()

        val verifyTools = if (canPlan) {
            VerifyTools(sessionId, planStore!!, eventBus, shellTools)
        } else null
        val verifyToolMap = if (verifyTools != null) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "verify_subtask" to { verifyTools.VerifySubtaskTool() }
            )
        } else emptyMap()

        val subagentToolMap = if (canSpawn) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "spawn_agent" to {
                    SpawnAgentTool(
                        subagentManager = subagentManager!!,
                        directories = directories,
                        aiModel = aiModel!!,
                        reasoningLevel = reasoningLevel!!,
                        projectId = projectId!!,
                        parentSessionId = sessionId,
                        planStore = planStore,
                        eventBus = eventBus
                    )
                },
                "spawn_researcher" to {
                    SpawnResearcherTool(
                        subagentManager = subagentManager!!,
                        directories = directories,
                        aiModel = aiModel!!,
                        reasoningLevel = reasoningLevel!!,
                        projectId = projectId!!,
                        parentSessionId = sessionId
                    )
                }
            )
        } else emptyMap()

        // 异步子代理生命周期管理工具：仅主代理（子代理自身不管理别人）
        val subagentMgmtToolMap = if (canSpawn) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "agent_status" to { AgentStatusTool(subagentManager!!) },
                "stop_agent" to { StopAgentTool(subagentManager!!) },
                "wait_agent" to { WaitAgentTool(subagentManager!!) }
            )
        } else emptyMap()

        // 浏览器任务工具：仅主代理（子代理不派发浏览器任务）
        val browserTaskToolMap = if (!isSubagent && browserTaskService != null && aiModel != null && reasoningLevel != null && projectId != null) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "run_browser_task" to {
                    RunBrowserTaskTool(
                        service = browserTaskService!!,
                        aiModel = aiModel!!,
                        reasoningLevel = reasoningLevel!!,
                        projectId = projectId!!,
                        sessionId = sessionId
                    )
                },
                "browser_task_status" to { BrowserTaskStatusTool(browserTaskService!!) },
                "stop_browser_task" to { StopBrowserTaskTool(browserTaskService!!) },
                "browser_info" to { BrowserInfoTool(browserTaskService!!, sessionId) }
            )
        } else emptyMap()

        // Office 文档工具（主代理 + EXECUTOR 可用，RESEARCHER 只读）
        val officeTools = OfficeTools(directories)
        val officeToolMap = if (canWrite) {
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "office_read" to { officeTools.OfficeReadTool() },
                "office_write" to { officeTools.OfficeWriteTool() }
            )
        } else if (!isResearcher) {
            // EXECUTOR 可读不可写？实际上 EXECUTOR canWrite=true。此处保守留空。
            mapOf<String, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                "office_read" to { officeTools.OfficeReadTool() }
            )
        } else {
            emptyMap()
        }

        val allAvailableMaps = fsToolMap + agentToolMap + askUserToolMap + planToolMap + verifyToolMap + subagentToolMap + subagentMgmtToolMap + browserTaskToolMap + officeToolMap + processToolMap
        val requested = if (toolNames.isEmpty()) allAvailableMaps.keys.toList() else toolNames

        val built = ToolRegistry {
            requested.forEach { name ->
                allAvailableMaps[name]?.let { tool(it()) }
            }
        }

        // MCP server 工具（已带 server 名前缀）：旁路合并，不参与 toolNames 裁剪。
        // 无 MCP 工具时直接返回内置 registry，避免多包一层。
        return if (mcpTools.isEmpty()) {
            built
        } else {
            built + ToolRegistry { mcpTools.forEach { tool(it) } }
        }
    }
}
