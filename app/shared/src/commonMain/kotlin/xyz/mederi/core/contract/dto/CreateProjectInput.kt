package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateProjectInput(
    val name: String,
    val directory: String,
)
