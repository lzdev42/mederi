package xyz.mederi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.WorkspaceTooltipPopup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.HelpCircle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.tooltip_content_description
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors

/**
 * 圆圈问号信息气泡组件。
 * - 桌面端：鼠标悬停（Hover）自动弹出气泡，移出隐藏。
 * - 移动端：手势轻触（Click）弹出/切换气泡，点击外部区域自动收起。
 */
@Composable
fun HelpCircleTooltip(
    mainText: String,
    noteText: String? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = WorkspaceStyle.IconSize,
    touchTargetSize: Dp = WorkspaceStyle.IconTarget,
) {
    var isTapped by remember { mutableStateOf(false) }
    var automaticSuppressed by remember { mutableStateOf(false) }
    // hover 显示与 tap 显示分离：hover 路径带 150ms 延迟隐藏，允许光标从锚点移到气泡内容
    var hoverVisible by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun hoverShow() {
        if (automaticSuppressed) return
        hideJob?.cancel()
        hideJob = scope.launch { delay(WorkspaceStyle.TooltipDelayMillis); hoverVisible = true }
    }

    fun hoverHideDelayed() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(WorkspaceStyle.TooltipHideMillis)
            hoverVisible = false
        }
    }

    val showTooltip = hoverVisible || isTapped
    val colors = LocalMederiColors.current

    Box(
        modifier = modifier
            .sizeIn(minWidth = maxOf(touchTargetSize, WorkspaceStyle.IconTarget),
                minHeight = maxOf(touchTargetSize, WorkspaceStyle.IconTarget))
            .clip(RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> hoverShow()
                            PointerEventType.Exit -> { automaticSuppressed = false; hoverHideDelayed() }
                        }
                    }
                }
            }
            .onFocusChanged { if (it.isFocused) hoverShow() else { automaticSuppressed = false; hoverHideDelayed() } }
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && showTooltip) {
                    hideJob?.cancel(); automaticSuppressed = true; isTapped = false; hoverVisible = false; true
                } else false
            }
            .clickable(role = Role.Button) {
                hideJob?.cancel()
                isTapped = !isTapped
                automaticSuppressed = !isTapped
                hoverVisible = false
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = FeatherIcons.HelpCircle,
            contentDescription = stringResource(Res.string.tooltip_content_description),
            tint = if (showTooltip) colors.accentPrimary else colors.textMuted,
            modifier = Modifier.size(iconSize)
        )

        if (showTooltip) {
            WorkspaceTooltipPopup(
                text = mainText, note = noteText,
                focusable = isTapped,
                onDismiss = { hideJob?.cancel(); automaticSuppressed = true; isTapped = false; hoverVisible = false },
                modifier = Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter, PointerEventType.Move -> hoverShow()
                                PointerEventType.Exit -> hoverHideDelayed()
                            }
                        }
                    }
                },
            )
        }
    }
}
