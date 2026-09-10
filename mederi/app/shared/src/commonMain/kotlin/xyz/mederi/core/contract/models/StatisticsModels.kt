package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    val total: Long get() = input + output + reasoning
}

@Serializable
data class CostSummary(
    val total: Double = 0.0,
    val currency: String = "USD",
)
