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

    @Test
    fun testLongMarkdownWithCodeBlockShouldNotBeExtractedAsArtifact() {
        val input = buildString {
            appendLine("这是分析前言。")
            appendLine("## 当前结构的根本问题")
            appendLine("当前结构是扁平的：")
            appendLine("```")
            appendLine("# 计划")
            appendLine("## 需要确认 / 默认决策")
            appendLine("```")
            appendLine("问题在于：")
            repeat(40) { i ->
                appendLine("$i. 详细说明第 $i 点的原因与分析...")
            }
        }
        val result = ArtifactParser.parse(input, isStreaming = false)
        println("LOG_OUTPUT: artifact=${result.artifact?.title}, lineCount=${result.artifact?.lineCount}")
        println("LOG_OUTPUT: preamble_tail=${result.preamble.takeLast(30).replace("\n", "\\n")}")
        assertNull(result.artifact, "没有 <artifact> 标签的长文本不应被提取为 Artifact，否则会切断代码块")
        assertEquals(input, result.preamble)
    }
}