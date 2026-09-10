package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

enum class FileChangeStatus { Added, Modified, Deleted }

@Serializable
data class FileChange(
    val filePath: String,
    val status: FileChangeStatus,
)

@Serializable
data class FileDiff(
    val filePath: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int,
)
