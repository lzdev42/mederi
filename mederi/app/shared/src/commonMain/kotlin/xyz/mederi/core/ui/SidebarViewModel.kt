package xyz.mederi.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.dto.CreateProjectInput
import xyz.mederi.core.contract.models.Project
import xyz.mederi.core.ui.appstate.AppState
import xyz.mederi.theme.AppLanguage
import xyz.mederi.theme.AppThemeMode

data class SidebarUiState(
    val expandedProjectIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val error: String? = null,
)

class SidebarViewModel(
    private val appState: AppState,
) : ViewModel() {

    var uiState by mutableStateOf(SidebarUiState()); private set

    /**
     * 侧边栏项目/会话树（全部展示，按项目创建时间倒序）。
     */
    val filteredProjects: StateFlow<List<Project>> =
        appState.projects.map { projects ->
            projects.sortedByDescending { it.createdAt }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 主题模式窄状态（转发 AppState 全局真理源，组件不直操全局单例） */
    val theme: StateFlow<AppThemeMode> = appState.theme

    /** 语言窄状态（转发 AppState 全局真理源，组件不直操全局单例） */
    val language: StateFlow<AppLanguage> = appState.language

    fun setTheme(mode: AppThemeMode) {
        appState.setTheme(mode)
    }

    fun setLanguage(lang: AppLanguage) {
        appState.setLanguage(lang)
    }

    /** 确保会话所属的项目在侧边栏展开（覆盖程序化选择：自动创建会话、子代理跳转等） */
    fun ensureConversationVisible(conversationId: String) {
        val project = appState.projects.value.firstOrNull { p ->
            p.conversations.any { it.id == conversationId }
        } ?: return
        if (project.id !in uiState.expandedProjectIds) {
            uiState = uiState.copy(expandedProjectIds = uiState.expandedProjectIds + project.id)
        }
    }

    fun createProject(input: CreateProjectInput) {
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true)
            // 目录查重：若该目录已存在项目，则直接选中并展开，避免重复创建
            val existing = appState.projects.value.firstOrNull { it.directory == input.directory }
            if (existing != null) {
                uiState = uiState.copy(
                    isBusy = false,
                    expandedProjectIds = uiState.expandedProjectIds + existing.id
                )
                appState.selectProject(existing.id)
                return@launch
            }

            val result = appState.aiCore.createProject(input)
            uiState = uiState.copy(isBusy = false)
            result.fold(
                onSuccess = { project ->
                    uiState = uiState.copy(expandedProjectIds = uiState.expandedProjectIds + project.id)
                    appState.selectProject(project.id)
                },
                onFailure = { uiState = uiState.copy(error = it.message) }
            )
        }
    }

    fun createProjectFromDirectory(dir: String) {
        val name = dir.substringAfterLast("/").substringAfterLast("\\").ifBlank { dir }
        createProject(CreateProjectInput(name = name, directory = dir))
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
        // 不落库：仅本地切换到「新对话」占位（展开并选中项目、清空会话选中），与 newSession() 一致。
        // 会话真正创建发生在用户发送第一条消息时——WorkspaceViewModel.send 的
        // 无会话自动 createConversation 路径（convId==null 且项目已选）。
        uiState = uiState.copy(expandedProjectIds = uiState.expandedProjectIds + projectId)
        appState.selectProject(projectId)
        appState.selectConversation(null)
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
