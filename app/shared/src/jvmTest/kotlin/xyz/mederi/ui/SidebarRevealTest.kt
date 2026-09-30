package xyz.mederi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SidebarReveal] 唤出/收起真值表。
 * 重点回归：显式关闭后，指针仍停在抽屉内（drawerHovered=true）也不得重新唤出（旧实现回弹的根因）。
 */
class SidebarRevealTest {

    @Test
    fun explicitCloseBlocksDrawerHoverReveal() {
        // 回弹回归用例：点导航项后指针仍停在抽屉内，不得重新唤出
        assertFalse(SidebarReveal.shouldReveal(false, true, false, true))
    }

    @Test
    fun explicitCloseBlocksEdgeHoverReveal() {
        // 显式关闭后即使贴边 hover 也不唤出（需先重新武装）
        assertFalse(SidebarReveal.shouldReveal(true, true, false, true))
    }

    @Test
    fun edgeHoverRevealsWhenNotClosed() {
        // 未关闭时贴边即展开
        assertTrue(SidebarReveal.shouldReveal(true, false, false, false))
    }

    @Test
    fun drawerHoverKeepsRevealedWhileOpen() {
        // 抽屉自身 hover 只维持开启（不能作为唤出权限）
        assertTrue(SidebarReveal.shouldReveal(false, true, false, false))
    }

    @Test
    fun interactionKeepsRevealed() {
        // 菜单/对话框打开期间维持开启
        assertTrue(SidebarReveal.shouldReveal(false, false, true, false))
    }

    @Test
    fun nothingHoveredStaysHidden() {
        // 无任何来源时保持收起
        assertFalse(SidebarReveal.shouldReveal(false, false, false, false))
    }

    @Test
    fun rearmWhenPointerLeftDrawerAndNoInteraction() {
        // 指针离开抽屉且无交互 → latch 解除
        assertTrue(SidebarReveal.shouldRearm(false, false, false))
    }

    @Test
    fun noRearmWhilePointerInsideDrawer() {
        // 指针仍在抽屉内 → 不解除
        assertFalse(SidebarReveal.shouldRearm(false, true, false))
    }

    @Test
    fun noRearmWhileInteracting() {
        // 菜单/对话框交互中 → 不解除
        assertFalse(SidebarReveal.shouldRearm(false, false, true))
    }

    @Test
    fun rearmOnEdgeHover() {
        // 用户主动贴边 → 立即解除（即使抽屉 hover 且交互中）
        assertTrue(SidebarReveal.shouldRearm(true, true, true))
    }

    @Test
    fun hideDelayIs350Millis() {
        // 宽限收起时长固定 350ms
        assertEquals(350L, SidebarReveal.HIDE_DELAY_MILLIS)
    }
}
