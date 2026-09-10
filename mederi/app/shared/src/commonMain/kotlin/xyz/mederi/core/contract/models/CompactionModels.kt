package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class CompactionConfig(
    val auto: Boolean? = null,
    val tailTurns: Int? = null,
    val preserveRecentTokens: Int? = null,
    val reserved: Int? = null,
    val prune: Boolean? = null,
)
