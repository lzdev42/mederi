package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val directory: String,
    val conversations: List<Conversation>,
    val createdAt: Long = 0L,
    /** core 布局约定：项目工作目录（= directory + "/.mederi"），由 MederiModelMapper 填充。空 = 未知（旧服务端）。 */
    val workDir: String = "",
)
