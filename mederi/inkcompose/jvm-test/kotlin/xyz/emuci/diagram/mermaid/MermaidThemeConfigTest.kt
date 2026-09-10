package xyz.emuci.diagram.mermaid

import xyz.emuci.diagram.theme.DiagramTheme
import xyz.emuci.diagram.theme.mermaidConfigPayloadJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MermaidThemeConfigTest {

    @Test
    fun testDefaultLightThemePayload() {
        val json = mermaidConfigPayloadJson(DiagramTheme.Default)

        // 浅色 = Mermaid 内置 default 主题
        assertContains(json, "\"theme\":\"default\"")
        assertContains(json, "\"darkMode\":false")

        // themeVariables 只允许背景/字体三个无冲突标准变量
        assertContains(json, "\"background\":\"#")
        assertContains(json, "\"fontFamily\":\"")
        assertContains(json, "\"fontSize\":\"14px\"")
    }

    @Test
    fun testDarkThemePayload() {
        val json = mermaidConfigPayloadJson(DiagramTheme.Dark)

        // 深色 = Mermaid 内置 dark 主题（文字对比度由主题内部派生）
        assertContains(json, "\"theme\":\"dark\"")
        assertContains(json, "\"darkMode\":true")
    }

    @Test
    fun testNoPaletteInjection() {
        val json = mermaidConfigPayloadJson(DiagramTheme.Dark)

        // 不注入调色板变量——交给 Mermaid 内置主题派生
        assertFalse(json.contains("primaryColor"))
        assertFalse(json.contains("classText"))
        assertFalse(json.contains("stateLabelColor"))
        assertFalse(json.contains("themeCSS"))
    }
}
