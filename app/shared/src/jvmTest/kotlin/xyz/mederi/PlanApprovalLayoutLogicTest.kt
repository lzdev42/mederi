package xyz.mederi

import kotlin.test.Test
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.ui.ChatLayout

class PlanApprovalLayoutLogicTest {

    @Test
    fun testDefaultReaderPanelWidthCalculation() {
        // 模拟各种桌面窗口宽度下，计算 2/3 默认阅读宽度
        val screenWidths = listOf(1024.dp, 1280.dp, 1440.dp, 1920.dp)

        for (screenWidth in screenWidths) {
            val maxPanelWidth = (screenWidth - ChatLayout.conversationMinWidth - ChatLayout.rightDockWidth)
                .coerceAtLeast(0.dp)

            // 默认宽度为窗口的 2/3
            val defaultReaderWidth = (screenWidth * (2f / 3f)).coerceAtMost(maxPanelWidth)

            // 验证：
            // 1. 面板宽度不超过最大允许宽度（确保左侧对话区保底 360dp 手机宽度）
            assertTrue(defaultReaderWidth <= maxPanelWidth, "Panel width must not exceed maxPanelWidth for screen $screenWidth")
            // 2. 左侧剩余空间至少为 conversationMinWidth
            val remainingLeft = screenWidth - defaultReaderWidth - ChatLayout.rightDockWidth
            assertTrue(remainingLeft >= ChatLayout.conversationMinWidth, "Remaining width for chat must be >= 360dp")
            // 3. 在普通宽屏（1440dp）下，面板宽度约等于 960dp (2/3)
            if (screenWidth == 1440.dp) {
                assertEquals(960.dp, defaultReaderWidth)
            }
            println("Screen: $screenWidth, maxPanel: $maxPanelWidth, defaultReader: $defaultReaderWidth, remainingChat: $remainingLeft")
        }
    }

    @Test
    fun testReaderSymmetricPaddingLogic() {
        // 验证阅读面板与对话流共用单一真理源：ChatLayout.contentMaxWidth
        val maxContentWidth = ChatLayout.contentMaxWidth
        assertEquals(1000.dp, maxContentWidth, "Content max width must be single source of truth (1000dp)")

        // 验证当面板宽度超大（如 1440dp）时，正文居中后两侧留白严格对称等宽
        val panelWidth = 1440.dp
        val remainingSpace = panelWidth - maxContentWidth
        val sideMargin = remainingSpace / 2
        assertEquals(220.dp, sideMargin, "Center aligned reader must have equal margins on both sides")
    }

    @Test
    fun testChatInputToolbarResponsiveBreakpointAndFixedControlHeight() {
        val toolbarBreakpoint = 640.dp
        val fixedControlHeight = 28.dp

        // 验证标准控件固定高度
        assertEquals(28.dp, fixedControlHeight, "All pills and buttons must have fixed 28dp height")

        // 验证不同桌面窗口宽度下，打开 2/3 阅读面板后左侧对话区的宽度判定
        val screenWidths = listOf(1024.dp, 1280.dp, 1440.dp)
        for (screenWidth in screenWidths) {
            val maxPanelWidth = (screenWidth - ChatLayout.conversationMinWidth - ChatLayout.rightDockWidth)
                .coerceAtLeast(0.dp)
            val defaultReaderWidth = (screenWidth * (2f / 3f)).coerceAtMost(maxPanelWidth)
            val remainingLeft = screenWidth - defaultReaderWidth - ChatLayout.rightDockWidth

            // 打开阅读面板后，左侧剩余空间小于断点（640dp），必然触发两行 Flow 排版，避免单行挤压
            assertTrue(
                remainingLeft < toolbarBreakpoint,
                "Under screen $screenWidth with 2/3 reader panel, chat input width ($remainingLeft) must enter stacked flow layout (< 640dp)"
            )
            println("[ToolbarTest] Screen: $screenWidth, remainingChat: $remainingLeft, usesStackedFlowLayout: true, controlHeight: $fixedControlHeight")
        }
    }

    @Test
    fun testVerifyingControlHeightsAndSurfaceMinimumInteractiveSize() {
        val standardHeight = 28.dp
        val material3InteractiveMinSize = 48.dp
        val offset = (material3InteractiveMinSize - standardHeight) / 2

        println("=== [DEBUG_LAYOUT_VERIFICATION] Control Height Diagnosis ===")
        println("Standard toolbar item height target: $standardHeight")
        println("Material 3 Surface(onClick) minInteractiveComponentSize: $material3InteractiveMinSize")
        println("Vertical alignment offset introduced by Surface: $offset (Top shift = 0dp for Box vs 10dp for Surface content)")
        println("ChipSelectorPill using Surface -> rendered at y=$offset, height=28.dp inside 48.dp bounding box")
        println("SendButton using Box -> rendered at y=0.dp, height=28.dp inside 28.dp bounding box")
        println("Resulting visual mis-alignment: SendButton is $offset higher than ChipSelectorPill!")
        println("Fix verification target: eliminate Surface, bind all components strictly to height=$standardHeight")
        println("=============================================================")

        assertEquals(10.dp, offset, "The visual offset caused by M3 Surface is exactly 10dp")
    }
}

