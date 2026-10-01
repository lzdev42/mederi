package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.MederiIconSquareButton

data class DockItemData(
    val panel: RightDockPanel,
    val icon: ImageVector
)

@Composable
fun RightDock(
    activePanel: RightDockPanel?,
    onSelectPanel: (RightDockPanel) -> Unit,
    onOpenSettings: () -> Unit,
    hasRunningSubagents: Boolean = false,
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
            val hasBadge = item.panel == RightDockPanel.OVERVIEW && hasRunningSubagents
            DockIconButton(
                icon = item.icon,
                label = label,
                isActive = isActive,
                hasBadge = hasBadge,
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
            hasBadge = false,
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
    hasBadge: Boolean = false,
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

    // 按钮本体收敛为 MederiIconSquareButton（28dp / Control / active=accentPrimary 高亮，保留 dock 选中态现状）；
    // 外层 Box 只负责 tooltip 的 hover 探测与手型光标，不再自绘背景/图标
    Box(
        modifier = Modifier
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
    ) {
        MederiIconSquareButton(
            icon = icon,
            onClick = {
                showTooltip = false
                onClick()
            },
            contentDescription = label,
            active = isActive,
            colors = colors,
        )

        // 概览子任务运行中脉冲呼吸微光角标（Pulse Badge）
        if (hasBadge) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse_badge")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_alpha"
            )
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clip(CircleShape)
                    .background(colors.accentSecondary)
            )
        }

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
                // 工具提示壳：Surface 收敛为自绘（shadow 6dp 保留原 shadowElevation 视觉 + clip + bg + border）
                Box(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
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
