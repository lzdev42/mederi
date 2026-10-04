package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.ui.components.parseAskItems

/**
 * parseAskItems 回归测试（ask_user 已回答显示修复的 UI 侧）。
 *
 * 覆盖两条 output 形态：
 * - 干净 JSON（formatToolResultOutput 修复后的新数据 / 真实 AskUserResult 形态）
 * - Koog 引号包裹的非法 JSON（`"{"answers":...}"`，旧 .toString() 落盘的遗留脏数据）
 *
 * 以及 parseAskItems 硬化行为：裸数组支持、合法 JSON 无 answer 不回退原始 JSON、
 * 多问 questionId 不匹配按序 1:1 回退、非 JSON 纯文本仍回退 generalAnswer。
 */
class ParseAskItemsTest {

    /** 真实 ask_user 入参（3 问：单选自由输入 / 多选 / 单选）。 */
    private val realArgs = """
        {"questions":[
          {"id":"q1","prompt":"主语言选哪个","options":["Kotlin","Java"]},
          {"id":"q2","prompt":"要启用哪些模块","options":["core","server","app"],"multiSelect":true},
          {"id":"q3","prompt":"部署到哪个环境","options":["staging","prod"]}
        ]}
    """.trimIndent()

    /** 真实 ask_user 出参（AskUserResult 的 Json.encodeToString 形态）。 */
    private val realOutput =
        """{"answers":[{"questionId":"q1","answers":["Kotlin"]},{"questionId":"q2","answers":["core","app"]},{"questionId":"q3","answers":["staging"]}]}"""

    private fun realInput(): Map<String, String> = ToolArgParser.parse(realArgs)

    @Test
    fun `clean real JSON output maps each answer to its question`() {
        val items = parseAskItems(realInput(), realOutput)

        assertEquals(3, items.size)
        assertEquals("Kotlin", items[0].answer)
        assertEquals("core, app", items[1].answer)
        assertEquals("staging", items[2].answer)
        items.forEach { assertFalse(it.isDeclined) }
    }

    @Test
    fun `koog quoted invalid JSON output never displayed as answer`() {
        // 旧 TurnExecutor .toString() 落盘的遗留脏数据：外层一对引号 + 未转义的内部引号 = 非法 JSON
        val corrupted = "\"$realOutput\""

        val items = parseAskItems(realInput(), corrupted)

        assertEquals(3, items.size)
        items.forEach { item ->
            // 核心断言：绝不把整串原始 JSON（无论带引号与否）显示为某个问题的答案
            assertNull(item.answer, "问题 ${item.id} 的答案不应是 Koog 脏数据串，实际：${item.answer}")
            assertFalse(item.answer == corrupted || item.answer == realOutput)
            assertFalse(item.answer?.contains("questionId") == true, "原始 JSON 串泄漏进了答案：${item.answer}")
        }
    }

    @Test
    fun `bare array output is parsed by questionId`() {
        val output = """[{"questionId":"q1","answers":["Kotlin"]},{"questionId":"q2","answers":["core","app"]}]"""

        val items = parseAskItems(realInput(), output)

        assertEquals("Kotlin", items[0].answer)
        assertEquals("core, app", items[1].answer)
    }

    @Test
    fun `valid JSON without any answer does not fall back to raw JSON`() {
        // 合法 JSON 但 answers 为空 → 各问题答案为 null，绝不显示原始 JSON
        val items = parseAskItems(realInput(), """{"answers":[]}""")
        items.forEach { assertNull(it.answer, "空 answers 时不允许回退原始 JSON：${it.answer}") }

        // 合法 JSON 但结构无关 → 同样不回退
        val items2 = parseAskItems(realInput(), """{"foo":"bar"}""")
        items2.forEach { assertNull(it.answer, "无关 JSON 不允许回退为答案：${it.answer}") }
    }

    @Test
    fun `mismatched questionIds with equal count fall back to positional mapping`() {
        // 多问、questionId 全部不匹配、答案数与问题数一致 → 按序一一对应（最后回退）
        val output = """{"answers":[{"questionId":"x1","answers":["one"]},{"questionId":"x2","answers":["two"]},{"questionId":"x3","answers":["three"]}]}"""

        val items = parseAskItems(realInput(), output)

        assertEquals("one", items[0].answer)
        assertEquals("two", items[1].answer)
        assertEquals("three", items[2].answer)
    }

    @Test
    fun `non-json plain text output still falls back as general answer`() {
        // 自定义工具的自由文本返回仍保留 generalAnswer 回退（单问进 Q1）
        val singleInput = ToolArgParser.parse(
            """{"questions":[{"id":"q1","prompt":"确认继续吗","options":["是","否"]}]}"""
        )

        val items = parseAskItems(singleInput, "looks fine, proceed")

        assertEquals("looks fine, proceed", items[0].answer)
    }

    @Test
    fun `declined output marks items declined without answer`() {
        val items = parseAskItems(realInput(), "User declined to answer the questions.")

        assertEquals(3, items.size)
        items.forEach {
            assertTrue(it.isDeclined)
            assertNull(it.answer)
        }
    }
}
