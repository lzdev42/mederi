package xyz.mederi.provider

import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.ModelOrigin
import xyz.mederi.metadata.ModelMetadata
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.RemoteModelInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ModelMerge 穷举测试：每字段 × 端点有/无 × 目录有/无 × 本地有/无。
 *
 * 合并规则唯一实现就是这张表——测了它 = 测了所有写入路径（refresh / 启动回填）。
 * 事故防线：任何绕过 ModelMerge 手写字段合并的改动，都必须先改这里并让全组合断言通过。
 */
class ModelMergeTest {

    // ==================== fixtures ====================

    private fun model(
        name: String = "old-name",
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        supportsReasoning: Boolean = false,
        supportsImages: Boolean = false,
        reasoningLevels: List<ReasoningLevel> = emptyList(),
        reasoningLevel: ReasoningLevel = ReasoningLevel.NONE,
        isEnabled: Boolean = true,
        inputPrice: Double? = null,
        outputPrice: Double? = null
    ) = AIModel(
        id = "mdl_1",
        providerModelId = "gpt-x",
        name = name,
        supportsReasoning = supportsReasoning,
        reasoningLevel = reasoningLevel,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        supportsImages = supportsImages,
        reasoningLevels = reasoningLevels,
        origin = ModelOrigin.FETCHED,
        isEnabled = isEnabled,
        inputPricePerMillion = inputPrice,
        outputPricePerMillion = outputPrice
    )

    /** 端点解析结果（supportsImages/prices/levels 端点永不提供，字段不存在） */
    private fun endpoint(
        name: String? = "new-name",
        contextWindow: Int? = null,
        maxTokens: Int? = null,
        supportsReasoning: Boolean? = null
    ) = RemoteModelInfo(
        providerModelId = "gpt-x",
        name = name ?: "gpt-x",
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        supportsReasoning = supportsReasoning
    )

    private fun catalog(
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        supportsReasoning: Boolean? = null,
        supportsImages: Boolean? = null,
        reasoningLevels: List<ReasoningLevel> = emptyList(),
        inputPrice: Double? = null,
        outputPrice: Double? = null
    ) = ModelMetadata(
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        inputPricePerMillion = inputPrice,
        outputPricePerMillion = outputPrice,
        supportsImages = supportsImages,
        supportsReasoning = supportsReasoning,
        reasoningLevels = reasoningLevels
    )

    // ==================== contextWindow / maxTokens（端点可说） ====================

    @Test
    fun `refresh - 端点值优先于目录`() {
        val merged = ModelMerge.mergeFetched(
            model(contextWindow = 50_000),
            endpoint = endpoint(contextWindow = 262_144),
            catalog = catalog(contextWindow = 200_000)
        )
        assertEquals(262_144, merged.contextWindow)
    }

    @Test
    fun `refresh - 端点未提供时目录补位`() {
        val merged = ModelMerge.mergeFetched(
            model(contextWindow = 50_000),
            endpoint = endpoint(contextWindow = null),
            catalog = catalog(contextWindow = 200_000)
        )
        assertEquals(200_000, merged.contextWindow)
    }

    @Test
    fun `refresh - 端点目录都没给时保留本地`() {
        val merged = ModelMerge.mergeFetched(
            model(contextWindow = 50_000),
            endpoint = endpoint(contextWindow = null),
            catalog = catalog(contextWindow = null)
        )
        assertEquals(50_000, merged.contextWindow)
    }

    @Test
    fun `backfill - 本地为空时从目录补全`() {
        val merged = ModelMerge.mergeFetched(
            model(contextWindow = null),
            endpoint = null,
            catalog = catalog(contextWindow = 200_000)
        )
        assertEquals(200_000, merged.contextWindow)
    }

    @Test
    fun `backfill - 本地已有值绝不覆盖（防端点部署值被目录打回，跨路径零冲突）`() {
        // refresh 写入端点部署值 → 重启回填不得用目录值覆盖（历史事故类）
        val afterRefresh = ModelMerge.mergeFetched(
            model(contextWindow = null),
            endpoint = endpoint(contextWindow = 262_144),
            catalog = catalog(contextWindow = 200_000)
        )
        val afterBackfill = ModelMerge.mergeFetched(
            afterRefresh,
            endpoint = null,
            catalog = catalog(contextWindow = 200_000)
        )
        assertEquals(262_144, afterBackfill.contextWindow)
    }

    @Test
    fun `maxTokens - 端点可说双路径规则同 contextWindow`() {
        val refreshed = ModelMerge.mergeFetched(
            model(maxTokens = 4_096),
            endpoint = endpoint(maxTokens = 65_536),
            catalog = catalog(maxOutputTokens = 8_192)
        )
        assertEquals(65_536, refreshed.maxTokens)
        val backfilled = ModelMerge.mergeFetched(
            model(maxTokens = 65_536),
            endpoint = null,
            catalog = catalog(maxOutputTokens = 8_192)
        )
        assertEquals(65_536, backfilled.maxTokens)
    }

    // ==================== supportsReasoning（端点可说·目录优先，单一表达式） ====================

    @Test
    fun `supportsReasoning - 目录优先于端点（单一表达式，两路径恒一致）`() {
        val merged = ModelMerge.mergeFetched(
            model(supportsReasoning = true),
            endpoint = endpoint(supportsReasoning = false),
            catalog = catalog(supportsReasoning = true)
        )
        assertEquals(true, merged.supportsReasoning)
    }

    @Test
    fun `supportsReasoning - 目录未标注时端点兜底`() {
        val merged = ModelMerge.mergeFetched(
            model(),
            endpoint = endpoint(supportsReasoning = true),
            catalog = catalog(supportsReasoning = null)
        )
        assertEquals(true, merged.supportsReasoning)
    }

    @Test
    fun `supportsReasoning - 两源都沉默时保留本地`() {
        val merged = ModelMerge.mergeFetched(
            model(supportsReasoning = true),
            endpoint = endpoint(supportsReasoning = null),
            catalog = catalog(supportsReasoning = null)
        )
        assertEquals(true, merged.supportsReasoning)
    }

    @Test
    fun `supportsReasoning - 回填补全存量空值`() {
        val merged = ModelMerge.mergeFetched(
            model(supportsReasoning = false),
            endpoint = null,
            catalog = catalog(supportsReasoning = true)
        )
        assertEquals(true, merged.supportsReasoning)
    }

    // ==================== supportsImages（目录独有，标注即权威） ====================

    @Test
    fun `supportsImages - 目录标注覆盖本地（true 或 false 都是权威，自愈）`() {
        val on = ModelMerge.mergeFetched(
            model(supportsImages = false),
            endpoint = endpoint(),
            catalog = catalog(supportsImages = true)
        )
        assertEquals(true, on.supportsImages)
        val off = ModelMerge.mergeFetched(
            model(supportsImages = true),
            endpoint = null,
            catalog = catalog(supportsImages = false)
        )
        assertEquals(false, off.supportsImages)
    }

    @Test
    fun `supportsImages - 目录未收录时保留本地`() {
        val merged = ModelMerge.mergeFetched(
            model(supportsImages = true),
            endpoint = endpoint(),
            catalog = null
        )
        assertEquals(true, merged.supportsImages)
    }

    // ==================== supportsImagesOverride（用户覆盖 > 目录） ====================

    @Test
    fun `用户覆盖 - true 压过目录 false（目录缺数据或标错时用户显式设置必须赢）`() {
        val merged = ModelMerge.mergeFetched(
            model(supportsImages = false).copy(supportsImagesOverride = true),
            endpoint = endpoint(),
            catalog = catalog(supportsImages = false)
        )
        assertEquals(true, merged.supportsImages)
    }

    @Test
    fun `用户覆盖 - false 压过目录 true，且 refresh 与回填两条路径一致`() {
        val existing = model(supportsImages = true).copy(supportsImagesOverride = false)
        val refreshed = ModelMerge.mergeFetched(existing, endpoint = endpoint(), catalog = catalog(supportsImages = true))
        assertEquals(false, refreshed.supportsImages)
        val backfilled = ModelMerge.mergeFetched(existing, endpoint = null, catalog = catalog(supportsImages = true))
        assertEquals(false, backfilled.supportsImages)
    }

    @Test
    fun `用户覆盖 - 跨 refresh-回填循环永不洗掉`() {
        var current = model(supportsImages = false).copy(supportsImagesOverride = true)
        current = ModelMerge.mergeFetched(current, endpoint = endpoint(), catalog = catalog(supportsImages = false))
        assertEquals(true, current.supportsImages)
        current = ModelMerge.mergeFetched(current, endpoint = null, catalog = catalog(supportsImages = false))
        assertEquals(true, current.supportsImages)
    }

    @Test
    fun `用户覆盖 - 未设置时行为与无覆盖完全一致`() {
        val existing = model(supportsImages = false)
        assertEquals(
            ModelMerge.mergeFetched(existing, endpoint = endpoint(), catalog = catalog(supportsImages = true)),
            ModelMerge.mergeFetched(existing.copy(supportsImagesOverride = null), endpoint = endpoint(), catalog = catalog(supportsImages = true))
        )
    }

    // ==================== 价格（目录独有） ====================

    @Test
    fun `价格 - 目录填充，未收录保留`() {
        val filled = ModelMerge.mergeFetched(
            model(),
            endpoint = endpoint(),
            catalog = catalog(inputPrice = 1.25, outputPrice = 10.0)
        )
        assertEquals(1.25, filled.inputPricePerMillion)
        assertEquals(10.0, filled.outputPricePerMillion)
        val kept = ModelMerge.mergeFetched(
            model(inputPrice = 2.0),
            endpoint = endpoint(),
            catalog = null
        )
        assertEquals(2.0, kept.inputPricePerMillion)
    }

    // ==================== reasoningLevels（目录独有，空 = 未标注保留） ====================

    @Test
    fun `档位 - 目录有档位覆盖，无档位保留本地`() {
        val overwritten = ModelMerge.mergeFetched(
            model(reasoningLevels = listOf(ReasoningLevel.LOW)),
            endpoint = endpoint(),
            catalog = catalog(reasoningLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX))
        )
        assertEquals(listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), overwritten.reasoningLevels)
        val kept = ModelMerge.mergeFetched(
            model(reasoningLevels = listOf(ReasoningLevel.HIGH)),
            endpoint = endpoint(),
            catalog = catalog(reasoningLevels = emptyList())
        )
        assertEquals(listOf(ReasoningLevel.HIGH), kept.reasoningLevels)
    }

    @Test
    fun `档位 - 回填补空`() {
        val merged = ModelMerge.mergeFetched(
            model(reasoningLevels = emptyList()),
            endpoint = null,
            catalog = catalog(reasoningLevels = listOf(ReasoningLevel.MEDIUM))
        )
        assertEquals(listOf(ReasoningLevel.MEDIUM), merged.reasoningLevels)
    }

    // ==================== name（仅端点提供） ====================

    @Test
    fun `name - refresh 取端点 displayName，回填不动`() {
        val refreshed = ModelMerge.mergeFetched(
            model(name = "old-name"),
            endpoint = endpoint(name = "GPT-X Turbo"),
            catalog = null
        )
        assertEquals("GPT-X Turbo", refreshed.name)
        val backfilled = ModelMerge.mergeFetched(
            model(name = "old-name"),
            endpoint = null,
            catalog = catalog()
        )
        assertEquals("old-name", backfilled.name)
    }

    // ==================== 用户权威字段恒保留 ====================

    @Test
    fun `用户字段 - isEnabled 与 reasoningLevel 与 origin 与 id 恒不被合并触碰`() {
        val existing = model(
            isEnabled = false,
            reasoningLevel = ReasoningLevel.HIGH
        )
        val merged = ModelMerge.mergeFetched(
            existing,
            endpoint = endpoint(),
            catalog = catalog(supportsImages = true, supportsReasoning = true)
        )
        assertEquals(false, merged.isEnabled)
        assertEquals(ReasoningLevel.HIGH, merged.reasoningLevel)
        assertEquals(ModelOrigin.FETCHED, merged.origin)
        assertEquals("mdl_1", merged.id)
        assertEquals("gpt-x", merged.providerModelId)
    }

    // ==================== createFetched（新增路径与既有路径同一张表） ====================

    @Test
    fun `createFetched - 空模板全量合并，默认值语义不变`() {
        val created = ModelMerge.createFetched(
            "gpt-x",
            endpoint = endpoint(name = "GPT-X", contextWindow = 128_000, supportsReasoning = true),
            catalog = catalog(supportsImages = true, inputPrice = 1.25)
        )
        assertEquals("", created.id)
        assertEquals("gpt-x", created.providerModelId)
        assertEquals("GPT-X", created.name)
        assertEquals(128_000, created.contextWindow)
        assertEquals(true, created.supportsReasoning)
        assertEquals(true, created.supportsImages)
        assertEquals(1.25, created.inputPricePerMillion)
        assertEquals(ModelOrigin.FETCHED, created.origin)
        assertEquals(true, created.isEnabled)
        assertEquals(ReasoningLevel.NONE, created.reasoningLevel)
    }

    // ==================== 跨路径稳定性（闪回事故回归） ====================

    @Test
    fun `端点部署值跨 refresh-回填-refresh 循环稳定不闪烁`() {
        var current = model(contextWindow = null, supportsImages = false)
        // 第 1 次 refresh：端点给部署值
        current = ModelMerge.mergeFetched(current, endpoint(contextWindow = 262_144), catalog(contextWindow = 200_000))
        assertEquals(262_144, current.contextWindow)
        // 重启回填：目录值不得打回端点值
        current = ModelMerge.mergeFetched(current, endpoint = null, catalog(contextWindow = 200_000))
        assertEquals(262_144, current.contextWindow)
        // 再次 refresh：端点值更新
        current = ModelMerge.mergeFetched(current, endpoint(contextWindow = 131_072), catalog(contextWindow = 200_000))
        assertEquals(131_072, current.contextWindow)
        // 目录标注图片能力：回填自愈
        current = ModelMerge.mergeFetched(current, endpoint = null, catalog(contextWindow = 200_000, supportsImages = true))
        assertEquals(true, current.supportsImages)
    }
}
