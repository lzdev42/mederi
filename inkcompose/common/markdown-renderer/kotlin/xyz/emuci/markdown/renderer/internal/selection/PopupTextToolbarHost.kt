package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt
import xyz.emuci.markdown.renderer.SelectionMenuAction

@Composable
internal fun PopupTextToolbarHost(
    toolbar: PopupTextToolbar,
    controller: MarkdownSelectionController,
    selectionMenuActions: List<SelectionMenuAction>,
) {
    val menuState = toolbar.menuState ?: return
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val menuColor = if (isDark) Color(0xFFE8E8E8) else Color(0xFF1A1A1A)
    val menuBg = if (isDark) Color(0xFF2B2B2B) else Color(0xFFFFFFFF)
    Popup(
        popupPositionProvider = remember(menuState, controller) {
            SelectionMenuPositionProvider(menuState, controller)
        },
        onDismissRequest = { toolbar.hide() },
    ) {
        Surface(
            color = menuBg,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 3.dp,
            tonalElevation = 0.dp,
        ) {
            Column {
                for (action in selectionMenuActions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clickable {
                                action.onClick(controller.selectedText)
                                toolbar.hide()
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (action.leadingIcon != null) {
                            action.leadingIcon()
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(action.label, fontSize = 13.sp, color = menuColor)
                    }
                }
            }
        }
    }
}

/** 把选区锚点 rect（window 坐标）换算成相对 root 容器的位置，供 [Popup] 定位。 */
private class SelectionMenuPositionProvider(
    private val menuState: PopupTextToolbar.MenuState,
    private val controller: MarkdownSelectionController,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val root = controller.registry.rootCoordinates?.takeIf { it.isAttached }
        val windowOffset = Offset(menuState.rect.left, menuState.rect.top)
        val localOffset = root?.windowToLocal(windowOffset) ?: windowOffset
        return IntOffset(localOffset.x.roundToInt(), localOffset.y.roundToInt())
    }
}
