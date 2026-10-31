package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/** 纯几何速度函数：选区只在可见阅读区上下边缘滚动，区外速度封顶。 */
internal fun selectionAutoScrollVelocity(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeSize: Float,
    maxSpeed: Float,
): Float {
    if (viewportBottom <= viewportTop || edgeSize <= 0f) return 0f
    val edge = minOf(edgeSize, (viewportBottom - viewportTop) / 2f)
    return when {
        pointerY < viewportTop + edge -> -maxSpeed * ((viewportTop + edge - pointerY) / edge).coerceIn(0f, 1f)
        pointerY > viewportBottom - edge -> maxSpeed * ((pointerY - viewportBottom + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * 和手势分离的逐帧选区滚动。指针保持窗口坐标，内容滚动时不需要等待新的 move 事件。
 * 自己的 LazyColumn 先消费；剩余量按 nested-scroll 协议传给宿主列表。
 */
@Composable
internal fun SelectionAutoScrollHost(
    controller: MarkdownSelectionController,
    lazyListState: LazyListState,
    enableScroll: Boolean,
    dispatcher: NestedScrollDispatcher,
) {
    val density = LocalDensity.current
    val edgeSize = with(density) { 48.dp.toPx() }
    val maxSpeed = with(density) { 1000.dp.toPx() }
    val dragging = controller.state.dragPointerInWindow != null
    LaunchedEffect(controller, lazyListState, enableScroll, dispatcher, dragging, edgeSize, maxSpeed) {
        if (!dragging) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrame = frame
            val pointer = controller.state.dragPointerInWindow ?: break
            val root = controller.registry.rootCoordinates?.takeIf { it.isAttached } ?: break
            // boundsInWindow 会裁掉祖先列表的不可见区域，不能用整个长文 item 的高度。
            val viewport = root.boundsInWindow()
            val velocity = selectionAutoScrollVelocity(pointer.y, viewport.top, viewport.bottom, edgeSize, maxSpeed)
            if (velocity == 0f) continue
            // nested scroll 的 offset 是手指移动方向，与 scrollBy 的内容前进方向相反。
            val available = Offset(0f, -velocity * seconds)
            val preConsumed = dispatcher.dispatchPreScroll(available, NestedScrollSource.UserInput)
            val afterPre = available - preConsumed
            val ownConsumed = if (enableScroll) lazyListState.scrollBy(-afterPre.y) else 0f
            dispatcher.dispatchPostScroll(
                consumed = Offset(0f, -ownConsumed),
                available = Offset(0f, afterPre.y + ownConsumed),
                source = NestedScrollSource.UserInput,
            )
            // 先等待本帧布局更新坐标表，下一帧即使鼠标不动也会扩大逻辑选区。
            withFrameNanos { }
            val latestPointer = controller.state.dragPointerInWindow ?: break
            val latestRoot = controller.registry.rootCoordinates?.takeIf { it.isAttached } ?: break
            if (controller.state.isHandleDrag) {
                controller.dragSelectionHandleToRootLocal(latestRoot.windowToLocal(latestPointer))
            } else {
                controller.extendSelectionTo(latestPointer)
            }
        }
    }
}
