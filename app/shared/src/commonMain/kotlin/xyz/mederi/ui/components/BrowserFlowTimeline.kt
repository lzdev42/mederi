package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Code
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Globe
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.MousePointer
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import compose.icons.feathericons.ZoomIn
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_flow_screenshot_default
import mederi.app.shared.generated.resources.browser_flow_step_failed
import mederi.app.shared.generated.resources.browser_flow_step_in_progress
import mederi.app.shared.generated.resources.browser_flow_zoom_in_hint
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.InkImage
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.browser.BrowserActionKind
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus
import xyz.mederi.util.TimeFormatter

/**
 * 单个流程时间线行：左侧时间线节点圆标 + 垂直导轨线，右侧对应步骤卡片。
 */
@Composable
internal fun BrowserFlowTimelineRow(
    step: BrowserFlowStep,
    isLast: Boolean,
    colors: MederiColors,
    onPreviewScreenshot: (BrowserFlowStep.Screenshot) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧时间线圆标与垂直连接线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight()
                .padding(top = 2.dp),
        ) {
            TimelineStepIndicator(step = step, colors = colors)

            if (!isLast) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .weight(1f)
                        .background(colors.divider),
                )
            }
        }

        // 右侧节点内容卡片
        Box(modifier = Modifier.weight(1f)) {
            when (step) {
                is BrowserFlowStep.Narration -> BrowserNarrationStepCard(
                    step = step,
                    colors = colors,
                )

                is BrowserFlowStep.Action -> BrowserActionStepCard(
                    step = step,
                    colors = colors,
                )

                is BrowserFlowStep.Screenshot -> BrowserScreenshotStepCard(
                    step = step,
                    colors = colors,
                    onClick = { onPreviewScreenshot(step) },
                )
            }
        }
    }
}

/**
 * 左侧时间线节点圆标（16×16dp）。
 */
@Composable
private fun TimelineStepIndicator(
    step: BrowserFlowStep,
    colors: MederiColors,
) {
    when (step) {
        is BrowserFlowStep.Narration -> {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.accentBg)
                    .border(1.dp, colors.accentBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.MessageSquare,
                    contentDescription = null,
                    tint = colors.accentText,
                    modifier = Modifier.size(9.dp),
                )
            }
        }

        is BrowserFlowStep.Action -> {
            when (step.status) {
                BrowserStepStatus.RUNNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.8.dp,
                        color = colors.accentPrimary,
                    )
                }

                BrowserStepStatus.COMPLETED -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.successBg)
                            .border(1.dp, colors.accentSuccess.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            tint = colors.successText,
                            modifier = Modifier.size(9.dp),
                        )
                    }
                }

                BrowserStepStatus.FAILED -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.dangerBg)
                            .border(1.dp, colors.statusError.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = null,
                            tint = colors.dangerText,
                            modifier = Modifier.size(9.dp),
                        )
                    }
                }
            }
        }

        is BrowserFlowStep.Screenshot -> {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.successBg)
                    .border(1.dp, colors.accentSuccess.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.Camera,
                    contentDescription = null,
                    tint = colors.successText,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

/**
 * AI 旁白 / 思考卡片（如 `"我看看网页"`）。
 */
@Composable
private fun BrowserNarrationStepCard(
    step: BrowserFlowStep.Narration,
    colors: MederiColors,
) {
    val shape = RoundedCornerShape(6.dp)
    val timeLabel = remember(step.timestampMillis) {
        if (step.timestampMillis > 0L) TimeFormatter.formatTimeWithSeconds(step.timestampMillis) else ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = step.text,
            fontSize = 12.sp,
            color = colors.textPrimary,
        )

        if (timeLabel.isNotEmpty()) {
            Text(
                text = timeLabel,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * 浏览器执行动作卡片（如 `"正在点击"`、`"正在打开网页"`）。
 */
@Composable
private fun BrowserActionStepCard(
    step: BrowserFlowStep.Action,
    colors: MederiColors,
) {
    val shape = RoundedCornerShape(6.dp)
    val isRunning = step.status == BrowserStepStatus.RUNNING
    val isFailed = step.status == BrowserStepStatus.FAILED

    val bgColor = when {
        isRunning -> colors.accentBg
        isFailed -> colors.dangerBg
        else -> colors.surfaceCard
    }
    val borderColor = when {
        isRunning -> colors.accentBorder
        isFailed -> colors.statusError.copy(alpha = 0.4f)
        else -> colors.surfaceCardBorder
    }
    val titleColor = when {
        isRunning -> colors.accentText
        isFailed -> colors.dangerText
        else -> colors.textPrimary
    }

    val statusText = when (step.status) {
        BrowserStepStatus.RUNNING -> stringResource(Res.string.browser_flow_step_in_progress)
        BrowserStepStatus.FAILED -> stringResource(Res.string.browser_flow_step_failed)
        BrowserStepStatus.COMPLETED -> step.durationMs?.let { "${it}ms" }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = actionKindIcon(step.kind),
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = step.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (statusText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isRunning) colors.accentText else colors.textMuted,
                )
            }
        }

        val target = step.target?.takeIf { it.isNotBlank() }
        if (target != null) {
            Text(
                text = target,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 流程时间线内直接展示的网页截图缩略图卡片，支持悬停放大提示与点击弹出大图预览。
 */
@Composable
private fun BrowserScreenshotStepCard(
    step: BrowserFlowStep.Screenshot,
    colors: MederiColors,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val shape = RoundedCornerShape(6.dp)
    val borderColor = if (isHovered) colors.accentPrimary else colors.surfaceCardBorder
    val displayTitle = step.title?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.browser_flow_screenshot_default)

    val metaText = remember(step.timestampMillis, step.resolution) {
        val time = if (step.timestampMillis > 0L) TimeFormatter.formatTimeWithSeconds(step.timestampMillis) else ""
        val res = step.resolution?.takeIf { it.isNotBlank() }.orEmpty()
        when {
            time.isNotEmpty() && res.isNotEmpty() -> "$time · $res"
            time.isNotEmpty() -> time
            else -> res
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCard)
            .border(1.dp, borderColor, shape)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 1. 截图缩略图预览区 + 悬停放大遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(colors.surfaceCode),
            contentAlignment = Alignment.Center,
        ) {
            InkImage(
                model = step.imageUrl,
                contentDescription = displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (isHovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceOverlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceCard.copy(alpha = 0.9f))
                            .border(1.dp, colors.borderStrong, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.ZoomIn,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(Res.string.browser_flow_zoom_in_hint),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )

        // 2. 底部截图说明条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = displayTitle,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (metaText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = metaText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 浏览器动作类别对应的前置图标。
 */
private fun actionKindIcon(kind: BrowserActionKind): ImageVector = when (kind) {
    BrowserActionKind.NAVIGATE -> FeatherIcons.Globe
    BrowserActionKind.CLICK -> FeatherIcons.MousePointer
    BrowserActionKind.INPUT -> FeatherIcons.Type
    BrowserActionKind.SCROLL -> FeatherIcons.ArrowDown
    BrowserActionKind.SNAPSHOT -> FeatherIcons.Eye
    BrowserActionKind.WAIT -> FeatherIcons.Clock
    BrowserActionKind.EVALUATE -> FeatherIcons.Code
    BrowserActionKind.OTHER -> FeatherIcons.Activity
}
