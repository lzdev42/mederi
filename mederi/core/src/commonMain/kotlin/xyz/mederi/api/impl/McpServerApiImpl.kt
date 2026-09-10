package xyz.mederi.api.impl

import xyz.mederi.api.McpServerApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.mcp.servers.McpServerManager
import xyz.mederi.mcp.servers.domain.McpInstallResult
import xyz.mederi.mcp.servers.domain.McpServerInfo
import xyz.mederi.mcp.servers.domain.McpVerifyResult

/**
 * McpServerApi 实现（薄转调 + 异常转换，无 DTO 转换需求——入参出参本身就是简单类型/领域结果）。
 */
class McpServerApiImpl(private val manager: McpServerManager) : McpServerApi {

    override suspend fun install(mcpServersJson: String): McpInstallResult = mederiCall {
        manager.install(mcpServersJson)
    }

    override suspend fun list(): List<McpServerInfo> = mederiCall { manager.list() }

    override suspend fun getJson(name: String): String = mederiCall { manager.getJson(name) }

    override suspend fun update(name: String, mcpServersJson: String): McpInstallResult = mederiCall {
        manager.update(name, mcpServersJson)
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        mederiCall { manager.setEnabled(name, enabled) }
    }

    override suspend fun delete(name: String) {
        mederiCall { manager.delete(name) }
    }

    override suspend fun verify(name: String): McpVerifyResult = mederiCall { manager.verify(name) }

    override suspend fun verifyConfig(mcpServersJson: String): McpVerifyResult = mederiCall {
        manager.verifyConfig(mcpServersJson)
    }
}
