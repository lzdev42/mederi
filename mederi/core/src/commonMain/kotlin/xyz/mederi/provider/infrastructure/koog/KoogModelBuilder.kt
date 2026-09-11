package xyz.mederi.provider.infrastructure.koog

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import xyz.mederi.domain.model.AIModel
import xyz.mederi.provider.domain.model.ProviderType

/**
 * Koog 模型构建器。
 *
 * 将 Mederi 的 [AIModel] 转换为 Koog 的 [LLModel]。
 * 转换过程中会根据供应商类型和模型配置设置相应的能力标记（[LLMCapability]）。
 *
 * 能力标记映射规则：
 * - OpenAI Chat 模型: 添加 [LLMCapability.OpenAIEndpoint.Completions]
 * - OpenAI Responses 模型: 添加 [LLMCapability.OpenAIEndpoint.Responses]
     * - 支持 reasoning 的模型: 添加 [LLMCapability.Thinking]
 * - 所有模型默认添加: [LLMCapability.Temperature], [LLMCapability.Tools]
 *
 * 这是一个无状态的构建器对象，线程安全。
 */
object KoogModelBuilder {

    /**
     * 从 Mederi 的 ProviderModel 构建 Koog 的 LLModel。
     *
     * @param model Mederi AI 模型定义。
     * @param type 供应商类型，用于确定 LLMProvider 和端点能力。
     * @return Koog LLModel 实例，包含正确的能力标记。
     */
    fun build(model: AIModel, type: ProviderType): LLModel {
        val provider = resolveProvider(type)
        val capabilities = buildCapabilities(model, type)
        return LLModel(
            provider = provider,
            id = model.providerModelId,
            capabilities = capabilities
        )
    }

    /**
     * 根据供应商类型解析 Koog LLMProvider。
     *
     * - OpenAI Chat / Responses -> [LLMProvider.OpenAI]
     * - Google -> [LLMProvider.Google]
     */
    private fun resolveProvider(type: ProviderType): LLMProvider = when (type) {
        ProviderType.OPENAI_CHAT, ProviderType.OPENAI_RESPONSES -> LLMProvider.OpenAI
        ProviderType.GOOGLE -> LLMProvider.Google
    }

    /**
     * 构建模型能力列表。
     *
     * 能力标记设置规则：
     * 1. OpenAI 端点能力（根据供应商类型）：
     *    - OPENAI_CHAT: 添加 OpenAIEndpoint.Completions
     *    - OPENAI_RESPONSES: 添加 OpenAIEndpoint.Responses
     *    - GOOGLE: 不添加 OpenAIEndpoint（Google 使用自己的 API 协议）
     * 2. Thinking 能力：如果 [AIModel.supportsReasoning] 为 true，添加 [LLMCapability.Thinking]
     * 3. 图片能力：如果 [AIModel.supportsImages] 为 true，添加 [LLMCapability.Vision.Image]——
     *    缺了它 Koog 会在发送时直接拒绝图片消息（"does not support images"），
     *    UI/存储怎么设都没用（历史事故：设置链路全通、唯独引擎能力缺失，用户永远报不支持图片）
     * 4. 默认能力：添加 [LLMCapability.Temperature] 和 [LLMCapability.Tools]
     *    （大多数现代模型都支持温度调节和工具调用）
     *
     * @param model Mederi AI 模型定义。
     * @param type 供应商类型。
     * @return Koog 能力列表。
     */
    private fun buildCapabilities(model: AIModel, type: ProviderType): List<LLMCapability> {
        val capabilities = mutableListOf<LLMCapability>()

        // OpenAI 端点能力 + 基础 Completion 能力
        when (type) {
            ProviderType.OPENAI_CHAT -> {
                capabilities.add(LLMCapability.OpenAIEndpoint.Completions)
                capabilities.add(LLMCapability.Completion)
            }
            ProviderType.OPENAI_RESPONSES -> {
                capabilities.add(LLMCapability.OpenAIEndpoint.Responses)
                capabilities.add(LLMCapability.Completion)
            }
            ProviderType.GOOGLE -> {
                capabilities.add(LLMCapability.Completion)
            }
        }

        // Thinking 能力
        if (model.supportsReasoning) {
            capabilities.add(LLMCapability.Thinking)
        }

        // 图片能力（supportsImages 含用户覆盖语义——消费端读到的生效值）
        if (model.supportsImages) {
            capabilities.add(LLMCapability.Vision.Image)
        }

        // 默认能力（大多数现代模型都支持）
        capabilities.add(LLMCapability.Temperature)
        capabilities.add(LLMCapability.Tools)

        return capabilities
    }
}
