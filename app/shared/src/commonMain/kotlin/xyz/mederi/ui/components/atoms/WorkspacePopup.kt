package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import xyz.mederi.theme.*

/** 菜单保留平台的焦点/方向键/dismiss行为，只收敛外壳与行样式。 */
@Composable
internal fun WorkspaceDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    DropdownMenu(expanded, onDismissRequest, modifier = modifier.onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismissRequest(); true } else false
    },
        shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius), containerColor = colors.surfaceCard,
        tonalElevation = 0.dp, shadowElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceCardBorder), content = content)
}

@Composable
internal fun WorkspaceMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    twoLines: Boolean = false,
) {
    val colors = LocalMederiColors.current
    DropdownMenuItem(text, onClick,
        modifier = modifier.heightIn(min = if (twoLines) 48.dp else WorkspaceStyle.ControlHeight)
            .semantics { this.selected = selected }
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(if (selected) colors.accentBg else androidx.compose.ui.graphics.Color.Transparent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp))
}

internal enum class WorkspaceTooltipPlacement { Above, Before }

/** 两类提示共享窗口内定位；hover不抢焦点，点击帮助可聚焦并接收Escape。 */
@Composable
internal fun WorkspaceTooltipPopup(
    text: String,
    note: String? = null,
    placement: WorkspaceTooltipPlacement = WorkspaceTooltipPlacement.Above,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = false,
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    Popup(popupPositionProvider = remember(density, placement) {
        WorkspaceTooltipPositionProvider(with(density) { 8.dp.roundToPx() }, placement)
    }, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        val keyboard = if (focusable) Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
        }.focusRequester(focusRequester).focusable() else Modifier
        WorkspaceTooltipContent(text, note, modifier.then(keyboard))
        LaunchedEffect(focusable) { if (focusable) focusRequester.requestFocus() }
    }
}

@Composable
internal fun WorkspaceTooltipContent(text: String, note: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalMederiColors.current
    val shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius)
    Column(modifier.widthIn(max = 280.dp).clip(shape).background(colors.surfaceCard)
        .border(1.dp, colors.surfaceCardBorder, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = WorkspaceStyle.Body, color = colors.textPrimary)
        if (!note.isNullOrBlank()) Text(note, style = WorkspaceStyle.Caption, color = colors.textSecondary)
    }
}

internal class WorkspaceTooltipPositionProvider(
    private val spacingPx: Int,
    private val placement: WorkspaceTooltipPlacement,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val preferredX = if (placement == WorkspaceTooltipPlacement.Before) {
            if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left - popupContentSize.width - spacingPx
            else anchorBounds.right + spacingPx
        } else anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val above = anchorBounds.top - popupContentSize.height - spacingPx
        val preferredY = if (placement == WorkspaceTooltipPlacement.Before)
            anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        else if (above >= spacingPx) above else anchorBounds.bottom + spacingPx
        // oversized popup/window仍有合法区间，不会coerceIn(min>max)崩溃。
        return IntOffset(preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            preferredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}
