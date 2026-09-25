package xyz.mederi.domain.model

/**
 * 文件变更 diff，供 UI 展示。
 *
 * @param filePath 相对于项目目录的文件路径。
 * @param before 变更前内容（新增文件为空字符串）。
 * @param after 变更后内容（删除文件为空字符串）。
 * @param additions 新增行数。
 * @param deletions 删除行数。
 */
data class FileDiff(
    val filePath: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int
)
