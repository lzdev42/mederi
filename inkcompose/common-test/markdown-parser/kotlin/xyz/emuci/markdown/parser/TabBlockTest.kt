package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.ast.*
import xyz.emuci.markdown.parser.flavour.ExtendedFlavour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabBlockTest {

    private val parser = MarkdownParser(ExtendedFlavour)

    @Test
    fun should_parse_basic_tab_block() {
        val input = """
            |=== "Tab 1"
            |    Content of tab 1
            |
            |=== "Tab 2"
            |    Content of tab 2
        """.trimMargin()

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock != null, "Expected a TabBlock node")

        val items = tabBlock.children.filterIsInstance<TabItem>()
        assertEquals(2, items.size, "Expected 2 tab items")
        assertEquals("Tab 1", items[0].title)
        assertEquals("Tab 2", items[1].title)
    }

    @Test
    fun should_parse_single_tab() {
        val input = """
            |=== "Only Tab"
            |    Some content here
        """.trimMargin()

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock != null, "Expected a TabBlock node")

        val items = tabBlock.children.filterIsInstance<TabItem>()
        assertEquals(1, items.size)
        assertEquals("Only Tab", items[0].title)
    }

    @Test
    fun should_parse_tab_with_single_quotes() {
        val input = """
            |=== 'Tab A'
            |    Content A
            |
            |=== 'Tab B'
            |    Content B
        """.trimMargin()

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock != null, "Expected a TabBlock node")

        val items = tabBlock.children.filterIsInstance<TabItem>()
        assertEquals(2, items.size)
        assertEquals("Tab A", items[0].title)
        assertEquals("Tab B", items[1].title)
    }

    @Test
    fun should_parse_tab_content_with_markdown() {
        val input = """
            |=== "Code"
            |    ```kotlin
            |    fun hello() = println("Hello")
            |    ```
            |
            |=== "Output"
            |    Hello
        """.trimMargin()

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock != null, "Expected a TabBlock node")

        val items = tabBlock.children.filterIsInstance<TabItem>()
        assertEquals(2, items.size)
        assertEquals("Code", items[0].title)
        assertEquals("Output", items[1].title)
    }

    @Test
    fun should_not_parse_without_quotes() {
        val input = "=== No Quotes\n    Content"

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock == null, "Should not parse without quoted title")
    }

    @Test
    fun should_not_parse_with_only_two_equals() {
        val input = "== \"Not Tab\"\n    Content"

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock == null, "Should not parse with only two =")
    }

    @Test
    fun should_parse_three_tabs() {
        val input = """
            |=== "Tab 1"
            |    Content 1
            |
            |=== "Tab 2"
            |    Content 2
            |
            |=== "Tab 3"
            |    Content 3
        """.trimMargin()

        val doc = parser.parse(input)
        val tabBlock = doc.children.filterIsInstance<TabBlock>().firstOrNull()
        assertTrue(tabBlock != null, "Expected a TabBlock node")

        val items = tabBlock.children.filterIsInstance<TabItem>()
        assertEquals(3, items.size)
    }
}
