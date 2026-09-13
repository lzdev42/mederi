package xyz.mederi.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.contract.models.WorkType
import xyz.mederi.core.ui.appstate.AppState

data class SidebarUiState(
    val expandedProjectIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null,
)

class SidebarViewModel(
    private val appState: AppState,
) : ViewModel() {

    var uiState by mutableStateOf(SidebarUiState()); private set

    /** 当前工作用途（透传 AppState 派生流） */
    val selectedWorkType: StateFlow<WorkType> get() = appState.selectedWorkType

    /**
     * 按当前工作用途过滤的项目/会话树：
     * 只保留没有会话的项目，或包含匹配 workType 会话的项目（且只展示匹配的会话）。
     */
    val filteredProjects: StateFlow<List<Project>> =
        combine(appState.projects, appState.selectedWorkType) { projects, workType ->
            projects.mapNotNull { project ->
                val matching = project.conversations.filter { it.workType == workType }
                if (project.conversations.isEmpty() || matching.isNotEmpty()) {
                    project.copy(conversations = matching)
                } else {
                    null
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 确保会话所属的项目在侧边栏展开（覆盖程序化选择：自动创建会话、子代理跳转等） */
    fun ensureConversationVisible(conversationId: String) {
        val project = appState.projects.value.firstOrNull { p ->
            p.conversations.any { it.id == conversationId }
        } ?: return
        if (project.id !in uiState.expandedProjectIds) {
            uiState = uiState.copy(expandedProjectIds = uiState.expandedProjectIds + project.id)
        }
    }

    fun createProject(input: CreateProjectInput) = launch {
        appState.aiCore.createProject(input)
    }

    fun createProjectFromDirectory(dir: String) = launch {
        val name = dir.substringAfterLast("/").substringAfterLast("\\").ifBlank { dir }
        appState.aiCore.createProject(CreateProjectInput(name = name, directory = dir))
    }

    fun renameProject(projectId: String, newName: String) = launch {
        appState.aiCore.renameProject(projectId, newName)
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            appState.aiCore.deleteProject(projectId)
                .onSuccess { appState.handleProjectDeleted(projectId) }
                .onFailure { uiState = uiState.copy(error = it.message) }
            uiState = uiState.copy(isBusy = false)
        }
    }

    fun createConversation(projectId: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val agent = appState.availableAgents.value.find { it.id == appState.selectedAgentId.value }
            val result = appState.aiCore.createConversation(projectId, agent)
            uiState = uiState.copy(isBusy = false)
            result.fold(
                onSuccess = { conv ->
                    // 自动展开项目，使新创建的对话在侧边栏可见
                    uiState = uiState.copy(expandedProjectIds = uiState.expandedProjectIds + projectId)
                    appState.selectProject(projectId)
                    appState.selectConversation(conv.id)
                },
                onFailure = { uiState = uiState.copy(error = it.message) },
            )
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            // 与 deleteProject 相同的事务顺序：先执行删除，成功后才清理选中态——
            // 反过来会话删除失败时选中态已被清掉，且错误信息丢失
            appState.aiCore.deleteConversation(conversationId)
                .onSuccess {
                    DebugLog.info("UI", "deleteConversation: id=$conversationId")
                    appState.handleConversationDeleted(conversationId)
                }
                .onFailure { uiState = uiState.copy(error = it.message) }
            uiState = uiState.copy(isBusy = false)
        }
    }

    fun renameConversation(conversationId: String, title: String) = launch {
        DebugLog.info("UI", "renameConversation: id=$conversationId, title='$title'")
        appState.aiCore.renameConversation(conversationId, title)
    }

    fun toggleProjectExpanded(projectId: String) {
        val cur = uiState.expandedProjectIds
        uiState = uiState.copy(
            expandedProjectIds = if (projectId in cur) cur - projectId else cur + projectId
        )
    }

    fun selectProject(id: String?) {
        appState.selectProject(id)
    }

    fun selectConversation(id: String?) {
        appState.selectConversation(id)
    }

    fun selectWorkType(workType: WorkType) {
        appState.selectWorkType(workType)
    }

    fun newSession() {
        appState.selectConversation(null)
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    private inline fun launch(crossinline block: suspend () -> Result<*>) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            val r: Result<*> = try { block() } catch (e: Exception) { Result.failure<Unit>(e) }
            uiState = uiState.copy(isBusy = false)
            r.onFailure { uiState = uiState.copy(error = it.message) }
        }
    }
}
