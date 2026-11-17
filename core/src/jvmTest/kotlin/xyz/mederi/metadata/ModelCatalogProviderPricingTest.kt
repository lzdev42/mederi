package xyz.mederi.metadata

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 供应商目录价直查链（providerPricing）纯内存测试。
 *
 * 语义：只查**配置的供应商自己目录条目**的价格，绝不查 OpenRouter、绝不跨供应商兜底——
 * 该供应商目录没有就是 null（目录无价 ≠ OpenRouter 价）。
 *
 * 样例目录：
 * - sensenova: glm-5.2（全 0 价，真实目录价）/ glm-5.2-alias（无价，canonical 指回 glm-5.2）
 *   / no-cost（无价无 canonical）/ deepseek-flash（0.15/0.6/缓存读 0.003，真实目录价）
 *   ；**不含 glm-5.3**
 * - zai: glm-5.3（canonical zhipuai/glm-5.3，自报价 1.4 / 4.4）——供 referencePricing ④ 全局同名兜底
 * - openrouter: z-ai/glm-5.3（canonical zhipuai/glm-5.3，0.084 / 3.6）、glm-5.2（2.5 / 7.5，验证不会被误用）
 */
class ModelCatalogProviderPricingTest {

    private val sampleCatalog = """
        {
          "sensenova": {
            "api": "https://token.sensenova.cn/v1",
            "name": "SenseNova",
            "models": {
              "glm-5.2": {
                "name": "GLM 5.2",
                "cost": { "input": 0, "output": 0 }
              },
              "glm-5.2-alias": {
                "canonical_model_id": "glm-5.2",
                "name": "GLM 5.2 alias (no own price)"
              },
              "no-cost": {
                "name": "No Cost Model"
              },
              "deepseek-flash": {
                "name": "DeepSeek V4.1 Flash 1017",
                "cost": { "input": 0.15, "output": 0.6, "cache_read": 0.003 }
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
                "cost": { "input": 1.4, "output": 4.4 }
              }
            }
          },
          "openrouter": {
            "api": "https://openrouter.ai/api/v1",
            "name": "OpenRouter",
            "models": {
              "z-ai/glm-5.3": {
                "canonical_model_id": "zhipuai/glm-5.3",
                "name": "GLM 5.3",
                "cost": { "input": 0.084, "output": 3.6, "cache_read": 0.079 }
              },
              "glm-5.2": {
                "name": "GLM 5.2 (OR reference)",
                "cost": { "input": 2.5, "output": 7.5 }
              }
            }
          }
        }
    """.trimIndent()

    private val index = parseModelsDevResponse(sampleCatalog)

    @Test
    fun `ST1-PRICE0 供应商目录 0 价是真实目录价，合法非 null 且不落到 OpenRouter 价`() {
        val pricing = assertNotNull(index.providerPricing("glm-5.2", "sensenova", null))
        assertEquals(0.0, pricing.inputPerMillion)
        assertEquals(0.0, pricing.outputPerMillion)
        // 诚实原则：绝不能返回 OpenRouter 的同名价 2.5 / 7.5
        assertEquals(false, pricing.inputPerMillion == 2.5)
    }

    @Test
    fun `ST1-NULL 供应商未收录时返回 null，不兜底 OpenRouter`() {
        // openrouter 有 z-ai/glm-5.3，sensenova 没有 → 必须 null
        assertNull(index.providerPricing("glm-5.3", "sensenova", null))
        // baseUrl 路径同样不兜底
        assertNull(index.providerPricing("glm-5.3", null, "https://token.sensenova.cn/v1"))
    }

    @Test
    fun `ST1-HIT 有条目时返回供应商条目自身价`() {
        // sensenova 收录 deepseek-flash（0.15 / 0.6 / cache_read 0.003，与 OR 的同名条目价不同）
        val pricing = assertNotNull(index.providerPricing("deepseek-flash", "sensenova", null))
        assertEquals(0.15, pricing.inputPerMillion)
        assertEquals(0.6, pricing.outputPerMillion)
        assertEquals(0.003, pricing.cacheReadPerMillion)
    }

    @Test
    fun `ST1-REGRESSION 标准价链路 referencePricing 不受影响`() {
        // sensenova 未收录 glm-5.3 → 走 ④ 全局同名兜底：openrouter z-ai/glm-5.3（canonical zhipuai/glm-5.3）
        val pricing = assertNotNull(index.referencePricing("glm-5.3", "sensenova", null))
        assertEquals(0.084, pricing.inputPerMillion)
        assertEquals(3.6, pricing.outputPerMillion)
    }

    @Test
    fun `baseUrl 直查命中的是该供应商自己的条目`() {
        val pricing = assertNotNull(index.providerPricing("glm-5.2", null, "https://token.sensenova.cn/v1"))
        assertEquals(0.0, pricing.inputPerMillion)
        assertEquals(0.0, pricing.outputPerMillion)
    }

    @Test
    fun `条目自身无价时在同供应商内按 canonicalModelId 反查`() {
        val pricing = assertNotNull(index.providerPricing("glm-5.2-alias", "sensenova", null))
        assertEquals(0.0, pricing.inputPerMillion)
        assertEquals(0.0, pricing.outputPerMillion)
    }

    @Test
    fun `条目无价且无 canonical 返回 null`() {
        assertNull(index.providerPricing("no-cost", "sensenova", null))
        assertNull(index.providerPricing("glm-5.2", "unknown-provider", null))
        assertNull(index.providerPricing("glm-5.2", null, null))
    }
}
