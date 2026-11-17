package xyz.mederi.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.ui.overview.uncachedInputTokens

/**
 * 概览用量拆分行（子任务 4）：派生口径 + 文案资源 + 跨会话累计数据源走查。
 * 纯 JVM 测试，不启动 APP；jvmTest 工作目录 = `app/shared` 模块目录。
 */
class OverviewUsageSplitTest {

    // ---- ST4-SPLIT：无缓存输入派生 ----

    @Test
    fun `uncached input subtracts cache read and write from total input`() {
        // 输入 100 命中 99、无写入 → 无缓存 1（用户口径示例）
        assertEquals(1L, uncachedInputTokens(100, 99, 0))
    }

    @Test
    fun `uncached input subtracts cache write too`() {
        // 总额 100 = 命中 99 + 写入 1 → 无缓存 0
        assertEquals(0L, uncachedInputTokens(100, 99, 1))
    }

    @Test
    fun `unreported cache counters count as zero`() {
        assertEquals(100L, uncachedInputTokens(100, null, null))
    }

    @Test
    fun `unreported input stays null instead of pretending zero`() {
        assertNull(uncachedInputTokens(null, 99, 1))
    }

    @Test
    fun `negative remainder is clamped to zero`() {
        // 供应商上报的缓存数超过总额（不一致旧记录）时不显示负数
        assertEquals(0L, uncachedInputTokens(50, 60, 0))
    }

    // ---- ST4-SPLIT：双语资源 ----

    @Test
    fun `both locales carry the new split and key labels`() {
        val zh = stringResources("values/strings.xml")
        val en = stringResources("values-en/strings.xml")
        for (key in listOf(
            "overview_usage_input_uncached",
            "overview_usage_cache_write",
            "overview_usage_cache_detail",
            "overview_usage_key_default",
            "overview_usage_key_rotation",
        )) {
            assertTrue(key in zh, "values/strings.xml 缺 %$key")
            assertTrue(key in en, "values-en/strings.xml 缺 %$key")
        }
    }

    @Test
    fun `cache read wording is unified to cache hits`() {
        assertTrue(stringResources("values/strings.xml").getValue("overview_usage_cache_read").contains("缓存命中"))
        assertEquals("Cache hits", stringResources("values-en/strings.xml").getValue("overview_usage_cache_read"))
        // 样本行同步：不再出现「缓存读取」
        assertTrue(stringResources("values/strings.xml").getValue("overview_usage_cache_sample").contains("缓存命中"))
        assertTrue(!stringResources("values/strings.xml").getValue("overview_usage_cache_sample").contains("缓存读取"))
    }

    @Test
    fun `usage statistics page is named cumulative usage in both locales`() {
        val zh = stringResources("values/strings.xml")
        assertEquals("累计用量", zh.getValue("overview_usage_stats"))
        assertEquals("累计用量", zh.getValue("overview_usage_stats_title"))
        val en = stringResources("values-en/strings.xml")
        assertEquals("Cumulative usage", en.getValue("overview_usage_stats"))
        assertEquals("Cumulative usage", en.getValue("overview_usage_stats_title"))
    }

    @Test
    fun `cost note points at openrouter reference rates`() {
        assertTrue(stringResources("values/strings.xml").getValue("overview_usage_cost_note").contains("OpenRouter"))
        assertTrue(stringResources("values-en/strings.xml").getValue("overview_usage_cost_note").contains("OpenRouter"))
    }

    // ---- ST4-GLOBAL：数据源走查（跨会话累计） ----

    @Test
    fun `usage dialogs read cross session statistics instead of session cost models`() {
        val source = moduleFile("src/commonMain/kotlin/xyz/mederi/ui/overview/OverviewUsageRow.kt").readText()
        assertTrue("usageStatistics" in source, "弹窗/紧凑页应读 viewModel.usageStatistics")
        assertTrue("loadUsageStatistics" in source, "打开统计页应触发 loadUsageStatistics()")
        assertTrue("uncachedInputTokens" in source, "拆分行应复用可测派生函数")
        assertTrue("cost.models" !in source, "统计弹窗不得再以会话 cost.models 为数据源")
    }

    @Test
    fun `view model exposes cumulative usage statistics state`() {
        val source = moduleFile("src/commonMain/kotlin/xyz/mederi/ui/WorkspaceViewModel.kt").readText()
        assertTrue("val usageStatistics: StateFlow<UsageStatistics?>" in source)
        assertTrue("fun loadUsageStatistics()" in source)
        assertTrue("getUsageStatistics()" in source)
    }

    // ---- helpers ----

    private fun stringResources(relative: String): Map<String, String> {
        val text = moduleFile("src/commonMain/composeResources/$relative").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** jvmTest 工作目录 = `app/shared`；兜底从仓库根解析，避免运行器工作目录不同时报错。 */
    private fun moduleFile(relative: String): File {
        val direct = File(relative)
        if (direct.isFile) return direct
        val repo = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("仓库根（含 settings.gradle.kts）未找到: ${System.getProperty("user.dir")}")
        return File(repo, "app/shared/$relative")
    }
}
