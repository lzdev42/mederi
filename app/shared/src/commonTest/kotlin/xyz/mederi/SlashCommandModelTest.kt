package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.ui.components.command.CommandGroup
import xyz.mederi.ui.components.command.SlashCommandItem
import xyz.mederi.ui.components.command.SlashCommandRegistry

class SlashCommandModelTest {

    @Test
    fun builtinCommandsContainsSystemCommands() {
        val builtins = SlashCommandRegistry.BUILTIN_COMMANDS
        println("[TEST LOG] Builtin commands count: ${builtins.size}, ids: ${builtins.map { it.id }}")
        assertTrue(builtins.isNotEmpty(), "Builtin commands should not be empty")
        assertTrue(builtins.any { it.id == "clear" })
        assertTrue(builtins.any { it.id == "compact" })
        assertTrue(builtins.any { it.id == "plan" })
        assertTrue(builtins.any { it.id == "skill" })
    }

    @Test
    fun customCommandCreationMatchesTemplate() {
        val sampleItem = SlashCommandItem(
            id = "spec",
            label = "Spec",
            group = CommandGroup.COMMAND,
            description = "根据需求细化规范文档",
            insertText = "/spec ",
            keywords = listOf("spec", "规范")
        )
        assertEquals("spec", sampleItem.id)
        assertEquals("Spec", sampleItem.label)
        assertEquals(CommandGroup.COMMAND, sampleItem.group)
        assertEquals("/spec ", sampleItem.insertText)
    }

    @Test
    fun groupingMaintainsCorrectOrder() {
        val testItems = listOf(
            SlashCommandItem(id = "p1", label = "P1", group = CommandGroup.PLUGIN, description = "Plugin 1"),
            SlashCommandItem(id = "c1", label = "C1", group = CommandGroup.COMMAND, description = "Command 1")
        )
        val grouped = SlashCommandRegistry.groupItems(testItems)
        val groups = grouped.keys.toList()

        assertEquals(CommandGroup.COMMAND, groups[0])
        assertEquals(CommandGroup.PLUGIN, groups[1])
    }

    @Test
    fun dynamicSkillsMergeIntoPluginGroup() {
        val fakeSkills = listOf(
            SkillItem(name = "custom-analysis", description = "Run deep data analysis")
        )
        val allWithSkills = SlashCommandRegistry.getAllCommands(fakeSkills)
        val custom = allWithSkills.find { it.id == "custom-analysis" }

        assertNotNull(custom)
        assertEquals("custom-analysis", custom.label)
        assertEquals(CommandGroup.PLUGIN, custom.group)
        assertEquals("/skill custom-analysis ", custom.insertText)
    }

    @Test
    fun queryAtCursorSupportsBothStartAndEnd() {
        // 1. 开头输入单字 "c"（输入法联想模式）
        val qStartLetter = SlashCommandRegistry.findCommandQueryAtCursor("c", 1)
        println("[TEST LOG] Query for 'c' at cursor 1: $qStartLetter")
        assertNotNull(qStartLetter)
        assertEquals("c", qStartLetter.query)
        assertEquals(0 until 1, qStartLetter.range)
        assertEquals(false, qStartLetter.isSlashMode)

        // 2. 开头输入 "/c"
        val qStartSlash = SlashCommandRegistry.findCommandQueryAtCursor("/c", 2)
        println("[TEST LOG] Query for '/c' at cursor 2: $qStartSlash")
        assertNotNull(qStartSlash)
        assertEquals("c", qStartSlash.query)
        assertEquals(0 until 2, qStartSlash.range)
        assertEquals(true, qStartSlash.isSlashMode)

        // 3. 句子末尾输入 "/s"（解决用户反馈：/快捷命令不能只在开头，末尾也支持）
        val endText = "帮我分析代码 /s"
        val qEndSlash = SlashCommandRegistry.findCommandQueryAtCursor(endText, endText.length)
        println("[TEST LOG] Query for '$endText' at cursor ${endText.length}: $qEndSlash")
        assertNotNull(qEndSlash)
        assertEquals("s", qEndSlash.query)
        assertEquals(7 until 9, qEndSlash.range)
        assertEquals(true, qEndSlash.isSlashMode)

        // 4. 句子末尾仅输入 "/"，唤起全量菜单
        val endOnlySlash = "帮我分析代码 /"
        val qEndOnlySlash = SlashCommandRegistry.findCommandQueryAtCursor(endOnlySlash, endOnlySlash.length)
        println("[TEST LOG] Query for '$endOnlySlash' at cursor ${endOnlySlash.length}: $qEndOnlySlash")
        assertNotNull(qEndOnlySlash)
        assertEquals("", qEndOnlySlash.query)
        assertEquals(7 until 8, qEndOnlySlash.range)
        assertEquals(true, qEndOnlySlash.isSlashMode)

        // 5. 解决输入冲突：转义 \/skill 不触发联想菜单
        val escapedText = "\\/skill"
        val qEscaped = SlashCommandRegistry.findCommandQueryAtCursor(escapedText, escapedText.length)
        println("[TEST LOG] Query for escaped '$escapedText': $qEscaped")
        assertNull(qEscaped, "Escaped \\/skill must not trigger command query")

        // 6. 解决输入冲突：URL 路径中的斜杠（如 http://example.com/api）不触发
        val urlText = "http://example.com/api"
        val qUrl = SlashCommandRegistry.findCommandQueryAtCursor(urlText, urlText.length)
        println("[TEST LOG] Query for URL '$urlText': $qUrl")
        assertNull(qUrl, "URL path slash must not trigger command query")

        // 7. 解决输入冲突：句子中间普通英文（如 'hello can'）不误触
        val normalMid = "hello can you"
        val qNormalMid = SlashCommandRegistry.findCommandQueryAtCursor(normalMid, 9)
        println("[TEST LOG] Query for middle text '$normalMid': $qNormalMid")
        assertNull(qNormalMid, "Middle plain english word must not trigger command query")
    }

    @Test
    fun atomicDeletionSupportsBothStartAndEnd() {
        val fakeSkills = listOf(
            SkillItem(name = "custom-analysis", description = "Run deep data analysis")
        )
        val allCommands = SlashCommandRegistry.getAllCommands(fakeSkills)

        // 1. 开头命令删除："/clear "
        val tokenAtStart = SlashCommandRegistry.findCommandTokenAtCursor("/clear ", 7, allCommands)
        println("[TEST LOG] Token at start for '/clear ': $tokenAtStart")
        assertNotNull(tokenAtStart)
        assertEquals(0, tokenAtStart.start)
        assertEquals(7, tokenAtStart.end)

        // 2. 末尾命令删除："帮我分析代码 /compact "（光标在 16 处按退格，整词删除 /compact ）
        val textWithEndCommand = "帮我分析代码 /compact "
        val tokenAtEnd = SlashCommandRegistry.findCommandTokenAtCursor(textWithEndCommand, 16, allCommands)
        println("[TEST LOG] Token at end for '$textWithEndCommand': $tokenAtEnd")
        assertNotNull(tokenAtEnd)
        assertEquals(7, tokenAtEnd.start)
        assertEquals(16, tokenAtEnd.end)
        assertEquals("/compact ", tokenAtEnd.token)

        // 3. 转义的命令不识别为 Token："\\/skill "
        val escapedText = "\\/skill "
        val tokenEscaped = SlashCommandRegistry.findCommandTokenAtCursor(escapedText, escapedText.length, allCommands)
        println("[TEST LOG] Token for escaped '$escapedText': $tokenEscaped")
        assertNull(tokenEscaped)
    }
}
