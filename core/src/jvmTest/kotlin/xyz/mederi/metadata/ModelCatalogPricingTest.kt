package xyz.mederi.metadata

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * models.dev 查价链（referencePricing）纯内存测试。
 *
 * 样例目录 JSON 走真实字段名（canonical_model_id / cost.cache_read），价格用真实数字：
 * - openrouter: z-ai/glm-5.3 → canonical zhipuai/glm-5.3（0.084 / 3.6 / 0.079）
 * - zai: glm-5.3 → canonical zhipuai/glm-5.3，自身标价 1.4 / 4.4 / 0.26
 * - sensenova: 不含 glm-5.3（模拟用户供应商未收录该模型）
 * - orphan: solo-model（无 canonical，2.0 / 6.0）
 */
class ModelCatalogPricingTest {

    private val sampleCatalog = """
        {
          "openrouter": {
            "api": "https://openrouter.ai/api/v1",
            "name": "OpenRouter",
            "models": {
              "z-ai/glm-5.3": {
                "canonical_model_id": "zhipuai/glm-5.3",
                "name": "GLM 5.3",
                "cost": { "input": 0.084, "output": 3.6, "cache_read": 0.079 }
              }
            }
          },
          "zai": {
            "api": "https://api.z.ai/api/paas/v4",
            "name": "Z.ai",
            "models": {
              "glm-5.3": {
                "canonical_model_id": "zhipuai/glm-5.3",
                "name": "GLM 5.3",
                "cost": { "input": 1.4, "output": 4.4, "cache_read": 0.26 }
              }
            }
          },
          "sensenova": {
            "api": "https://token.sensenova.cn/v1",
            "name": "SenseNova",
            "models": {
              "sensenova-1": {
                "name": "SenseNova 1",
                "cost": { "input": 0.5, "output": 1.5 }
              }
            }
          },
          "meta-only": {
            "api": "https://meta.example.com/v1",
            "name": "Meta Only",
            "models": {
              "glm-5.3": { "canonical_model_id": "zhipuai/glm-5.3", "name": "GLM 5.3 (no price)" }
            }
          },
          "orphan": {
            "api": "https://orphan.example.com/v1",
            "name": "Orphan",
            "models": {
              "solo-model": {
                "name": "Solo Model",
                "cost": { "input": 2.0, "output": 6.0 }
              }
            }
          }
        }
    """.trimIndent()

    private val index = parseModelsDevResponse(sampleCatalog)

    @Test
    fun `ST0-CANONICAL 供应商未收录时走全局同名 canonical 反查 OpenRouter`() {
        val pricing = assertNotNull(
            index.referencePricing("glm-5.3", "sensenova", "https://token.sensenova.cn/v1")
        )
        assertEquals(0.084, pricing.inputPerMillion)
        assertEquals(3.6, pricing.outputPerMillion)
        assertEquals(0.079, pricing.cacheReadPerMillion)
    }

    @Test
    fun `ST0-DIRECT 供应商自己收录时用自己的价而非 OpenRouter 参考价`() {
        val pricing = assertNotNull(index.referencePricing("glm-5.3", "zai", null))
        assertEquals(1.4, pricing.inputPerMillion)
        assertEquals(4.4, pricing.outputPerMillion)
        assertEquals(0.26, pricing.cacheReadPerMillion)
        // 防 OR 劫持回归：绝不能落到 OpenRouter 的 0.084
        assertEquals(false, pricing.inputPerMillion == 0.084)
    }

    @Test
    fun `无 canonical 映射时用全局同名条目自身价格`() {
        val pricing = assertNotNull(index.referencePricing("solo-model", "unknown-provider", null))
        assertEquals(2.0, pricing.inputPerMillion)
        assertEquals(6.0, pricing.outputPerMillion)
    }

    @Test
    fun `供应商自己收录但无价格时走 canonical 反查 OpenRouter`() {
        val pricing = assertNotNull(
            index.referencePricing("glm-5.3", "meta-only", "https://meta.example.com/v1")
        )
        assertEquals(0.084, pricing.inputPerMillion)
        assertEquals(3.6, pricing.outputPerMillion)
    }

    @Test
    fun `目录完全没有的模型返回 null`() {
        assertNull(index.referencePricing("not-in-catalog", "zai", null))
        assertNull(index.referencePricing("missing", "unknown-provider", null))
    }
}
