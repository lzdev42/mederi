package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val directories: List<String>,
    val conversations: List<Conversation>,
)
