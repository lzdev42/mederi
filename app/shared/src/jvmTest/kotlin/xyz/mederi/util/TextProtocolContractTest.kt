package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 跨层文本协议的**契约锁**（core ↔ app/shared）。
 *
 * core 与 app/shared commonMain 无法共享常量（模块边界），故两侧各持一份：
 * - 生成侧 = `xyz.mederi.prompt.SteeringPrompt.wrap(text)`（core，工具边界 steering 注入文本）
 * - 剥离侧 = `PromptComposer.sanitizeUserVisibleText(...)`（app/shared，UI 渲染/回退编辑前清洗）
 *
 * 本测试把「core 生成 → shared 剥离 → 还原为原文」固化为机器校验：
 * core 改包装话术而剥离侧没跟上时，测试必须失败（而不是运行期 UI 漏出系统提示）。
 */
class TextProtocolContractTest {

    private val sampleText = "任务卡死了，你重调一下"

    @Test
    fun coreWrappedTextIsStrippedBackToOriginalBySanitizer() {
        val wrapped = xyz.mederi.prompt.SteeringPrompt.wrap(sampleText)
        assertEquals(sampleText, PromptComposer.sanitizeUserVisibleText(wrapped), "生成→剥离闭环必须还原为原文")
    }

    @Test
    fun parseHandlesWrappedMessageWithPastedSections() {
        // 现实场景：steer 消息的 text = PromptComposer.compose(主指令, 粘贴文本)，
        // core 把**整体**包进 <user_intervention>（SteeringPrompt.wrap），UI 剥离后
        // parse 必须还原主指令与粘贴文本。回归点：parse 的 matches 在 sanitize 后的文本上
        // find，索引必须作用在同一文本上，否则包装前缀会让偏移错位/越界。
        val inner = sampleText + "\n----------------------------------------\n" +
            "The following are text sections pasted by the user for context:\n\n" +
            "<pasted_text index=\"1\" lines=\"1\" chars=\"6\">\n大段粘贴内容\n</pasted_text>"
        val wrapped = xyz.mederi.prompt.SteeringPrompt.wrap(inner)
        val parsed = PromptComposer.parse(wrapped)

        assertEquals(sampleText, parsed.instruction.trim(), "主指令应为剥离后的用户正文")
        assertEquals(1, parsed.pastedTexts.size, "包装内的粘贴段必须被还原")
        assertEquals("大段粘贴内容", parsed.pastedTexts[0].text)
    }
}
