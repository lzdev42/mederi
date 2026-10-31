package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.resources.Res
import xyz.emuci.inkcompose.resources.selection_copy
import xyz.emuci.inkcompose.resources.selection_paste
import xyz.emuci.inkcompose.SelectionMenuAction

internal data class ToolbarAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val leadingIcon: (@Composable () -> Unit)? = null,
)

@Composable
internal fun PopupTextToolbarHost(
    toolbar: PopupTextToolbar,
    controller: MarkdownSelectionController,
    selectionMenuActions: List<SelectionMenuAction>,
) {
    val menuState = toolbar.menuState ?: return
    val allActions = resolveToolbarActions(
        menuState,
        selectionMenuActions,
        selectedText = { controller.selectedText },
        copyLabel = stringResource(Res.string.selection_copy),
        pasteLabel = stringResource(Res.string.selection_paste),
    )
    if (allActions.isEmpty()) return
    val mouse = controller.state.isMouseInput
    val focusRequester = remember { FocusRequester() }
    var activeIndex by remember(menuState) {
        mutableIntStateOf(allActions.indexOfFirst { it.enabled }.coerceAtLeast(0))
    }
    val spacing = with(LocalDensity.current) { 8.dp.roundToPx() }
    fun activate(index: Int) {
        // 先收起旧菜单：全选等动作可能立即请求一个新菜单。
        if (!allActions[index].enabled) return
        toolbar.hide()
        allActions[index].onClick()
    }
    fun moveActive(direction: Int) {
        repeat(allActions.size) {
            activeIndex = (activeIndex + direction + allActions.size) % allActions.size
            if (allActions[activeIndex].enabled) return
        }
    }
    Popup(
        popupPositionProvider = remember(menuState.rect, mouse, spacing) {
            SelectionMenuPositionProvider(menuState.rect, mouse, spacing)
        },
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(focusable = mouse),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier
                    .width(if (mouse) 224.dp else 240.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        val command = event.isCtrlPressed || event.isMetaPressed
                        if (event.type != KeyEventType.KeyDown) false
                        else if ((command && event.key == Key.C) || event.key == Key.Copy) {
                            menuState.onCopyRequested?.let { toolbar.hide(); it(); true } ?: false
                        } else if ((command && event.key == Key.V) || event.key == Key.Paste) {
                            menuState.onPasteRequested?.let { toolbar.hide(); it(); true } ?: false
                        } else when (event.key) {
                            Key.DirectionDown -> { moveActive(1); true }
                            Key.DirectionUp -> { moveActive(-1); true }
                            Key.MoveHome -> { activeIndex = allActions.indexOfFirst { it.enabled }.coerceAtLeast(0); true }
                            Key.MoveEnd -> { activeIndex = allActions.indexOfLast { it.enabled }.coerceAtLeast(0); true }
                            Key.Enter, Key.Spacebar -> { activate(activeIndex); true }
                            Key.Escape -> { toolbar.hide(); true }
                            else -> false
                        }
                    }
                    .focusable(enabled = mouse)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                allActions.forEachIndexed { index, action ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    val highlighted = action.enabled && (hovered || (mouse && index == activeIndex))
                    Row(
                        modifier = Modifier
                            .width(if (mouse) 216.dp else 232.dp)
                            .heightIn(min = if (mouse) 36.dp else 48.dp)
                            .background(
                                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(8.dp),
                            )
                            .hoverable(interaction)
                            .clickable(enabled = action.enabled) { activate(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        action.leadingIcon?.let {
                            it()
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.enabled) 1f else 0.38f),
                        )
                    }
                }
            }
        }
        LaunchedEffect(menuState, mouse) {
            if (mouse) focusRequester.requestFocus()
        }
    }
}

/** 每个类型对应一个菜单项，严格保持调用方数组的顺序和数量。 */
internal fun resolveToolbarActions(
    menuState: PopupTextToolbar.MenuState,
    selectionMenuActions: List<SelectionMenuAction>,
    selectedText: () -> String,
    copyLabel: String,
    pasteLabel: String,
): List<ToolbarAction> = selectionMenuActions.map { action ->
    when (action) {
        SelectionMenuAction.Copy -> ToolbarAction(
            copyLabel, { menuState.onCopyRequested?.invoke() }, menuState.onCopyRequested != null,
        )
        SelectionMenuAction.Paste -> ToolbarAction(
            pasteLabel, { menuState.onPasteRequested?.invoke() }, menuState.onPasteRequested != null,
        )
        is SelectionMenuAction.Custom -> ToolbarAction(
            action.label, { action.onClick(selectedText()) }, action.enabled, action.leadingIcon,
        )
    }
}

/** PopupPositionProvider 使用窗口坐标；不要再次减去 Markdown 容器的原点。 */
internal class SelectionMenuPositionProvider(
    private val selectionBounds: Rect,
    private val mouse: Boolean,
    private val spacing: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val preferredX = if (mouse) {
            if (layoutDirection == LayoutDirection.Ltr) selectionBounds.left.roundToInt()
            else selectionBounds.right.roundToInt() - width
        } else {
            selectionBounds.center.x.roundToInt() - width / 2
        }
        val above = selectionBounds.top.roundToInt() - height - spacing
        val below = selectionBounds.bottom.roundToInt() + spacing
        val preferredY = if (mouse) {
            // 右键菜单通常在指针下方展开，贴近窗口底部时向上翻转。
            if (below + height + spacing <= windowSize.height) below else above
        } else {
            // 触屏浮动工具条优先在选区上方，避免遮挡手柄。
            if (above >= spacing) above else below
        }
        val maxX = (windowSize.width - width - spacing).coerceAtLeast(0)
        val maxY = (windowSize.height - height - spacing).coerceAtLeast(0)
        return IntOffset(
            preferredX.coerceIn(spacing.coerceAtMost(maxX), maxX),
            preferredY.coerceIn(spacing.coerceAtMost(maxY), maxY),
        )
    }
}
