package xyz.emuci.markdown.renderer.internal.selection

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.markdown.renderer.SelectionMenuAction

@Composable
internal fun PopupTextToolbarHost(
    toolbar: PopupTextToolbar,
    controller: MarkdownSelectionController,
    selectionMenuActions: List<SelectionMenuAction>,
) {
    val menuState = toolbar.menuState ?: return
    val density = LocalDensity.current
    val root = controller.registry.rootCoordinates?.takeIf { it.isAttached }
    val windowOffset = Offset(menuState.rect.left, menuState.rect.top)
    val localOffset = root?.windowToLocal(windowOffset) ?: windowOffset
    DropdownMenu(
        expanded = true,
        onDismissRequest = { toolbar.hide() },
        offset = with(density) { androidx.compose.ui.unit.DpOffset(localOffset.x.toDp(), localOffset.y.toDp()) },
    ) {
        if (menuState.onCopyRequested != null) {
            DropdownMenuItem(
                text = { Text("复制", fontSize = 13.sp) },
                onClick = {
                    menuState.onCopyRequested.invoke()
                    toolbar.hide()
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        for (action in selectionMenuActions) {
            DropdownMenuItem(
                text = { Text(action.label, fontSize = 13.sp) },
                onClick = {
                    action.onClick(controller.selectedText)
                    toolbar.hide()
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
