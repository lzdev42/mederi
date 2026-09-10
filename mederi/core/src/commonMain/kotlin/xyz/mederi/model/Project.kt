package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/**
 * 项目（Project）。
 *
 * 项目是目录的集合，也是会话（Session）的容器。
 * 一个项目必须至少包含一个目录，不允许存在没有目录的项目。
 * 项目内的所有目录地位平等，不存在主目录/次目录之分。
 *
 * 会话创建时必须绑定到一个项目，Agent 的读写权限默认作用于该项目下的所有目录。
 *
 * @param id 系统生成的项目唯一 ID。
 * @param name 项目显示名称。
 * @param directories 项目包含的绝对路径目录列表，至少一个。
 * @param createdAt ISO 8601 创建时间戳。
 * @param updatedAt ISO 8601 更新时间戳。
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val directories: List<String>,
    val createdAt: String,
    val updatedAt: String
) {
    init {
        require(directories.isNotEmpty()) {
            "Project must have at least one directory"
        }
    }
}
