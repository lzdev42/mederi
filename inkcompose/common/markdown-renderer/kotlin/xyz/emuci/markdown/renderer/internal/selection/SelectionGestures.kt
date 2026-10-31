package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar

/**
 * 鼠标按下即固定锚点；触摸普通滑动交给滚动容器，只有长按后才消费拖动。
 * 使用同一手势循环处理右键和左键，避免多个 detector 争抢同一次按下。
 */
internal fun Modifier.markdownSelectionGestures(
    controller: MarkdownSelectionController,
): Modifier = this.composed {
    val focusRequester = remember { FocusRequester() }
    this
        .focusRequester(focusRequester)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val command = event.isMetaPressed || event.isCtrlPressed
            when {
                command && event.key == Key.A -> { controller.selectAll(); true }
                event.key == Key.Escape && controller.hasSelection -> { controller.clearSelection(); true }
                ((command && event.key == Key.C) ||
                    (event.isCtrlPressed && event.key == Key.Insert) || event.key == Key.Copy) &&
                    controller.hasSelection -> { controller.copySelection(); true }
                else -> false
            }
        }
        .focusable()
        .pointerInput(controller) {
            var previousClickTime = Long.MIN_VALUE
            var previousClickPosition = Offset.Zero
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                controller.state.isMouseInput = down.type == PointerType.Mouse
                if (down.type == PointerType.Mouse) {
                    val event = currentEvent
                    if (event.isSecondaryClick()) {
                        focusRequester.requestFocus()
                        controller.showContextMenuAt(down.position)
                        down.consume()
                        return@awaitEachGesture
                    }
                    if (!event.buttons.isPrimaryPressed) return@awaitEachGesture
                    focusRequester.requestFocus()
                    val doubleClick = previousClickTime != Long.MIN_VALUE &&
                        down.uptimeMillis - previousClickTime <= viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - previousClickPosition).getDistance() <= viewConfiguration.touchSlop
                    if (event.keyboardModifiers.isShiftPressed) {
                        controller.extendSelectionFromClick(down.position)
                    } else if (doubleClick) {
                        controller.selectWordAtRootLocal(down.position)
                    } else {
                        controller.beginSelectionAtRootLocal(down.position)
                    }
                    // 让子组件收到原始 down。链接拥有点击时，跨过拖动阈值才抢占它。
                    awaitPointerEvent(PointerEventPass.Main)
                    val slop = if (down.isConsumed) viewConfiguration.touchSlop else 0f
                    var moved = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (moved) change.consume()
                                break
                            }
                            if (!moved && (change.position - down.position).getDistance() <= slop) continue
                            moved = true
                            controller.updateDragPointerAtRootLocal(change.position)
                            controller.extendSelectionToRootLocal(change.position)
                            change.consume()
                        }
                    } finally {
                        controller.finishSelectionGesture()
                    }
                    if (!moved && !doubleClick) {
                        previousClickTime = down.uptimeMillis
                        previousClickPosition = down.position
                    } else previousClickTime = Long.MIN_VALUE
                } else {
                    // 此前不消费 down/move：父级滚动或子组件点击有权取消长按。
                    awaitPointerEvent(PointerEventPass.Main)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        val change = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (change != null && !change.pressed && !change.isConsumed &&
                            (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
                        ) controller.clearSelectionFromTap()
                        return@awaitEachGesture
                    }
                    focusRequester.requestFocus()
                    controller.selectWordAtRootLocal(longPress.position)
                    controller.state.activeHandle = SelectionActiveHandle.End
                    controller.state.toolbarRequestKey = 0
                    longPress.consume()
                    controller.updateDragPointerAtRootLocal(longPress.position)
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == longPress.id } ?: break
                            // 长按后连 up 一起消费，防止松手意外打开链接。
                            change.consume()
                            if (!change.pressed) break
                            controller.updateDragPointerAtRootLocal(change.position)
                            controller.extendSelectionToRootLocal(change.position)
                        }
                    } finally {
                        controller.finishSelectionGesture()
                    }
                }
            }
        }
}

@Composable
internal fun SelectionToolbarHost(
    controller: MarkdownSelectionController,
    onPasteRequested: (() -> Unit)? = null,
) {
    val latestPaste by rememberUpdatedState(onPasteRequested)
    val canPaste = onPasteRequested != null
    val textToolbar = LocalTextToolbar.current
    val range = controller.state.range
    val toolbarRequestKey = controller.state.toolbarRequestKey
    val activeHandle = controller.state.activeHandle
    LaunchedEffect(range, toolbarRequestKey, activeHandle, canPaste) {
        if (range == null || activeHandle != SelectionActiveHandle.None) {
            textToolbar.hide()
        } else if (toolbarRequestKey > 0 && controller.hasSelection) {
            val rect = controller.state.contextMenuAnchor ?: controller.selectionBoundsInWindow()
                ?: return@LaunchedEffect
            textToolbar.showMenu(
                rect = rect,
                onCopyRequested = controller::copySelection,
                onSelectAllRequested = controller::selectAll,
                onPasteRequested = if (canPaste) ({ latestPaste?.invoke() }) else null,
            )
        }
    }
    // Dismissing a menu does not discard the selection; users can still copy with the keyboard.
}
