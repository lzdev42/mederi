package xyz.mederi.tools.diff

/**
 * apply_patch 工具产生的文件变更，用于向 [TurnDiffTracker] 汇报。
 *
 * @param path 文件路径。
 * @param status 变更状态。
 * @param before 变更前内容（新增文件为 null）。
 * @param after 变更后内容（删除文件为 null）。
 */
data class PatchChange(
    val path: String,
    val status: FileChangeStatus,
    val before: String?,
    val after: String?
)
