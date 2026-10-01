package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mederi.ui.components.SubagentTerminalKind
import xyz.mederi.ui.components.subagentTerminalKind

/**
 * 子代理终态映射（`subagentTerminalKind`）的判定锁定。
 *
 * 这是一条**回归防护测试**：历史上 UI 只判 ERROR/FAILED、其余一律走「已完成」分支，
 * 导致用户手动取消（STOPPED）的子代理被显示成「已完成」。核心断言见
 * [stoppedMapsToStoppedNotOther]。
 */
class SubagentTerminalKindTest {

    /** 回归防护核心：STOPPED（手动取消）必须命中 STOPPED，绝不能落 else/OTHER 被当成「已完成」。 */
    @Test
    fun stoppedMapsToStoppedNotOther() {
        assertEquals(SubagentTerminalKind.STOPPED, subagentTerminalKind("STOPPED"))
        assertEquals(SubagentTerminalKind.STOPPED, subagentTerminalKind("stopped"))
        // 大小写不敏感 + 忽略首尾空白，沿用既有 equals(ignoreCase = true) 行为，不收窄
        assertEquals(SubagentTerminalKind.STOPPED, subagentTerminalKind(" Stopped "))
        // 反向断言：STOPPED 与 OTHER / COMPLETED 三者互不等价
        assertEquals(false, subagentTerminalKind("STOPPED") == SubagentTerminalKind.OTHER)
        assertEquals(false, subagentTerminalKind("STOPPED") == SubagentTerminalKind.COMPLETED)
    }

    @Test
    fun completedMapsToCompleted() {
        assertEquals(SubagentTerminalKind.COMPLETED, subagentTerminalKind("COMPLETED"))
        assertEquals(SubagentTerminalKind.COMPLETED, subagentTerminalKind("completed"))
        assertEquals(SubagentTerminalKind.COMPLETED, subagentTerminalKind("COMPLETED "))
    }

    @Test
    fun errorMapsToError() {
        assertEquals(SubagentTerminalKind.ERROR, subagentTerminalKind("ERROR"))
        assertEquals(SubagentTerminalKind.ERROR, subagentTerminalKind("error"))
    }

    /** FAILED 是历史防御性取值（core 不产生，但 UI 侧历史上写过 equals("FAILED")），保留兼容。 */
    @Test
    fun failedMapsToErrorForLegacyCompatibility() {
        assertEquals(SubagentTerminalKind.ERROR, subagentTerminalKind("FAILED"))
        assertEquals(SubagentTerminalKind.ERROR, subagentTerminalKind("failed"))
    }

    /** RUNNING 是非终态；空串/纯空白/乱串是未知取值——都落 OTHER 兜底，绝不当成 COMPLETED。 */
    @Test
    fun nonTerminalAndUnknownStatusesMapToOther() {
        assertEquals(SubagentTerminalKind.OTHER, subagentTerminalKind("RUNNING"))
        assertEquals(SubagentTerminalKind.OTHER, subagentTerminalKind("running"))
        assertEquals(SubagentTerminalKind.OTHER, subagentTerminalKind(""))
        assertEquals(SubagentTerminalKind.OTHER, subagentTerminalKind("   "))
        assertEquals(SubagentTerminalKind.OTHER, subagentTerminalKind("WHATEVER"))
    }
}
