package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
    iconSize: Dp = 14.dp,
    touchTargetSize: Dp = 22.dp,
) {
    var isTapped by remember { mutableStateOf(false) }
    // hover 显示与 tap 显示分离：hover 路径带 150ms 延迟隐藏，允许光标从锚点移到气泡内容
    var hoverVisible by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun hoverShow() {
        hideJob?.cancel()
        hoverVisible = true
    }

    fun hoverHideDelayed() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(150)
            hoverVisible = false
        }
    }

    val showTooltip = hoverVisible || isTapped
    val colors = LocalMederiColors.current
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .size(touchTargetSize)
            .clip(RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> hoverShow()
                            PointerEventType.Exit -> hoverHideDelayed()
                        }
                    }
                }
            }
            .clickable {
                isTapped = !isTapped
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
            Popup(
                popupPositionProvider = remember(density) {
                    AboveAnchorPositionProvider(with(density) { 8.dp.roundToPx() })
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                ),
                onDismissRequest = {
                    hideJob?.cancel()
                    isTapped = false
                    hoverVisible = false
                }
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceCard,
                    border = BorderStroke(1.dp, colors.surfaceCardBorder),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(4.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> hoverShow()
                                        PointerEventType.Exit -> hoverHideDelayed()
                                    }
                                }
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = mainText,
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        if (!noteText.isNullOrBlank()) {
                            Text(
                                text = noteText,
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 优先在锚点上方弹出的 PositionProvider。
 * 当上方空间不足时自动翻转到下方，并保证水平方向在屏幕边缘内。
 */
private class AboveAnchorPositionProvider(
    private val spacingPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val preferredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - spacingPx).coerceAtLeast(0)
        val x = preferredX.coerceIn(spacingPx, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - spacingPx
        val y = if (aboveY >= spacingPx) {
            aboveY
        } else {
            val belowY = anchorBounds.bottom + spacingPx
            val maxY = (windowSize.height - popupContentSize.height - spacingPx).coerceAtLeast(0)
            belowY.coerceIn(spacingPx, maxY)
        }

        return IntOffset(x, y)
    }
}
