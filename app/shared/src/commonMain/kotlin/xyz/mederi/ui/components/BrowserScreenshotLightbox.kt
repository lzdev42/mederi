package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.Camera
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_flow_lightbox_close
import mederi.app.shared.generated.resources.browser_flow_screenshot_default
import mederi.app.shared.generated.resources.dock_fit_window
import mederi.app.shared.generated.resources.dock_original_ratio
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.InkImage
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.util.TimeFormatter

/**
 * 点击流程内截图后弹出的高清大图预览弹窗（Lightbox）。
 *
 * 支持「适应窗口 / 原始比例」一键切换，点击遮罩背景或右上角关闭按钮退出。
 */
@Composable
fun BrowserScreenshotLightbox(
    screenshot: BrowserFlowStep.Screenshot,
    colors: MederiColors,
    onDismiss: () -> Unit,
) {
    var isOriginalScale by remember(screenshot.id) { mutableStateOf(false) }
    val displayTitle = screenshot.title?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.browser_flow_screenshot_default)

    val metaText = remember(screenshot.timestampMillis, screenshot.resolution) {
        val time = if (screenshot.timestampMillis > 0L) TimeFormatter.formatTimeWithSeconds(screenshot.timestampMillis) else ""
        val res = screenshot.resolution?.takeIf { it.isNotBlank() }.orEmpty()
        when {
            time.isNotEmpty() && res.isNotEmpty() -> "$time · $res"
            time.isNotEmpty() -> time
            else -> res
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceOverlay)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardShape = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = 980.dp)
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.86f)
                    .clip(cardShape)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.borderStrong, cardShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // 1. 弹窗顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(colors.surfaceSidebar)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Camera,
                            contentDescription = null,
                            tint = colors.accentText,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = displayTitle,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (metaText.isNotEmpty()) {
                            Text(
                                text = metaText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 适应窗口 / 原始比例切换
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors.surfaceWorkspace)
                                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(5.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { isOriginalScale = !isOriginalScale }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (isOriginalScale) Res.string.dock_fit_window else Res.string.dock_original_ratio
                                ),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.accentText,
                            )
                        }

                        // 关闭按钮
                        val closeInteraction = remember { MutableInteractionSource() }
                        val closeHovered by closeInteraction.collectIsHoveredAsState()
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (closeHovered) colors.surfaceHover else Color.Transparent)
                                .hoverable(closeInteraction)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = closeInteraction,
                                    indication = null,
                                    onClick = onDismiss,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FeatherIcons.X,
                                contentDescription = stringResource(Res.string.browser_flow_lightbox_close),
                                tint = if (closeHovered) colors.textPrimary else colors.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider),
                )

                // 2. 高清截图画板
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.surfaceWorkspace)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InkImage(
                        model = screenshot.imageUrl,
                        contentDescription = displayTitle,
                        contentScale = if (isOriginalScale) ContentScale.None else ContentScale.Fit,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (isOriginalScale) {
                                    Modifier
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                } else {
                                    Modifier.fillMaxSize()
                                }
                            ),
                    )
                }
            }
        }
    }
}
