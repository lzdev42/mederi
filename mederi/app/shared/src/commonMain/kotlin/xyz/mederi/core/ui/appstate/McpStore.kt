package xyz.mederi.core.ui.appstate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.McpServerItem
import xyz.mederi.core.ui.DebugLog

/**
 * MCP 服务唯一真理源（Single Source of Truth）。
 *
 * 集中管理已配置的 MCP Server 列表、启停开关与生命周期动作。
 * 由 [AppState] 统一部署并持有生命周期，概览卡片与后续 MCP 市场等所有 UI 组件
 * 共享此同一实例，实现双向完全同步。
 */
class McpStore(
    private val aiCore: AiCore,
    private val scope: CoroutineScope,
) {
    private val _mcpServers = MutableStateFlow<List<McpServerItem>>(emptyList())
    val mcpServers: StateFlow<List<McpServerItem>> = _mcpServers.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // 内核就绪后立即触发首次加载
        scope.launch {
            aiCore.isReady.first { it }
            refresh()
        }
    }

    /** 刷新 MCP Server 列表。 */
    fun refresh() {
        scope.launch {
            _isRefreshing.value = true
            try {
                DebugLog.info("McpStore", "refreshMcpServers started. aiCore.isReady=${aiCore.isReady.value}")
                val res = aiCore.listMcpServers()
                if (res.isSuccess) {
                    val list = res.getOrDefault(emptyList())
                    DebugLog.info("McpStore", "refreshMcpServers success. Found ${list.size} servers: ${list.map { it.name }}")
                    _mcpServers.value = list
                } else {
                    val err = res.exceptionOrNull()
                    DebugLog.error("McpStore", "refreshMcpServers failed: ${err?.message}", err)
                    _errorMessage.value = err?.message ?: "刷新 MCP 服务列表失败"
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 启停 MCP Server（乐观更新 + 异步请求 + 重新对齐）。 */
    fun toggleEnabled(name: String, enabled: Boolean) {
        scope.launch {
            _mcpServers.value = _mcpServers.value.map { if (it.name == name) it.copy(enabled = enabled) else it }
            val res = aiCore.setMcpServerEnabled(name, enabled)
            if (res.isFailure) {
                _errorMessage.value = res.exceptionOrNull()?.message ?: "切换 MCP 状态失败"
            }
            refresh()
        }
    }

    /** 安装/新增 MCP Server。 */
    suspend fun install(json: String): Result<Unit> {
        _isOperating.value = true
        return try {
            val res = aiCore.installMcpServer(json)
            if (res.isSuccess) {
                refresh()
            } else {
                _errorMessage.value = res.exceptionOrNull()?.message ?: "安装 MCP 服务失败"
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    /** 更新/编辑 MCP Server 配置。 */
    suspend fun update(name: String, json: String): Result<Unit> {
        _isOperating.value = true
        return try {
            val res = aiCore.updateMcpServer(name, json)
            if (res.isSuccess) {
                refresh()
            } else {
                _errorMessage.value = res.exceptionOrNull()?.message ?: "更新 MCP 服务失败"
            }
            res
        } finally {
            _isOperating.value = false
        }
    }

    /** 删除 MCP Server。 */
    fun delete(name: String) {
        scope.launch {
            _isOperating.value = true
            try {
                val res = aiCore.deleteMcpServer(name)
                if (res.isSuccess) {
                    refresh()
                } else {
                    _errorMessage.value = res.exceptionOrNull()?.message ?: "删除 MCP 服务失败"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    /** 验证/测试 MCP Server。 */
    fun verify(name: String) {
        scope.launch {
            _isOperating.value = true
            try {
                val res = aiCore.verifyMcpServer(name)
                if (res.isSuccess) {
                    refresh()
                } else {
                    _errorMessage.value = res.exceptionOrNull()?.message ?: "验证 MCP 服务失败"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    /** 读取单个 MCP Server 的原始配置 JSON。 */
    suspend fun getJson(name: String): Result<String> {
        return aiCore.getMcpServerJson(name)
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
