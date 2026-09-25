package xyz.mederi.provider

import xyz.mederi.domain.model.AIModel
import xyz.mederi.metadata.ModelMetadata
import xyz.mederi.provider.domain.model.RemoteModelInfo

/**
 * 远端元数据合并——FETCHED 模型元数据的**唯一**写入实现（表驱动，穷举测试锁定）。
 *
 * 两个同步入口共用本函数，只能喂不同输入、带不进不同规则：
 * - refresh（endpoint != null）：端点 + 目录全量权威重同步
 * - 启动回填（endpoint == null）：只有目录信息，只补空、绝不覆盖已填字段
 *
 * 字段所有权表（新增 AIModel 元数据字段必须在此登记，见 AGENTS.md）：
 *
 * | 类别             | 字段                                             | 规则 |
 * |------------------|--------------------------------------------------|------|
 * | 用户权威         | isEnabled、reasoningLevel、origin、id             | 恒保留，本函数不触碰 |
 * | 用户覆盖         | supportsImagesOverride                            | 存在时永远压过目录/端点（目录对长尾模型经常缺数据或标错，用户显式设置必须赢） |
 * | 端点可说         | contextWindow、maxTokens                         | refresh = endpoint ?: catalog ?: keep；回填 = keep ?: catalog（只补空） |
 * | 端点可说·目录优先 | supportsReasoning                                | catalog ?: endpoint ?: keep（单一表达式，两路径恒一致） |
 * | 目录独有         | supportsImages（生效值）、reasoningLevels、价格   | catalog 有值即覆盖（端点不提供该信息，目录是唯一远端源） |
 * | 名称             | name                                             | refresh = endpoint（displayName）；回填 = keep |
 *
 * 目录优先/覆盖语义说明：
 * - supportsReasoning 选目录优先而非端点优先：端点信号（Gemini thinking）与目录重叠度高，
 *   单表达式保证 refresh 与回填零冲突（否则回填会用目录值打回端点值，跨重启闪烁）。
 * - supportsImages 等目录独有字段在回填时也覆盖：这些字段不存在"端点给的更准"的情况，
 *   覆盖即自愈（存量模型目录标注更新后无需手动刷新）。
 *
 * MANUAL 模型不进本函数（用户权威，调用方负责跳过）。
 */
object ModelMerge {

    /**
     * 合并远端元数据进已有 FETCHED 模型。
     *
     * @param endpoint /models 端点解析结果；null = 启动回填场景（无端点信息）。
     * @param catalog models.dev 目录元数据；null = 目录未收录（保持本地值）。
     */
    fun mergeFetched(existing: AIModel, endpoint: RemoteModelInfo?, catalog: ModelMetadata?): AIModel {
        val refreshing = endpoint != null
        return existing.copy(
            // 端点可说：refresh 端点优先（部署实际值，如 Hetzner max_model_len）；
            // 回填只补空——绝不覆盖已填值（可能是端点给的部署值，目录更笼统）
            contextWindow = when {
                refreshing -> endpoint?.contextWindow ?: catalog?.contextWindow ?: existing.contextWindow
                else -> existing.contextWindow ?: catalog?.contextWindow
            },
            maxTokens = when {
                refreshing -> endpoint?.maxTokens ?: catalog?.maxOutputTokens ?: existing.maxTokens
                else -> existing.maxTokens ?: catalog?.maxOutputTokens
            },
            // 端点可说·目录优先：单一表达式，refresh 与回填恒一致（防跨路径打架）
            supportsReasoning = catalog?.supportsReasoning ?: endpoint?.supportsReasoning ?: existing.supportsReasoning,
            // 图片能力：用户覆盖 > 目录标注 > 保留。覆盖值由用户在 UI 显式设置
            //（updateUserModel 写入 supportsImagesOverride），本函数保证同步永不洗掉它
            supportsImages = existing.supportsImagesOverride
                ?: catalog?.supportsImages
                ?: existing.supportsImages,
            inputPricePerMillion = catalog?.inputPricePerMillion ?: existing.inputPricePerMillion,
            outputPricePerMillion = catalog?.outputPricePerMillion ?: existing.outputPricePerMillion,
            // 目录档位权威；目录无档位（空列表）保留本地
            reasoningLevels = catalog?.reasoningLevels?.ifEmpty { existing.reasoningLevels } ?: existing.reasoningLevels,
            // 名称只来自端点（目录无 displayName）；回填不动
            name = endpoint?.name ?: existing.name
        )
    }

    /**
     * 从零构建新增 FETCHED 模型（refresh 拉到新模型时用）。
     * 与 [mergeFetched] 同一张规则表：空模板 + 全量远端输入。
     */
    fun createFetched(providerModelId: String, endpoint: RemoteModelInfo, catalog: ModelMetadata?): AIModel =
        mergeFetched(
            existing = AIModel(id = "", providerModelId = providerModelId, name = endpoint.name),
            endpoint = endpoint,
            catalog = catalog
        )
}
