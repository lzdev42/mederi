package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val directory: String,
    val conversations: List<Conversation>,
    val createdAt: Long = 0L,
)
