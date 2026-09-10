package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

enum class TodoStatus { Pending, InProgress, Completed, Cancelled }

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus,
    val priority: String? = null,
)
