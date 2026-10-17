package xyz.mederi.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import xyz.mederi.browser.BrowserInfoTool
import xyz.mederi.browser.BrowserTaskService
import xyz.mederi.browser.BrowserTaskStatusTool
import xyz.mederi.browser.BrowserTool
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
import xyz.mederi.tools.subagent.SpawnAgentTool
import xyz.mederi.tools.subagent.SpawnResearcherTool
import xyz.mederi.tools.subagent.SubagentConfigManager
import xyz.mederi.tools.subagent.SubagentManager
import xyz.mederi.tools.subagent.SubagentTool
import java.util.concurrent.atomic.AtomicBoolean

object ToolFactory {

    // apply_patch 不注册给 AI（2026-09 裁定：模型实际不用，单文件场景 edit_file + replace_all 全覆盖）；
    // 实现保留在 FileSystemTools.ApplyPatchTool，恢复时在此列表与下方注册表各加回一行
    val FS_TOOL_NAMES: List<ToolName> = listOf(
        ToolName.READ_FILE,
        ToolName.WRITE_FILE,
        ToolName.EDIT_FILE,
        ToolName.LIST_DIRECTORY,
        ToolName.BASH
    )
    // get_context_remaining / new_context 未开放给 AI（AgentTools 实现保留），恢复时取消注释
    val AGENT_TOOL_NAMES: List<ToolName> = listOf(
        ToolName.UPDATE_TODO,
        // ToolName.GET_CONTEXT_REMAINING,
        // ToolName.NEW_CONTEXT,
        ToolName.ASK_USER
    )
    val PLAN_TOOL_NAMES: List<ToolName> = listOf(
        ToolName.WRITE_PLAN,
        ToolName.GENERATE_SPEC,
        ToolName.WRITE_LOG,
        ToolName.CONVERGE_PLAN,
        ToolName.UPDATE_VERIFY
    )
    // subagent 单一入口：封装原 spawn_agent/spawn_researcher/agent_status/stop_agent/wait_agent（action 分流）
    val SUBAGENT_TOOL_NAMES: List<ToolName> = listOf(ToolName.SUBAGENT)
    // browser 单一入口：封装原 run_browser_task/browser_task_status/stop_browser_task/browser_info（action 分流）
    val BROWSER_TASK_TOOL_NAMES: List<ToolName> = listOf(ToolName.BROWSER)
    val OFFICE_TOOL_NAMES: List<ToolName> = listOf(ToolName.OFFICE_READ, ToolName.OFFICE_WRITE)
    val VERIFY_TOOL_NAMES: List<ToolName> = listOf(ToolName.VERIFY_SUBTASK)
    val PROCESS_TOOL_NAMES: List<ToolName> = listOf(ToolName.LIST_PROCESSES, ToolName.STOP_PROCESS, ToolName.SET_TIMER)
    // web_search/web_fetch：主代理 + 研究员子代理可用（Bing 搜索 + URL 抓取，curl GET 只读）
    val WEB_TOOL_NAMES: List<ToolName> = listOf(ToolName.WEB_SEARCH, ToolName.WEB_FETCH)
    val ALL_TOOL_NAMES: List<ToolName> = FS_TOOL_NAMES + AGENT_TOOL_NAMES + PLAN_TOOL_NAMES + VERIFY_TOOL_NAMES + SUBAGENT_TOOL_NAMES + BROWSER_TASK_TOOL_NAMES + OFFICE_TOOL_NAMES + PROCESS_TOOL_NAMES + WEB_TOOL_NAMES

    fun build(
        toolNames: List<ToolName> = ALL_TOOL_NAMES,
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
        subagentRole: SubagentRole? = null,
        planApprovalRequester: PlanApprovalRequester? = null,
        planStore: PlanStore? = null,
        notebook: Notebook? = null,
        commandSandbox: xyz.mederi.tools.sandbox.CommandSandbox? = null,
        sessionStore: SessionStore? = null,
        mcpTools: List<ToolBase<*, *>> = emptyList(),
        agentsDiscovery: AgentsSubtreeDiscovery? = null,
        apiKeyId: String? = null,
        /** 执行器子代理的文件写入回调（SubagentRunnerImpl 挂，收集 touched files）。 */
        onFileTouched: ((String) -> Unit)? = null,
        subagentConfigManager: SubagentConfigManager? = null,
        /** 自定义工具（CustomToolRegistry.buildAll() 收集）：旁路合并，不参与 toolNames 裁剪。 */
        customTools: List<ToolBase<*, *>> = emptyList(),
        backgroundTaskManager: xyz.mederi.tools.background.BackgroundTaskManager? = null,
    ): ToolRegistry {
        val fsTools = FileSystemTools(directories, diffTracker, agentsDiscovery, onFileTouched)
        val shellTools = ShellTools(directories, commandSandbox, backgroundTaskManager, sessionId)
        val agentTools = AgentTools(sessionId, historyStore, sessionStore, eventBus, modelContextWindow, newContextWindowFlag, questionRequester)

        // 主代理：全量工具（Triage Flow——是否建计划由 AI 判断，无代码门禁）。子代理按角色裁剪：
        // - EXECUTOR：执行计划内子任务——全量写/执行工具，但无 plan/spawn/verify/ask_user。
        // - RESEARCHER：只读调研——read_file/list_directory + web_search/web_fetch（无写、无命令、无计划工具）。
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
        val fsToolMap = mutableMapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
            ToolName.READ_FILE to { fsTools.ReadFileTool() },
            ToolName.LIST_DIRECTORY to { fsTools.ListDirectoryTool() }
        )
        if (canWrite) {
            // write_file/edit_file 属于非高危操作（可由 diff 追踪回滚），不触发授权
            fsToolMap[ToolName.WRITE_FILE] = { fsTools.WriteFileTool() }
            fsToolMap[ToolName.EDIT_FILE] = { fsTools.EditFileTool() }
        }
        if (canExecute) {
            // 非破坏命令自由执行；OS 沙箱（macOS Seatbelt / Linux bwrap）锁写白名单
            fsToolMap[ToolName.BASH] = { shellTools.BashTool() }
        }
        // web_search / web_fetch：主代理 + 研究员子代理（curl GET 只读网络搜索/抓取，复用 ShellTools.runCommand）
        if (isResearcher || !isSubagent) {
            fsToolMap[ToolName.WEB_SEARCH] = { shellTools.WebSearchTool() }
            fsToolMap[ToolName.WEB_FETCH] = { shellTools.WebFetchTool() }
        }

        // 进程管理：只对能执行命令的角色开放（主代理 + EXECUTOR）。
        // 宿主侧按 ProcessRegistry 回收 mederi 自己启动的进程组（沙箱内无法互杀，见 sandbox-plan.md）
        val processTools = ProcessTools()
        val processToolMap = if (canExecute) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.LIST_PROCESSES to { processTools.ListProcessesTool() },
                ToolName.STOP_PROCESS to { processTools.StopProcessTool() }
            )
        } else emptyMap()

        // 后台任务工具：set_timer（依赖 BackgroundTaskManager，未注入时不注册）
        val backgroundTaskTools = backgroundTaskManager?.let { xyz.mederi.tools.background.BackgroundTaskTools(it, sessionId) }
        val backgroundToolMap = if (canExecute && backgroundTaskTools != null) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.SET_TIMER to { backgroundTaskTools.SetTimerTool() }
            )
        } else emptyMap()

        val agentToolMap = if (canTodo) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.UPDATE_TODO to { agentTools.UpdateTodoTool() }
            )
        } else {
            // 未开放给 AI：AgentTools 实现保留，恢复时取消注释
            // ToolName.GET_CONTEXT_REMAINING to { agentTools.GetContextRemainingTool() },
            // ToolName.NEW_CONTEXT to { agentTools.NewContextWindowTool() },
            emptyMap()
        }

        val askUserToolMap = if (canAskUser) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.ASK_USER to { agentTools.AskUserTool() }
            )
        } else emptyMap()

        val planTools = if (canPlan) {
            PlanTools(sessionId, agentMode, planStore!!, planApprovalRequester!!, notebook!!, eventBus)
        } else null
        val planToolMap = if (planTools != null) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.WRITE_PLAN to { planTools.WritePlanTool() },
                ToolName.GENERATE_SPEC to { planTools.GenerateSpecTool() },
                ToolName.WRITE_LOG to { planTools.WriteLogTool() },
                ToolName.CONVERGE_PLAN to { planTools.ConvergePlanTool() },
                ToolName.UPDATE_VERIFY to { planTools.UpdateVerificationTool() }
            )
        } else emptyMap()

        val verifyTools = if (canPlan) {
            VerifyTools(sessionId, planStore!!, eventBus, shellTools)
        } else null
        val verifyToolMap = if (verifyTools != null) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.VERIFY_SUBTASK to { verifyTools.VerifySubtaskTool() }
            )
        } else emptyMap()

        val subagentToolMap = if (canSpawn) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.SUBAGENT to {
                    val mgr = subagentManager!!
                    SubagentTool(
                        spawnExecutor = SpawnAgentTool(
                            mgr, directories, aiModel!!, reasoningLevel!!, projectId!!,
                            sessionId, planStore, eventBus, apiKeyId, sessionStore,
                            subagentConfigManager = subagentConfigManager
                        ),
                        spawnResearcher = SpawnResearcherTool(
                            mgr, directories, aiModel!!, reasoningLevel!!, projectId!!,
                            sessionId, apiKeyId, sessionStore,
                            subagentConfigManager = subagentConfigManager,
                            planStore = planStore
                        ),
                        manager = mgr,
                        parentSessionId = sessionId
                    )
                }
            )
        } else emptyMap()

        // 浏览器任务工具：仅主代理（子代理不派发浏览器任务），4→1 单一入口（action 分流）
        val browserTaskToolMap = if (!isSubagent && browserTaskService != null && aiModel != null && reasoningLevel != null && projectId != null) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.BROWSER to {
                    val svc = browserTaskService!!
                    BrowserTool(
                        run = RunBrowserTaskTool(
                            service = svc,
                            aiModel = aiModel!!,
                            reasoningLevel = reasoningLevel!!,
                            projectId = projectId!!,
                            sessionId = sessionId,
                            apiKeyId = apiKeyId
                        ),
                        status = BrowserTaskStatusTool(svc),
                        stop = StopBrowserTaskTool(svc),
                        info = BrowserInfoTool(svc, sessionId),
                        service = svc
                    )
                }
            )
        } else emptyMap()

        // Office 文档工具（主代理 + EXECUTOR 可用，RESEARCHER 只读）
        val officeTools = OfficeTools(directories)
        val officeToolMap = if (canWrite) {
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.OFFICE_READ to { officeTools.OfficeReadTool() },
                ToolName.OFFICE_WRITE to { officeTools.OfficeWriteTool() }
            )
        } else if (!isResearcher) {
            // EXECUTOR 可读不可写？实际上 EXECUTOR canWrite=true。此处保守留空。
            mapOf<ToolName, () -> ai.koog.agents.core.tools.ToolBase<*, *>>(
                ToolName.OFFICE_READ to { officeTools.OfficeReadTool() }
            )
        } else {
            emptyMap()
        }

        val allAvailableMaps = fsToolMap + agentToolMap + askUserToolMap + planToolMap + verifyToolMap + subagentToolMap + browserTaskToolMap + officeToolMap + processToolMap + backgroundToolMap
        val requested = if (toolNames.isEmpty()) allAvailableMaps.keys.toList() else toolNames

        val built = ToolRegistry {
            requested.forEach { toolName ->
                allAvailableMaps[toolName]?.let { tool(it()) }
            }
        }

        // MCP server 工具（已带 server 名前缀）与自定义工具（CustomToolRegistry）：旁路合并，
        // 不参与 toolNames 裁剪。两者皆空时直接返回内置 registry，避免多包一层。
        val externalTools = customTools + mcpTools
        return if (externalTools.isEmpty()) {
            built
        } else {
            built + ToolRegistry { externalTools.forEach { tool(it) } }
        }
    }
}
