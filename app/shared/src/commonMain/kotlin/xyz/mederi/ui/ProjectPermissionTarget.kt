package xyz.mederi.ui

import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.Project

/** 已有会话只能使用自己的项目；水合期间不借当前另选的项目解释它的权限。 */
internal fun resolvePermissionProjectId(
    conversationId: String?,
    conversation: Conversation?,
    projects: List<Project>,
    selectedProjectId: String?,
): String? {
    if (conversationId == null) return selectedProjectId
    return conversation?.takeIf { it.id == conversationId }?.projectId
        ?: projects.firstOrNull { project -> project.conversations.any { it.id == conversationId } }?.id
}
