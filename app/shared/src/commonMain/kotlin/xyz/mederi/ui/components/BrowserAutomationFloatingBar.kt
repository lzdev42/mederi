package xyz.mederi.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.currentTimeMillis
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus
import xyz.mederi.ui.components.atoms.WorkingAnimationStyle
import xyz.mederi.ui.components.atoms.workingAnimation

/**
 * 浏览器自动化操作主对话浮动通知栏（折叠胶囊态 + 向上弹出的详情卡片）。
 *
 * 挂载在主对话输入框上方，解决多栏并排导致浏览器视口挤压的问题：
 * 1. 折叠态（BrowserAutomationCapsule）：36dp 流光胶囊，展示当前实时执行步骤、用时、展开箭头与停止按钮；
 * 2. 展开态（BrowserAutomationPopover）：向上平滑弹出的详情浮层，清晰呈现推理思考（Reasoning）与时序步骤（Timeline）。
 */
@Composable
fun BrowserAutomationFloatingBar(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val automationState = viewModel.browserAutomationState
    val isExpanded = viewModel.isBrowserAutomationFloatingExpanded
    val colors = LocalMederiColors.current

    // 自动化进行中或有未清除的步骤时呈现
    val shouldDisplay = automationState.isAutomating || automationState.steps.isNotEmpty()
    if (!shouldDisplay) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(20f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 1. 向上展开的浮动详情卡片 (Floating Popover)
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                expandFrom = Alignment.Bottom,
            ),
            exit = fadeOut(tween(150)) + shrinkVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
                shrinkTowards = Alignment.Bottom,
            ),
        ) {
            BrowserAutomationPopover(
                state = automationState,
                colors = colors,
                onClose = { viewModel.closeBrowserAutomationFloating() },
                onStopTask = { viewModel.stopBrowserAutomation() },
                onPreviewScreenshot = { shot -> viewModel.openBrowserScreenshotPreview(shot) },
            )
        }

        // 2. 底部的折叠态流光胶囊 (Capsule)
        BrowserAutomationCapsule(
            state = automationState,
            isExpanded = isExpanded,
            colors = colors,
            onClick = { viewModel.toggleBrowserAutomationFloating() },
            onStopTask = { viewModel.stopBrowserAutomation() },
        )
    }
}

/**
 * 折叠态流光胶囊（36dp 高）。
 */
@Composable
private fun BrowserAutomationCapsule(
    state: BrowserAutomationUiState,
    isExpanded: Boolean,
    colors: MederiColors,
    onClick: () -> Unit,
    onStopTask: () -> Unit,
) {
    val shellShape = RoundedCornerShape(8.dp)

    // 旋转加载动画（仅自动化运行中启用）
    val iconRotation by if (state.isAutomating) {
        val transition = rememberInfiniteTransition(label = "browser_capsule_spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "browser_capsule_angle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // 运行耗时计算
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
        val totalSec = ((end - start).coerceAtLeast(0L)) / 1000L
        "${(totalSec / 60L).toString().padStart(2, '0')}:${(totalSec % 60L).toString().padStart(2, '0')}"
    }

    // 提取当前正在执行的动作或最新一步文本
    val currentStepText = remember(state.steps, state.isAutomating) {
        val activeAction = state.steps.filterIsInstance<BrowserFlowStep.Action>()
            .lastOrNull { it.status == BrowserStepStatus.RUNNING }
        val lastStep = state.steps.lastOrNull()

        when {
            activeAction != null -> {
                val target = activeAction.target?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                "${activeAction.title}$target"
            }
            lastStep is BrowserFlowStep.Action -> {
                val target = lastStep.target?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                "${lastStep.title}$target"
            }
            lastStep is BrowserFlowStep.Narration -> lastStep.text
            lastStep is BrowserFlowStep.Memory -> lastStep.text
            lastStep is BrowserFlowStep.Screenshot -> lastStep.title ?: "网页截图"
            state.taskSummary != null -> state.taskSummary
            else -> null
        }
    }

    // 步骤统计
    val totalSteps = state.steps.count { it is BrowserFlowStep.Action }
    val completedSteps = state.steps.filterIsInstance<BrowserFlowStep.Action>().count { it.status == BrowserStepStatus.COMPLETED }

    val capsuleInteraction = remember { MutableInteractionSource() }
    val capsuleHovered by capsuleInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shellShape)
            .background(colors.surfaceCard)
            .border(
                1.dp,
                if (capsuleHovered) colors.borderStrong else colors.divider,
                shellShape,
            )
            .workingAnimation(
                style = WorkingAnimationStyle.BorderBeam,
                enabled = state.isAutomating,
                shape = shellShape,
                primaryColor = colors.accentSecondary,
            )
            .hoverable(capsuleInteraction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = capsuleInteraction, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧：状态图标 + 当前步骤文本 + 耗时角标
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (state.isAutomating) FeatherIcons.Compass else FeatherIcons.Globe,
                contentDescription = null,
                tint = if (state.isAutomating) colors.accentSecondary else colors.accentSuccess,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(iconRotation),
            )

            Text(
                text = currentStepText ?: stringResource(Res.string.browser_automation_floating_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (totalSteps > 0) {
                Text(
                    text = "($completedSteps/$totalSteps)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                )
            }

            if (elapsedText != null) {
                Text(
                    text = elapsedText,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧操作区：详情展开箭头 + 停止任务按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 展开/收起文字提示
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (isExpanded) {
                        stringResource(Res.string.browser_automation_collapse)
                    } else {
                        stringResource(Res.string.browser_automation_expand)
                    },
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
                Icon(
                    imageVector = if (isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronUp,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }

            // 停止任务按钮（仅在运行中或不可关闭时展示红色停止方块）
            if (state.isAutomating) {
                val stopInteraction = remember { MutableInteractionSource() }
                val stopHovered by stopInteraction.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (stopHovered) colors.accentDanger.copy(alpha = 0.16f) else Color.Transparent)
                        .hoverable(stopInteraction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = stopInteraction, indication = null) { onStopTask() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Square,
                        contentDescription = stringResource(Res.string.browser_automation_stop_task),
                        tint = colors.accentDanger,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

/**
 * 向上弹出的详情卡片 (Floating Popover)。
 */
@Composable
private fun BrowserAutomationPopover(
    state: BrowserAutomationUiState,
    colors: MederiColors,
    onClose: () -> Unit,
    onStopTask: () -> Unit,
    onPreviewScreenshot: (BrowserFlowStep.Screenshot) -> Unit,
) {
    val popoverShape = RoundedCornerShape(10.dp)

    // 提取时序动作与截图
    val actionSteps = remember(state.steps) {
        state.steps.filter { it is BrowserFlowStep.Action || it is BrowserFlowStep.Screenshot }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(popoverShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderStrong, popoverShape),
    ) {
        // ── A. 顶栏：标题 + 停止任务按钮 + 收起按钮 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(colors.surfaceWorkspace)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = FeatherIcons.Globe,
                    contentDescription = null,
                    tint = if (state.isAutomating) colors.accentSecondary else colors.accentSuccess,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(Res.string.browser_automation_floating_title),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isAutomating) {
                    // 红色「停止任务」按钮
                    Row(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentDanger.copy(alpha = 0.12f))
                            .border(1.dp, colors.accentDanger.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onStopTask() }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Square,
                            contentDescription = null,
                            tint = colors.accentDanger,
                            modifier = Modifier.size(10.dp),
                        )
                        Text(
                            text = stringResource(Res.string.browser_automation_stop_task),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.accentDanger,
                        )
                    }
                }

                // 收起按钮
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.ChevronDown,
                        contentDescription = stringResource(Res.string.browser_automation_collapse),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp),
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

        // ── B. 推理框（固定，流式读 liveReasoning + liveText；新步/终态由 reducer 复位清空）──
        if (state.liveReasoning.isNotEmpty() || state.liveText.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 10.dp)
                    .heightIn(max = 132.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceInput)
                    .border(1.dp, colors.accentSecondary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 上半：推理思考（liveReasoning）
                if (state.liveReasoning.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.HelpCircle,
                            contentDescription = null,
                            tint = colors.accentSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(Res.string.browser_automation_reasoning_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accentSecondary,
                        )
                    }
                    Text(
                        text = state.liveReasoning,
                        fontSize = 11.5.sp,
                        color = colors.textPrimary,
                        lineHeight = 16.sp,
                    )
                }

                // 下半：流式输出（liveText）
                if (state.liveText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.MessageSquare,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "输出",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                    }
                    Text(
                        text = state.liveText,
                        fontSize = 11.5.sp,
                        color = colors.textPrimary,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // ── C. 步骤时间线（单独滚动）──
        val listState = rememberLazyListState()
        LaunchedEffect(state.steps.size) {
            if (state.steps.isNotEmpty()) {
                listState.animateScrollToItem(state.steps.lastIndex)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. 步骤时间线标题
            item(key = "steps_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.List,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(Res.string.browser_automation_steps_title),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "(${actionSteps.size})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textMuted,
                    )
                }
            }

            // 2. 各动作时间线行
            itemsIndexed(
                items = actionSteps,
                key = { _, step -> step.id },
            ) { index, step ->
                BrowserFlowTimelineRow(
                    step = step,
                    isLast = index == actionSteps.lastIndex,
                    colors = colors,
                    onPreviewScreenshot = onPreviewScreenshot,
                )
            }
        }
    }
}
