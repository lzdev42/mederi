package xyz.mederi.ui.overview

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Minimize2
import kotlin.math.roundToInt
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_compact_context
import mederi.app.shared.generated.resources.dock_context_unset
import mederi.app.shared.generated.resources.overview_context_usage_title
import mederi.app.shared.generated.resources.overview_compaction_threshold
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.MederiCompactStrokeButton
import xyz.mederi.ui.components.formatTokenShort

/**
 * 概览浮动窗上下文占用行。
 *
 * 口径：显示当前 AI 视图窗口的占用（contextUsedTokens = 最近一次请求 API 报告的 prompt 大小，
 * 与自动压缩触发同源），不是累计 token 消耗。
 *
 * 折叠态摘要：短格式用量 + 36×3dp 迷你进度条 + 整数百分比；模型未配置 contextWindow 时只显示 "--"，
 * 不渲染进度条/百分比（诚实原则，不伪造 0% 已用）。
 *
 * 展开态：`used / max` 主值 + 压缩按钮（requestCompaction）+ 4dp 完整进度条（无窗口时不渲染）。
 */
@Composable
fun OverviewContextRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val usedTokens = viewModel.contextUsedTokens
    // contextWindow = selectedModel 派生 StateFlow：collectAsState 订阅后模型切换触发重组
    val maxTokens by viewModel.contextWindow.collectAsState()
    // 自动压缩阈值（10-95%）：Slider 拖动即写 AppState，collectAsState 订阅后即时响应
    val thresholdPercent by viewModel.compactionThresholdPercent.collectAsState()
    // null = 模型未配置 contextWindow：映射为 0，据此显示 "--" 而非伪造占比
    val maxValue = maxTokens ?: 0
    val hasWindow = maxValue > 0
    val ratio = if (hasWindow) {
        (usedTokens.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = if (hasWindow) {
        (usedTokens * 100 / maxValue).coerceIn(0, 100)
    } else {
        0
    }

    OverviewAccordionRow(
        icon = FeatherIcons.Layers,
        title = stringResource(Res.string.overview_context_usage_title),
        summary = {
            if (hasWindow) {
                Text(
                    text = formatTokenShort(usedTokens),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxHeight()
                            .background(colors.accentPrimary)
                    )
                }
                Text(
                    text = "$percent%",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "--",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasWindow) {
                        "${formatTokenShort(usedTokens)} / ${formatTokenShort(maxValue.toLong())}"
                    } else {
                        "-- / ${stringResource(Res.string.dock_context_unset)}"
                    },
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                MederiCompactStrokeButton(
                    text = stringResource(Res.string.dock_compact_context),
                    onClick = { viewModel.requestCompaction() },
                    icon = FeatherIcons.Minimize2,
                )
            }
            // 完整进度条：无 contextWindow 时不渲染，避免误读为 0% 已用
            if (hasWindow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.accentPrimary)
                    )
                }
            }
            // 压缩阈值滑块：无 contextWindow 时不渲染（诚实原则）；拖动即写 AppState
            if (hasWindow) {
                val thresholdTokens = (maxValue * thresholdPercent / 100).toLong()
                // 标签行：左 = 名称，右 = 百分比 · token 数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.overview_compaction_threshold),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = "$thresholdPercent% · ${formatTokenShort(thresholdTokens)}",
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                // 精简滑块：4dp 柔和底轨 + 12/14dp 精密指针滑块，总高 18dp
                CompactThresholdSlider(
                    value = thresholdPercent,
                    onValueChange = { viewModel.setCompactionThreshold(it) },
                    range = 10..95,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 精简压缩阈值滑块。
 *
 * 简约高品质实现：4dp 柔和轨道 + 12dp/14dp 双层精密指针滑块（高光白钮 + 品牌色内芯），总高 18dp。
 * 支持点击直接跳转与连续手势拖拽，悬停（Hover）与操作（Drag）时具备细腻平滑的缩放与投影微动效。
 */
@Composable
private fun CompactThresholdSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(1f) }

    val fraction = if (range.last > range.first) {
        ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
    } else {
        0f
    }

    val isActive = isHovered || isDragging
    val thumbSize by animateDpAsState(
        targetValue = if (isActive) 14.dp else 12.dp,
        animationSpec = tween(durationMillis = 150),
    )
    val innerDotSize by animateDpAsState(
        targetValue = if (isActive) 5.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
    )
    val thumbElevation by animateDpAsState(
        targetValue = if (isDragging) 3.dp else if (isHovered) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 150),
    )

    val trackHeight = 4.dp
    val totalHeight = 18.dp
    val baseThumbSizePx = with(density) { 12.dp.toPx() }

    fun updateValueFromPosition(x: Float) {
        val usableWidth = (widthPx - baseThumbSizePx).coerceAtLeast(1f)
        val f = ((x - baseThumbSizePx / 2f) / usableWidth).coerceIn(0f, 1f)
        val newValue = (range.first + f * (range.last - range.first))
            .roundToInt()
            .coerceIn(range.first, range.last)
        onValueChange(newValue)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(range, baseThumbSizePx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isDragging = true
                    updateValueFromPosition(down.position.x)
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        updateValueFromPosition(change.position.x)
                    }
                    isDragging = false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 1. 轨道背景（未激活部分使用 divider 色，清晰、柔和且克制）
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(colors.divider)
        )

        // 2. 已激活轨道（从起点延伸至滑块中心，无缝衔接）
        val usableWidthPx = (widthPx - baseThumbSizePx).coerceAtLeast(0f)
        val thumbOffsetXPx = fraction * usableWidthPx
        val activeTrackWidthDp = with(density) { (thumbOffsetXPx + baseThumbSizePx / 2f).toDp() }

        Box(
            Modifier
                .width(activeTrackWidthDp)
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(colors.accentPrimary)
        )

        // 3. 滑块（外圈白色圆钮 + 细微边框/阴影 + 品牌色核心，无 Y 轴偏移天然居中）
        val currentThumbOffsetXDp = with(density) {
            val currentThumbSizePx = thumbSize.toPx()
            val leftPx = thumbOffsetXPx + (baseThumbSizePx - currentThumbSizePx) / 2f
            leftPx.toDp()
        }

        Box(
            modifier = Modifier
                .offset(x = currentThumbOffsetXDp)
                .size(thumbSize)
                .shadow(elevation = thumbElevation, shape = CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(
                    width = 0.5.dp,
                    color = colors.surfaceCardBorder.copy(alpha = 0.6f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(innerDotSize)
                    .clip(CircleShape)
                    .background(colors.accentPrimary)
            )
        }
    }
}
