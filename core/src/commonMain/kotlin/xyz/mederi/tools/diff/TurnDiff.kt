package xyz.mederi.tools.diff

import kotlinx.serialization.Serializable

/**
 * 文件变更状态。
 */
@Serializable
enum class FileChangeStatus {
    ADDED,
    MODIFIED,
    DELETED
}

/**
 * 单个文件变更。
 *
 * @param path 相对于项目目录的路径。
 * @param status 变更状态。
 * @param before 变更前内容（ADDED 为 null）。
 * @param after 变更后内容（DELETED 为 null）。
 */
@Serializable
data class FileChange(
    val path: String,
    val status: FileChangeStatus,
    val before: String?,
    val after: String?
)

/**
 * 一次 turn 产生的完整 diff。
 *
 * @param sessionId 所属 Session ID。
 * @param messageId 产生该 diff 的 assistant message ID（可能为 null）。
 * @param changes 文件变更列表。
 * @param unifiedDiff git 风格的 unified diff 文本。
 * @param createdAt ISO 8601 时间戳。
 */
data class TurnDiff(
    val sessionId: String,
    val messageId: String?,
    val changes: List<FileChange>,
    val unifiedDiff: String,
    val createdAt: String
)

/**
 * 单个文件的变更统计摘要。
 */
@Serializable
data class FileDiffSummary(
    val path: String,
    val status: FileChangeStatus,
    val additions: Int,
    val deletions: Int
)

/**
 * 单次 Turn 的全部文件变更聚合摘要。
 */
@Serializable
data class TurnDiffSummary(
    val files: List<FileDiffSummary> = emptyList(),
    val totalAdditions: Int = 0,
    val totalDeletions: Int = 0
)

