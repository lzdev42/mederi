package xyz.mederi.api.impl

import xyz.mederi.api.ModelApi
import xyz.mederi.api.exception.MederiNotFoundException
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.ProviderManager

/**
 * ModelApi 实现（跨供应商聚合）。
 */
class ModelApiImpl(private val providerManager: ProviderManager) : ModelApi {

    override suspend fun list(): List<AIModel> = mederiCall {
        providerManager.listAllModels()
    }

    override suspend fun get(modelId: String): AIModel? = mederiCall {
        providerManager.getModel(modelId)
    }

    /**
     * 与接口签名保持一致：如果 model 不存在，通过统一异常清晰反馈。
     */
    suspend fun require(modelId: String): AIModel = mederiCall {
        providerManager.getModel(modelId)
            ?: throw MederiNotFoundException("Model not found: $modelId")
    }
}
