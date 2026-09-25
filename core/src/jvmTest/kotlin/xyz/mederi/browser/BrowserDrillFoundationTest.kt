package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.browser.bidi.KeyboardKey
import xyz.mederi.browser.bidi.OperationResult
import xyz.mederi.browser.drill.DrillScriptExtractor
import xyz.mederi.browser.drill.models.DrillScript
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ST0 基础层验证：Drill 数据模型 / 配方加载 / BrowserControl 新契约 default 兜底。
 */
class BrowserDrillFoundationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // ── 1. DrillScript 反序列化 ──

    @Test
    fun `DrillScript deserializes full script with all action kinds`() {
        val script = json.decodeFromString<DrillScript>(FULL_DRILL_JSON)

        assertEquals("抓取热门话题", script.task_name)
        assertEquals(2, script.loop_count)
        assertEquals(3000L, script.repeat_interval_ms)
        assertEquals(7, script.steps.size)

        // navigate
        assertEquals("navigate", script.steps[0].action)
        assertEquals("https://example.com", script.steps[0].url)

        // click（selector_options / first）
        assertEquals("click", script.steps[1].action)
        assertEquals("getByRole", script.steps[1].playwright_api)
        assertEquals(mapOf("name" to "提交", "exact" to "true"), script.steps[1].selector_options)
        assertEquals(true, script.steps[1].first)

        // fill
        assertEquals("fill", script.steps[2].action)
        assertEquals("test_user", script.steps[2].action_value)

        // extract_list
        assertEquals("extract_list", script.steps[3].action)
        assertTrue(script.steps[3].record_result)
        assertEquals(mapOf("title" to "h2", "url" to "a"), script.steps[3].record_fields)

        // ask_ai（content_from 单值 → 单元素列表）
        assertEquals("ask_ai", script.steps[4].action)
        assertEquals(listOf("title"), script.steps[4].content_from)
        assertEquals("ai_", script.steps[4].ai_result_key)

        // branch
        assertEquals("branch", script.steps[5].action)
        val branches = script.steps[5].branches
        assertNotNull(branches)
        assertEquals(1, branches.size)
        assertEquals("exists:.next", branches[0].condition)
        assertEquals("click", branches[0].steps[0].action)
        assertNotNull(script.steps[5].default_steps)
        assertEquals(1, script.steps[5].default_steps!!.size)

        // abort_item
        assertEquals("abort_item", script.steps[6].action)
        assertEquals("{judged} == 不合适", script.steps[6].condition)
        assertEquals("close_tab", script.steps[6].then)
    }

    // ── 2. DrillScriptExtractor ──

    @Test
    fun `extractDrillJson pulls fenced json out of chinese prose`() {
        val markdown = """
            本技能用于抓取热门话题。
            操作说明：先打开页面，再等待加载。
            ```json
            {
              "task_name": "sample_task",
              "steps": [{"step_name": "打开", "action": "navigate", "url": "https://example.com"}]
            }
            ```
            以上是完整脚本。
        """.trimIndent()

        val extracted = DrillScriptExtractor.extractDrillJson(markdown)

        assertNotNull(extracted)
        assertTrue(extracted.startsWith("{"))
        assertTrue(extracted.contains("\"task_name\": \"sample_task\""))
        // 无围栏的文本返回 null
        assertNull(DrillScriptExtractor.extractDrillJson("没有任何代码块"))
        assertNull(DrillScriptExtractor.extractDrillJson(""))
    }

    // ── 3. RecipeStore ──

    @Test
    fun `RecipeStore loads skill markdown sections`() {
        val dir = File.createTempFile("recipe_test", "").apply { delete(); mkdirs() }
        try {
            File(dir, "sample.md").writeText(SAMPLE_SKILL_MD)

            val store = RecipeStore(dir)

            val recipe = store.load("sample")
            assertNotNull(recipe)
            assertEquals("sample", recipe.id)
            assertEquals("示例技能", recipe.description)
            assertTrue(recipe.operatorContent.isNotBlank())
            assertTrue(recipe.operatorContent.contains("第一步"))
            assertTrue(recipe.judgeRules.isNotBlank())
            assertTrue(recipe.judgeRules.contains("包含目标内容"))
            assertNotNull(recipe.drillScriptJson)
            assertTrue(recipe.drillScriptJson.startsWith("{"))
            assertTrue(recipe.drillScriptJson.contains("\"task_name\": \"sample_task\""))

            // 不存在的 skill → null
            assertNull(store.load("不存在"))

            // listAll 返回全部并按 id 排序
            val all = store.listAll()
            assertEquals(1, all.size)
            assertEquals("sample", all[0].id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `RecipeStore listAll returns empty for missing dir`() {
        val missing = File(File.createTempFile("recipe_test_absent", "").apply { delete(); mkdirs() }, "nested_missing")
        // missing 目录不存在
        assertEquals(emptyList(), RecipeStore(missing).listAll())
    }

    // ── 4. BrowserControl 新契约 default 兜底 ──

    @Test
    fun `FakeBrowserControl implements new contract without throwing`() {
        runBlocking {
            val fake = FakeBrowserControl()
            fake.registerJs("1+1", "2")

            assertEquals("2", fake.evaluateJavascript("1+1"))
            assertNotNull(fake.locator(".item"))

            val opened = fake.openTab()
            assertTrue(opened.success)
            assertEquals(2, fake.listTabs().size)

            val closed = fake.closeTab("tab-2")
            assertTrue(closed.success)
            assertEquals(listOf("tab-1"), fake.listTabs())

            val selected = fake.selectTab("tab-1")
            assertTrue(selected.success)
            assertEquals("tab-1", fake.selectedTab)

            assertTrue(fake.uploadFile("ref-id", listOf("/tmp/a.txt")).success)
            assertTrue(fake.scrollByCoordinates(0, 0, 10, 20).success)
            assertTrue(fake.waitFor(".loaded", 1000).success)

            // locator 脚本化行为
            fake.setElementText(".title", "标题甲")
            fake.setElement(".hidden", FakeElement(text = "藏", visible = false, count = 0))
            val loc = fake.locator(".title")
            assertEquals("标题甲", loc.getText())
            assertTrue(loc.isVisible())
            assertTrue(loc.click().success)
            assertEquals(0, fake.locator(".hidden").count())
            assertTrue(!fake.locator(".hidden").isVisible())
        }
    }

    @Test
    fun `minimal control uses default unsupported implementations`() {
        runBlocking {
            val minimal = MinimalControl()

            assertIs<UnsupportedOperationException>(catchUnsupported { minimal.evaluateJavascript("1") })
            assertIs<UnsupportedOperationException>(catchUnsupported { minimal.locator(".x") })
            assertIs<UnsupportedOperationException>(catchUnsupported { minimal.openTab() })
            assertIs<UnsupportedOperationException>(catchUnsupported { minimal.listTabs() })
            assertIs<UnsupportedOperationException>(catchUnsupported { minimal.waitFor(".x") })
        }
    }

    private suspend fun catchUnsupported(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    /**
     * 仅实现 BrowserControl 原有抽象方法的极简实现——用于证明新契约 default 生效且
     * 既有实现不需要被迫实现新方法（向后兼容）。
     */
    private class MinimalControl : BrowserControl {
        override suspend fun start() = Unit
        override suspend fun close() = Unit
        override suspend fun getCurrentUrl(): String = ""
        override suspend fun getTitle(): String = ""
        override suspend fun navigate(url: String): OperationResult = OperationResult.Acknowledged
        override suspend fun click(elementRef: String): OperationResult = OperationResult.Acknowledged
        override suspend fun clickByCoordinates(x: Int, y: Int): OperationResult = OperationResult.Acknowledged
        override suspend fun type(elementRef: String, text: String): OperationResult = OperationResult.Acknowledged
        override suspend fun scroll(elementRef: String, deltaX: Int, deltaY: Int): OperationResult = OperationResult.Acknowledged
        override suspend fun press(key: KeyboardKey): OperationResult = OperationResult.Acknowledged
        override suspend fun snapshot(): PageSnapshot = PageSnapshot("", "", "", null)
        override suspend fun screenshot(): ByteArray? = null
    }

    private companion object {
        val FULL_DRILL_JSON = """
            {
              "task_name": "抓取热门话题",
              "loop_count": 2,
              "repeat_interval_ms": 3000,
              "steps": [
                {"step_name": "打开首页", "action": "navigate", "url": "https://example.com"},
                {"step_name": "点击提交", "action": "click", "playwright_api": "getByRole", "selector_type": "button", "selector_value": "提交", "selector_options": {"name": "提交", "exact": "true"}, "first": true},
                {"step_name": "填写用户名", "action": "fill", "selector_type": "input", "selector_value": "#username", "action_value": "test_user"},
                {"step_name": "抽取列表", "action": "extract_list", "selector_type": "css", "selector_value": ".item", "record_result": true, "record_fields": {"title": "h2", "url": "a"}},
                {"step_name": "AI判定", "action": "ask_ai", "store_as": "judged", "instruction": "判断内容是否合适", "content_from": "title", "ai_result_key": "ai_"},
                {"step_name": "条件分支", "action": "branch", "branches": [{"condition": "exists:.next", "steps": [{"step_name": "点击下一页", "action": "click", "selector_value": ".next"}]}], "default_steps": [{"step_name": "完成", "action": "navigate", "url": "about:blank"}]},
                {"step_name": "中止条目", "action": "abort_item", "condition": "{judged} == 不合适", "then": "close_tab"}
              ]
            }
        """.trimIndent()

        val SAMPLE_SKILL_MD = """
            ---
            name: sample
            description: 示例技能
            ---

            ## 操作规则
            第一步：打开目标页面。
            第二步：等待页面加载完成。

            ## Drill
            这是一段中文说明，描述该流程的意图。
            ```json
            {"task_name": "sample_task", "steps": [{"step_name": "打开", "action": "navigate", "url": "https://example.com"}]}
            ```

            ## 判定规则
            - 页面包含目标内容 → 成功
            - 否则 → 失败
        """.trimIndent()
    }
}