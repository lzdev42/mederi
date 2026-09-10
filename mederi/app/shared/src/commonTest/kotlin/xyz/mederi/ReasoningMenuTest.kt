package xyz.mederi.core.contract.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ReasoningMenu 推理档位唯一推导链测试。
 *
 * resolve 是推理档位的唯一真理源：显示（选择器）与发送（ChatPromptInput.thinkingLevel）
 * 必须同源，多源回退会导致"界面显示推理高、实际没推理"的显示与发送不一致。
 */
class ReasoningMenuTest {

    private val levels = listOf("NONE", "LOW", "MEDIUM", "HIGH", "MAX")

    @Test
    fun memoryWinsOverDefault() {
        assertEquals("HIGH", ReasoningMenu.resolve("HIGH", levels))
        assertEquals("LOW", ReasoningMenu.resolve("LOW", levels))
        assertEquals("NONE", ReasoningMenu.resolve("NONE", levels))
    }

    @Test
    fun memoryCaseInsensitive() {
        assertEquals("HIGH", ReasoningMenu.resolve("high", levels))
        assertEquals("MEDIUM", ReasoningMenu.resolve("medium", levels))
    }

    @Test
    fun invalidMemoryFallsBackToDefault() {
        // 旧记忆不在当前菜单内（模型/供应商配置变更）→ 回退默认档，不生效
        assertEquals("MEDIUM", ReasoningMenu.resolve("ULTRA", levels))
        assertEquals("MEDIUM", ReasoningMenu.resolve("", levels))
    }

    @Test
    fun defaultIsMediumWhenPresent() {
        assertEquals("MEDIUM", ReasoningMenu.resolve(null, levels))
    }

    @Test
    fun defaultFallsBackToFirstLevel() {
        assertEquals("NONE", ReasoningMenu.resolve(null, listOf("NONE", "LOW", "HIGH")))
        assertEquals("HIGH", ReasoningMenu.resolve(null, listOf("HIGH", "LOW")))
    }

    @Test
    fun unsupportedModelReturnsNull() {
        assertNull(ReasoningMenu.resolve("HIGH", emptyList()))
        assertNull(ReasoningMenu.resolve(null, emptyList()))
    }
}
