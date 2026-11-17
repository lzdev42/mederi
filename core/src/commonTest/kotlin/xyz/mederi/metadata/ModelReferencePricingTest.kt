package xyz.mederi.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelReferencePricingTest {
    private val index = parseModelsDevResponse(
        """
        {
          "openrouter": {
            "api": "https://openrouter.ai/api/v1",
            "models": {
              "openai/gpt-example": {"cost":{"input":2,"output":8,"cache_read":"0.5","cache_write":3}},
              "openai/gpt-example:free": {"cost":{"input":0,"output":0}},
              "google/gemini-example": {"cost":{"input":1,"output":5,"cache_read":0.2}},
              "other/gpt-example": {"cost":{"input":99,"output":99}}
            }
          },
          "openai": {
            "models": {"gpt-example":{"cost":{"input":4,"output":16}}}
          },
          "gateway": {
            "api": "https://example.test/v1",
            "models": {
              "official-alias": {"canonical_model_id":"openai/gpt-example","cost":{"input":20,"output":30}},
              "gpt-example": {"cost":{"input":6,"output":10}},
              "private": {"cost":{"input":0,"output":0,"cache_read":0,"cache_write":-1}},
              "bad": {"cost":{"input":"NaN","output":1}}
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun prefersOpenRouterForExactCanonicalOrDeclaredAlias() {
        val expected = ModelReferencePricing(2.0, 8.0, 0.5, 3.0)
        assertEquals(expected, index.referencePricing("openai/gpt-example", null, null))
        assertEquals(expected, index.referencePricing("gpt-example", "openai", null))
        assertEquals(expected, index.referencePricing("gpt-example", null, "https://api.openai.com/v1"))
        assertEquals(expected, index.referencePricing("official-alias", null, "https://example.test/v1/"))
        assertEquals(
            ModelReferencePricing(0.0, 0.0, null, null),
            index.referencePricing("openai/gpt-example:free", "openrouter", null),
        )
        assertEquals(
            ModelReferencePricing(1.0, 5.0, 0.2, null),
            index.referencePricing("gemini-example", null, "https://generativelanguage.googleapis.com/v1beta"),
        )
    }

    @Test
    fun usesOnlyExactProviderFallbackAndKeepsExplicitZero() {
        assertEquals(
            ModelReferencePricing(6.0, 10.0, null, null),
            index.referencePricing("gpt-example", null, "https://example.test/v1"),
        )
        // 未指定已知供应商时走全局同名兜底：落到目录里第一家收录该模型的供应商（openai）
        assertEquals(
            ModelReferencePricing(4.0, 16.0, null, null),
            index.referencePricing("gpt-example", null, null),
        )
        assertEquals(
            ModelReferencePricing(4.0, 16.0, null, null),
            index.referencePricing("gpt-example", null, "https://api.openai.com.example.test/v1"),
        )
        assertEquals(
            ModelReferencePricing(0.0, 0.0, 0.0, null),
            index.referencePricing("private", "gateway", null),
        )
        assertNull(index.referencePricing("bad", "gateway", null))
    }
}
