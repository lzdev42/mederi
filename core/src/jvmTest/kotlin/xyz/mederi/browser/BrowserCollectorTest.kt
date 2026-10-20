package xyz.mederi.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserCollectorTest {

    @Test
    fun testCollectorAddListAndToMarkdown() {
        val collector = BrowserCollector()
        assertEquals(0, collector.size())
        assertTrue(collector.list().isEmpty())

        val count1 = collector.add(
            BrowserCollectedItem(
                url = "https://example.com/item1",
                siteName = "Example 1",
                content = "# Title\nDetails here"
            )
        )
        assertEquals(1, count1)
        assertEquals(1, collector.size())

        val count2 = collector.add(
            BrowserCollectedItem(
                url = "https://example.com/item2",
                siteName = "Example 2",
                content = "![image](https://example.com/img.png)\nSome description"
            )
        )
        assertEquals(2, count2)
        assertEquals(2, collector.size())

        val items = collector.list()
        assertEquals("Example 1", items[0].siteName)
        assertEquals("https://example.com/item1", items[0].url)
        assertEquals("Example 2", items[1].siteName)
        assertEquals("![image](https://example.com/img.png)\nSome description", items[1].content)

        val markdown = collector.toMarkdown()
        assertTrue(markdown.contains("### [1] Example 1"))
        assertTrue(markdown.contains("https://example.com/item1"))
        assertTrue(markdown.contains("### [2] Example 2"))
        assertTrue(markdown.contains("![image](https://example.com/img.png)"))
    }

    @Test
    fun testCollectInfoToolExecution() = runBlocking {
        val collector = BrowserCollector()
        val fakeControl = FakeBrowserControl(
            currentUrl = "https://mederi.xyz/page",
            title = "Mederi Documentation"
        )
        val tool = CollectInfoTool(collector = collector, control = fakeControl)

        // 1. 尝试提交空内容 -> 应该失败并提示
        val failResult = tool.execute(CollectInfoArgs(content = "   "))
        assertTrue(failResult.contains("collect=FAIL"))
        assertEquals(0, collector.size())

        // 2. 正常提交，省略 url 和 site_name -> 自动使用页面当前 url 与 title
        val okResult = tool.execute(
            CollectInfoArgs(content = "## Summary\nFound 10 projects.")
        )
        assertTrue(okResult.contains("collect=ok: total 1 items collected"))
        assertEquals(1, collector.size())

        val item1 = collector.list().first()
        assertEquals("https://mederi.xyz/page", item1.url)
        assertEquals("Mederi Documentation", item1.siteName)
        assertEquals("## Summary\nFound 10 projects.", item1.content)

        // 3. 显式指定 url 与 site_name
        val okResult2 = tool.execute(
            CollectInfoArgs(
                url = "https://github.com/mederi/mederi",
                site_name = "GitHub Mederi",
                content = "Stars: 1000"
            )
        )
        assertTrue(okResult2.contains("collect=ok: total 2 items collected"))
        assertEquals(2, collector.size())
        val item2 = collector.list()[1]
        assertEquals("https://github.com/mederi/mederi", item2.url)
        assertEquals("GitHub Mederi", item2.siteName)
        assertEquals("Stars: 1000", item2.content)
    }
}
