package xyz.mederi.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Lock
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.browser_flow_close
import mederi.app.shared.generated.resources.browser_flow_empty
import mederi.app.shared.generated.resources.browser_flow_footer_steps
import mederi.app.shared.generated.resources.browser_flow_footer_steps_with_shots
import mederi.app.shared.generated.resources.browser_flow_running_locked
import mederi.app.shared.generated.resources.browser_flow_step_failed
import mederi.app.shared.generated.resources.browser_flow_step_in_progress
import mederi.app.shared.generated.resources.browser_flow_title_completed
import mederi.app.shared.generated.resources.browser_flow_title_idle
import mederi.app.shared.generated.resources.browser_flow_title_running
import mederi.app.shared.generated.resources.browser_flow_waiting_steps
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.currentTimeMillis
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus

/**
 * 浏览器面板右侧「自动化操作流程视图」（可拖拽调宽、自动化进行中不可关闭、支持直接展示截图并点击放大）。
 *
 * 布局结构：
 * - 左侧：`6.dp` 可拖拽分割线（Resize Handle）；
 * - 右侧：固定宽度的垂直流程面板（Header 状态栏 + Timeline 步骤流 + Footer 统计栏）。
 */
@Composable
fun BrowserAutomationFlowPanel(
    state: BrowserAutomationUiState,
    colors: MederiColors,
    onClose: () -> Unit,
    onResize: (Float) -> Unit,
    onPreviewScreenshot: (BrowserFlowStep.Screenshot) -> Unit,
    maxWidthDp: Float = BrowserAutomationUiState.MAX_PANEL_WIDTH_DP,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val effectiveMaxWidth = maxWidthDp.coerceAtLeast(BrowserAutomationUiState.MIN_PANEL_WIDTH_DP)
    val effectiveWidthDp = state.panelWidthDp.coerceIn(
        BrowserAutomationUiState.MIN_PANEL_WIDTH_DP,
        effectiveMaxWidth,
    )

    val currentWidthState = rememberUpdatedState(effectiveWidthDp)
    val maxWidthState = rememberUpdatedState(effectiveMaxWidth)
    val onResizeState = rememberUpdatedState(onResize)

    val handleInteraction = remember { MutableInteractionSource() }
    val handleHovered by handleInteraction.collectIsHoveredAsState()

    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.surfaceWorkspace),
    ) {
        // ── 1. 左侧可拖拽调宽分割线 (6dp Resize Handle) ──
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(6.dp)
                .hoverable(handleInteraction)
                .pointerHoverIcon(PointerIcon.Crosshair)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val dragDp = with(density) { dragAmount.toDp().value }
                        val nextWidth = (currentWidthState.value - dragDp).coerceIn(
                            BrowserAutomationUiState.MIN_PANEL_WIDTH_DP,
                            maxWidthState.value,
                        )
                        onResizeState.value(nextWidth)
                    }
                }
                .background(
                    if (handleHovered) colors.accentPrimary.copy(alpha = 0.22f)
                    else colors.surfaceCard
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 两侧细边线 + 中央拖拽握把条
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .align(Alignment.CenterStart)
                    .background(colors.divider),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (handleHovered) colors.accentPrimary else colors.borderStrong),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .align(Alignment.CenterEnd)
                    .background(colors.divider),
            )
        }

        // ── 2. 右侧流程面板主体 ──
        Column(
            modifier = Modifier
                .width(effectiveWidthDp.dp)
                .fillMaxHeight()
                .background(colors.surfaceWorkspace),
        ) {
            // A. 顶栏：状态点 + 标题 + 实时耗时 + 锁定徽章 / 关闭按钮
            BrowserAutomationHeader(
                state = state,
                colors = colors,
                onClose = onClose,
            )

            // B. 中部：流程步骤时间线列表
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.steps.isEmpty()) {
                    BrowserAutomationEmptyView(
                        isAutomating = state.isAutomating,
                        colors = colors,
                    )
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.steps.size) {
                        if (state.steps.isNotEmpty()) {
                            listState.animateScrollToItem(state.steps.lastIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = state.steps,
                            key = { _, step -> step.id },
                        ) { index, step ->
                            BrowserFlowTimelineRow(
                                step = step,
                                isLast = index == state.steps.lastIndex,
                                colors = colors,
                                onPreviewScreenshot = onPreviewScreenshot,
                            )
                        }
                    }
                }
            }

            // C. 底栏：步骤统计与当前宽度微标
            BrowserAutomationFooter(
                stepCount = state.steps.size,
                screenshotCount = state.screenshotCount,
                widthDp = effectiveWidthDp,
                colors = colors,
            )
        }
    }
}

/**
 * 流程面板顶栏（32dp）：
 * - 自动化操作进行中（`state.canClose == false`）：右侧显示 `🔒 执行中` 锁定标签，**不提供关闭按钮**；
 * - 自动化操作结束后（`state.canClose == true`）：右侧显示 `✕` 关闭按钮。
 */
@Composable
private fun BrowserAutomationHeader(
    state: BrowserAutomationUiState,
    colors: MederiColors,
    onClose: () -> Unit,
) {
    // 运行中实时计时器（每秒刷新）
    var nowMillis by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(state.isAutomating, state.startedAtMillis) {
        if (state.isAutomating && state.startedAtMillis != null) {
            while (true) {
                nowMillis = currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val elapsedText = remember(state.isAutomating, state.startedAtMillis, state.completedAtMillis, nowMillis) {
        val start = state.startedAtMillis ?: return@remember null
        val end = if (state.isAutomating) nowMillis else (state.completedAtMillis ?: nowMillis)
        val totalSeconds = ((end - start).coerceAtLeast(0L)) / 1000L
        val minutes = (totalSeconds / 60L).toString().padStart(2, '0')
        val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
        "$minutes:$seconds"
    }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val dotColor = when {
        state.isAutomating -> colors.statusWorking
        state.steps.isNotEmpty() -> colors.accentSuccess
        else -> colors.textMuted
    }

    val titleText = stringResource(
        when {
            state.isAutomating -> Res.string.browser_flow_title_running
            state.steps.isNotEmpty() -> Res.string.browser_flow_title_completed
            else -> Res.string.browser_flow_title_idle
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceCard),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(if (state.isAutomating) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(dotColor),
                )

                Text(
                    text = titleText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (elapsedText != null) {
                    Text(
                        text = elapsedText,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            if (!state.canClose) {
                // 自动化操作进行中：不可关闭，展示锁定徽章
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.warningBg)
                        .border(1.dp, colors.accentWarning.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.Lock,
                        contentDescription = null,
                        tint = colors.warningText,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = stringResource(Res.string.browser_flow_running_locked),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.warningText,
                    )
                }
            } else {
                // 非自动化运行中：允许用户手动关闭
                val closeInteraction = remember { MutableInteractionSource() }
                val closeHovered by closeInteraction.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (closeHovered) colors.surfaceHover else Color.Transparent)
                        .hoverable(closeInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = closeInteraction,
                            indication = null,
                            onClick = onClose,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.browser_flow_close),
                        tint = if (closeHovered) colors.textPrimary else colors.textSecondary,
                        modifier = Modifier.size(12.dp),
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
    }
}

/**
 * 流程列表为空时的占位视图。
 */
@Composable
private fun BrowserAutomationEmptyView(
    isAutomating: Boolean,
    colors: MederiColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isAutomating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentPrimary,
                )
                Text(
                    text = stringResource(Res.string.browser_flow_waiting_steps),
                    fontSize = 11.5.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Activity,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(Res.string.browser_flow_empty),
                    fontSize = 11.5.sp,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 流程面板底部统计栏（28dp）。
 */
@Composable
private fun BrowserAutomationFooter(
    stepCount: Int,
    screenshotCount: Int,
    widthDp: Float,
    colors: MederiColors,
) {
    val summaryText = if (screenshotCount > 0) {
        stringResource(Res.string.browser_flow_footer_steps_with_shots, stepCount, screenshotCount)
    } else {
        stringResource(Res.string.browser_flow_footer_steps, stepCount)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceCard),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(27.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = summaryText,
                fontSize = 10.5.sp,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${widthDp.toInt()}dp",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textMuted,
            )
        }
    }
}
