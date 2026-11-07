package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 输入模式分流单测：验证选区手势结束后的菜单自动弹出（toolbarRequestKey bump）
 * 仅在触屏输入时发生；鼠标输入（[MarkdownSelectionState.isMouseInput] = true）框选/选词
 * 结束不 bump，菜单不自动弹出（改由右键 [MarkdownSelectionController.showContextMenuAt] 触发）。
 */
@OptIn(ExperimentalTestApi::class)
class SelectionInputModeTest {

    @Test
    fun touch_input_finish_bumps_toolbar_request_key() = runComposeUiTest {
        val block = inlineTextBlock(id = 1, text = "selectable text", width = 260f, height = 20f)
        var controllerRef: MarkdownSelectionController? = null

        setContent {
            val controller = rememberMarkdownSelectionController(
                coroutineScope = rememberCoroutineScope(),
                textMeasurer = rememberTextMeasurer(),
            )
            LaunchedEffect(controller) {
                controller.updateIndex(listOf(block))
                controller.state.range = SelectionRange(
                    start = SelectionAnchor(blockStableId = 1, charInBlock = 1),
                    end = SelectionAnchor(blockStableId = 1, charInBlock = 8),
                )
                controllerRef = controller
            }
            // extractSelectedText 只依赖 index，不依赖坐标注册，无需 onGloballyPositioned。
            Box(modifier = Modifier)
        }

        waitForIdle()

        runOnIdle {
            val c = requireNotNull(controllerRef)
            assertEquals(false, c.state.isMouseInput)          // 默认触屏
            assertEquals(0, c.state.toolbarRequestKey)
            c.finishSelectionGesture()
            assertEquals(1, c.state.toolbarRequestKey)         // 触屏 → bump
        }
    }

    @Test
    fun mouse_input_finish_does_not_bump_toolbar_request_key() = runComposeUiTest {
        val block = inlineTextBlock(id = 1, text = "selectable text", width = 260f, height = 20f)
        var controllerRef: MarkdownSelectionController? = null

        setContent {
            val controller = rememberMarkdownSelectionController(
                coroutineScope = rememberCoroutineScope(),
                textMeasurer = rememberTextMeasurer(),
            )
            LaunchedEffect(controller) {
                controller.updateIndex(listOf(block))
                controller.state.range = SelectionRange(
                    start = SelectionAnchor(blockStableId = 1, charInBlock = 1),
                    end = SelectionAnchor(blockStableId = 1, charInBlock = 8),
                )
                controllerRef = controller
            }
            Box(modifier = Modifier)
        }

        waitForIdle()

        runOnIdle {
            val c = requireNotNull(controllerRef)
            c.state.isMouseInput = true
            c.finishSelectionGesture()
            assertEquals(0, c.state.toolbarRequestKey)         // 鼠标 → 不 bump
            assertTrue(c.state.range != null)                  // 选区保留（selectedText 非空，未 clear）
        }
    }

    @Test
    fun mouse_input_finish_then_tap_clears_selection() = runComposeUiTest {
        val block = inlineTextBlock(id = 1, text = "selectable text", width = 260f, height = 20f)
        var controllerRef: MarkdownSelectionController? = null

        setContent {
            val controller = rememberMarkdownSelectionController(
                coroutineScope = rememberCoroutineScope(),
                textMeasurer = rememberTextMeasurer(),
            )
            LaunchedEffect(controller) {
                controller.updateIndex(listOf(block))
                controller.state.range = SelectionRange(
                    start = SelectionAnchor(blockStableId = 1, charInBlock = 1),
                    end = SelectionAnchor(blockStableId = 1, charInBlock = 8),
                )
                controllerRef = controller
            }
            Box(modifier = Modifier)
        }

        waitForIdle()

        runOnIdle {
            val c = requireNotNull(controllerRef)
            c.state.isMouseInput = true
            c.finishSelectionGesture()
            // 鼠标路径不设 skipNextTapClear → 下次 tap 直接清选区
            c.clearSelectionFromTap()
            assertEquals(null, c.state.range)
        }
    }

    @Test
    fun touch_input_finish_next_tap_clears() = runComposeUiTest {
        val block = inlineTextBlock(id = 1, text = "selectable text", width = 260f, height = 20f)
        var controllerRef: MarkdownSelectionController? = null

        setContent {
            val controller = rememberMarkdownSelectionController(
                coroutineScope = rememberCoroutineScope(),
                textMeasurer = rememberTextMeasurer(),
            )
            LaunchedEffect(controller) {
                controller.updateIndex(listOf(block))
                controller.state.range = SelectionRange(
                    start = SelectionAnchor(blockStableId = 1, charInBlock = 1),
                    end = SelectionAnchor(blockStableId = 1, charInBlock = 8),
                )
                controllerRef = controller
            }
            Box(modifier = Modifier)
        }

        waitForIdle()

        runOnIdle {
            val c = requireNotNull(controllerRef)
            // 默认触屏 isMouseInput=false
            c.finishSelectionGesture()
            c.clearSelectionFromTap()   // 长按循环不会产生额外 tap；下一次点击立即清除
            assertEquals(null, c.state.range)
        }
    }
}
