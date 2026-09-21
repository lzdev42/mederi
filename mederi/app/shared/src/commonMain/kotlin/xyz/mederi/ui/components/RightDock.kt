package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.rightdock_artifacts
import mederi.app.shared.generated.resources.rightdock_browser
import mederi.app.shared.generated.resources.rightdock_diff
import mederi.app.shared.generated.resources.rightdock_overview
import mederi.app.shared.generated.resources.rightdock_plan
import mederi.app.shared.generated.resources.rightdock_settings
import mederi.app.shared.generated.resources.rightdock_terminal
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.RightDockPanel
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.ChatLayout

data class DockItemData(
    val panel: RightDockPanel,
    val icon: ImageVector
)

@Composable
fun RightDock(
    activePanel: RightDockPanel?,
    onSelectPanel: (RightDockPanel) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    val dockItems = listOf(
        DockItemData(RightDockPanel.OVERVIEW, FeatherIcons.Activity),
        DockItemData(RightDockPanel.DIFF, FeatherIcons.GitCommit),
        DockItemData(RightDockPanel.PLAN, FeatherIcons.FileText),
        DockItemData(RightDockPanel.ARTIFACTS, FeatherIcons.File),
        DockItemData(RightDockPanel.TERMINAL, FeatherIcons.Terminal),
        DockItemData(RightDockPanel.BROWSER, FeatherIcons.Globe)
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(ChatLayout.rightDockWidth)
            .background(colors.surfaceSidebar)
            .border(width = 1.dp, color = colors.divider)
            .padding(vertical = 10.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 功能图标栏
        dockItems.forEach { item ->
            val isActive = activePanel == item.panel
            val label = when (item.panel) {
                RightDockPanel.OVERVIEW -> stringResource(Res.string.rightdock_overview)
                RightDockPanel.DIFF -> stringResource(Res.string.rightdock_diff)
                RightDockPanel.PLAN -> stringResource(Res.string.rightdock_plan)
                RightDockPanel.ARTIFACTS -> stringResource(Res.string.rightdock_artifacts)
                RightDockPanel.TERMINAL -> stringResource(Res.string.rightdock_terminal)
                RightDockPanel.BROWSER -> stringResource(Res.string.rightdock_browser)
            }
            DockIconButton(
                icon = item.icon,
                label = label,
                isActive = isActive,
                colors = colors,
                onClick = { onSelectPanel(item.panel) }
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // 底部设置按钮
        DockIconButton(
            icon = FeatherIcons.Sliders,
            label = stringResource(Res.string.rightdock_settings),
            isActive = false,
            colors = colors,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun DockIconButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    colors: MederiColors,
    onClick: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    var showTooltip by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(200)
            showTooltip = true
        } else {
            showTooltip = false
        }
    }

    val backgroundColor = when {
        isActive -> colors.accentPrimary.copy(alpha = 0.15f)
        isHovered -> colors.surfaceHover
        else -> Color.Transparent
    }

    val iconTint = when {
        isActive -> colors.accentPrimary
        isHovered -> colors.textPrimary
        else -> colors.textMuted
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) colors.accentPrimary.copy(alpha = 0.35f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                if (!isHovered) {
                                    isHovered = true
                                    DebugLog.event("UI", "DockIconButton enter: label=$label")
                                }
                            }
                            PointerEventType.Exit -> {
                                if (isHovered) {
                                    isHovered = false
                                    DebugLog.event("UI", "DockIconButton exit: label=$label")
                                }
                            }
                        }
                    }
                }
            }
            .clickable(onClick = {
                showTooltip = false
                onClick()
            }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(17.dp)
        )

        if (showTooltip) {
            Popup(
                popupPositionProvider = remember(density) {
                    DockTooltipPositionProvider(with(density) { 8.dp.roundToPx() })
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.surfaceCardBorder),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

private class DockTooltipPositionProvider(
    private val spacingPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = anchorBounds.left - popupContentSize.width - spacingPx
        val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        return IntOffset(
            x = x.coerceAtLeast(0),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}
