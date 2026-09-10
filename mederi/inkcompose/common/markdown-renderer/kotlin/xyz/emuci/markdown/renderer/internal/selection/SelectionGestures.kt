package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 选区手势：通过 nestedScroll 让父级滚动容器（LazyColumn）优先消费拖拽量，
 * 仅当父级无法继续滚动（已到边界）时才启动文本选区。
 *
 * 原实现 detectDragGestures 直接 consume 了所有拖拽事件，父级 LazyColumn 完全收不到，
 * 导致在列表中拖拽 Markdown 内容时列表"粘住拉不动"。
 */
internal fun Modifier.markdownSelectionGestures(
    controller: MarkdownSelectionController,
): Modifier = this.composed {
    val dispatcher = remember { NestedScrollDispatcher() }

    this
        .nestedScroll(
            connection = object : NestedScrollConnection {},
            dispatcher = dispatcher,
        )
        .pointerInput(controller, dispatcher) {
            var selectionActive = false
            var pendingStart: Offset? = null

            detectDragGestures(
                onDragStart = { offset ->
                    pendingStart = offset
                    selectionActive = false
                },
                onDragEnd = {
                    if (selectionActive) {
                        controller.finishSelectionGesture()
                    }
                    selectionActive = false
                    pendingStart = null
                },
                onDragCancel = {
                    if (selectionActive) {
                        controller.finishSelectionGesture()
                    }
                    selectionActive = false
                    pendingStart = null
                },
                onDrag = { change, dragAmount ->
                    // 通过 nestedScroll 把拖拽量先分发给父级（LazyColumn）。
                    // 父级能滚动就消费，不能滚动（到边界）则返回 0。
                    val consumedByParent = dispatcher.dispatchPreScroll(
                        available = dragAmount,
                        source = NestedScrollSource.UserInput,
                    )

                    if (consumedByParent != Offset.Zero) {
                        // 父级滚动了 → 取消已启动的选区
                        if (selectionActive) {
                            controller.finishSelectionGesture()
                            selectionActive = false
                        }
                    } else {
                        // 父级没滚动 → 启动/扩展选区
                        if (!selectionActive && pendingStart != null) {
                            controller.beginSelectionAtRootLocal(pendingStart!!)
                            selectionActive = true
                        }
                        if (selectionActive) {
                            controller.extendSelectionToRootLocal(change.position)
                        }
                    }
                    change.consume()
                },
            )
        }
        .pointerInput(controller) {
            detectTapGestures(
                onTap = { controller.clearSelectionFromTap() },
                onDoubleTap = { offset -> controller.selectWordAtRootLocal(offset) },
            )
        }
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press) {
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.type == PointerType.Mouse && event.isSecondaryClick()) {
                            change.consume()
                            controller.showContextMenuAt(change.position)
                        }
                    }
                }
            }
        }
}

@Composable
internal fun SelectionToolbarHost(controller: MarkdownSelectionController) {
    val textToolbar = LocalTextToolbar.current
    val range = controller.state.range
    val toolbarRequestKey = controller.state.toolbarRequestKey
    val activeHandle = controller.state.activeHandle
    var watchExternalDismiss by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(range, toolbarRequestKey, activeHandle) {
        if (range == null || activeHandle != SelectionActiveHandle.None) {
            watchExternalDismiss = false
            textToolbar.hide()
        } else if (toolbarRequestKey > 0) {
            val rect = controller.selectionBoundsInWindow() ?: return@LaunchedEffect
            textToolbar.showMenu(
                rect = rect,
                onCopyRequested = {
                    controller.copySelection()
                    controller.clearSelection()
                },
            )
            watchExternalDismiss = true
        }
    }
    LaunchedEffect(textToolbar, range, activeHandle, watchExternalDismiss) {
        if (range == null || activeHandle != SelectionActiveHandle.None || !watchExternalDismiss) {
            return@LaunchedEffect
        }
        var seenShown = textToolbar.status == TextToolbarStatus.Shown
        snapshotFlow { textToolbar.status }.collect { status ->
            when (status) {
                TextToolbarStatus.Shown -> seenShown = true
                TextToolbarStatus.Hidden -> {
                    if (seenShown &&
                        controller.state.range != null &&
                        controller.state.activeHandle == SelectionActiveHandle.None
                    ) {
                        watchExternalDismiss = false
                        controller.clearSelection()
                    }
                }
            }
        }
    }
}
