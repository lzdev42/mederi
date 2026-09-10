package xyz.mederi.api

import xyz.mederi.domain.model.AIModel

/**
 * Model API（跨供应商聚合）。
 */
interface ModelApi {
    suspend fun list(): List<AIModel>
    suspend fun get(modelId: String): AIModel?
}
