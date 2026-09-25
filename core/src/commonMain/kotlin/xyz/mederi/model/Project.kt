package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/**
 * 项目（Project）。
 *
 * 项目是一个目录，也是会话（Session）的容器。
 * 一个项目恰好绑定一个目录（单目录模型），不存在多目录项目。
 * 会话创建时必须绑定到一个项目，Agent 的读写权限作用于该项目目录。
 *
 * @param id 系统生成的项目唯一 ID。
 * @param name 项目显示名称。
 * @param directory 项目绑定的绝对路径目录（唯一，非空）。
 * @param createdAt ISO 8601 创建时间戳。
 * @param updatedAt ISO 8601 更新时间戳。
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val directory: String,
    val createdAt: String,
    val updatedAt: String
) {
    init {
        require(directory.isNotBlank()) {
            "Project must have a directory"
        }
    }
}
