package xyz.mederi.api.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mederi.api.SandboxApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.SandboxManager
import xyz.mederi.tools.sandbox.SandboxSettings

/** 只做转调、异常转换及阻塞探测调度，不持有第二份权限配置。 */
class SandboxApiImpl(private val manager: SandboxManager) : SandboxApi {
    override suspend fun getSettings(projectId: String) = mederiCall { manager.getSettings(projectId) }

    override suspend fun updateSettings(projectId: String, settings: SandboxSettings) = mederiCall {
        manager.updateSettings(projectId, settings, expectedVersion = settings.version)
    }

    override suspend fun getStatus(projectId: String) = mederiCall {
        withContext(Dispatchers.IO) {
            val settings = manager.getSettings(projectId)
            CommandSandbox(emptyList(), settings.extraWritablePaths, manager.protectedDirectories, settings.deniedPaths).status()
        }
    }

    override fun pendingApprovals(conversationId: String) = mederiCall { manager.pendingApprovals(conversationId) }

    override suspend fun resolveExecutionApproval(conversationId: String, requestId: String, approved: Boolean) = mederiCall {
        manager.resolve(conversationId, requestId, approved)
    }
}
