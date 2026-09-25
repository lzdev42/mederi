package xyz.mederi

import xyz.mederi.util.ArtifactParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactParserTest {

    @Test
    fun testCompletedArtifactExtraction() {
        val input = """
            为了解决显示问题，我生成了以下文档：
            
            <artifact identifier="test-doc" title="架构设计方案" type="text/markdown">
            # 架构设计方案
            
            这是方案正文的第一段。
            </artifact>
            
            请查阅上述文档。
        """.trimIndent()

        val result = ArtifactParser.parse(input, isStreaming = false)
        assertEquals("为了解决显示问题，我生成了以下文档：", result.preamble.trim())
        assertNotNull(result.artifact)
        assertEquals("test-doc", result.artifact.identifier)
        assertEquals("架构设计方案", result.artifact.title)
        assertTrue(result.artifact.isCompleted)
        assertTrue(result.artifact.content.contains("# 架构设计方案"))
        assertEquals("请查阅上述文档。", result.postscript.trim())
    }

    @Test
    fun testStreamingUnclosedArtifactExtraction() {
        val input = """
            我正在为您生成排版报告：
            
            <artifact title="传统蒙古文排版评估">
            # 传统蒙古文排版评估
            
            正在实时流式输出中...
        """.trimIndent()

        val result = ArtifactParser.parse(input, isStreaming = true)
        assertEquals("我正在为您生成排版报告：", result.preamble.trim())
        assertNotNull(result.artifact)
        assertEquals("传统蒙古文排版评估", result.artifact.title)
        assertFalse(result.artifact.isCompleted)
        assertTrue(result.artifact.content.contains("正在实时流式输出中..."))
        assertEquals("", result.postscript)
    }

    @Test
    fun testArtifactWithoutTitleAttribute() {
        val input = """
            <artifact>
            # 自动提取的主标题
            
            正文内容...
            </artifact>
        """.trimIndent()

        val result = ArtifactParser.parse(input, isStreaming = false)
        assertNotNull(result.artifact)
        assertEquals("自动提取的主标题", result.artifact.title)
        assertTrue(result.artifact.isCompleted)
    }

    @Test
    fun testNormalShortTextShouldNotExtract() {
        val input = "你好，请问有什么可以帮助您的？"
        val result = ArtifactParser.parse(input, isStreaming = false)
        assertNull(result.artifact)
        assertEquals(input, result.preamble)
    }
}