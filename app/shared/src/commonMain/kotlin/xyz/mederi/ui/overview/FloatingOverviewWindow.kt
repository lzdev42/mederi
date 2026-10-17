package xyz.mederi.ui.overview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Minus
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.overview_minimize
import mederi.app.shared.generated.resources.overview_window_title
import mederi.app.shared.generated.resources.subagent_running_count
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.formatTokenShort

/** 概览窗口宽度（展开态） */
private val OVERVIEW_WINDOW_WIDTH = 292.dp

/** 概览胶囊高度（最小化态） */
private val OVERVIEW_PILL_HEIGHT = 32.dp

/** 窗口/胶囊与视口边缘的留白 */
private val OVERVIEW_MARGIN = 16.dp

/** 最小化状态落盘防抖（毫秒） */
private const val SAVE_DEBOUNCE_MS = 300L

/** 拖动位移阈值：低于此值视为点击（不触发拖动） */
private const val DRAG_THRESHOLD = 4f

/**
 * 概览浮动窗（FloatingOverviewWindow）
 *
 * 固定在工作区内容区右上角的悬浮窗：
 * - 窗口/胶囊两个形态间用 [animateContentSize] + [Crossfade] 平滑过渡（先缩小再贴合，非瞬切）；
 * - 胶囊点击恢复为窗口，窗口标题栏减号按钮最小化为胶囊；
 * - [draggable] = true 时标题栏/胶囊可拖动（位置持久化）；false（默认）固定右上角；
 * - 最小化状态持久化到 preferences.json；
 * - zIndex(10f) 保证浮于工作区其它内容之上。
 *
 * 内容区为 9 个手风琴行（后台任务/上下文/用量/计划/子 Agent/MCP/Skill/Todo/原始消息）。
 *
 * 调用方需在 BoxScope 内用 `Modifier.align(Alignment.TopEnd)` 定位（当 draggable=false）。
 */
@Composable
fun FloatingOverviewWindow(
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val density = LocalDensity.current
    val state = remember { OverviewWindowState() }

    // 子代理运行中计数（真实数据，非硬编码）
    val subagents = viewModel.subagents
    val runningCount = subagents.count { it.status.equals("RUNNING", ignoreCase = true) }

    // 拖动状态（仅 draggable=true 时使用）
    var offsetX by remember { mutableStateOf(0) }
    var offsetY by remember { mutableStateOf(0) }
    var dragLoaded by remember { mutableStateOf(false) }

    // 加载持久化状态
    LaunchedEffect(Unit) {
        state.load(appState.preferencesStore)
    }
    // 最小化状态变化时 debounce 落盘
    LaunchedEffect(state.minimized) {
        delay(SAVE_DEBOUNCE_MS)
        state.save(appState.preferencesStore)
    }

    // 位置定位：draggable=true 用 offset，false 用调用方的 align
    val positionModifier = if (draggable) {
        Modifier.offset { IntOffset(offsetX, offsetY) }
    } else {
        modifier
    }

    Box(
        modifier = positionModifier
            .padding(top = 48.dp, end = OVERVIEW_MARGIN)
            .zIndex(10f),
    ) {
        // animateContentSize 让窗口↔胶囊切换时容器尺寸平滑过渡
        // spring 弹性动画：先缩小尺寸，自然贴合到胶囊大小
        Box(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            Crossfade(
                targetState = state.minimized,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "overviewWindow",
            ) { minimized ->
                if (minimized) {
                    // ── 胶囊态（最小化） ──
                    val pillModifier = if (draggable) {
                        Modifier.pointerInput(Unit) {
                            var totalDrag = 0f
                            detectDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x.toInt()
                                    offsetY += dragAmount.y.toInt()
                                    totalDrag += kotlin.math.abs(dragAmount.x) + kotlin.math.abs(dragAmount.y)
                                },
                            )
                        }.clickable { state.minimized = false }
                    } else {
                        Modifier.clickable { state.minimized = false }
                    }

                    Row(
                        modifier = Modifier
                            .height(OVERVIEW_PILL_HEIGHT)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(OVERVIEW_PILL_HEIGHT / 2),
                                clip = false,
                            )
                            .clip(RoundedCornerShape(OVERVIEW_PILL_HEIGHT / 2))
                            .background(colors.surfaceCard)
                            .border(
                                width = 1.dp,
                                color = colors.surfaceCardBorder,
                                shape = RoundedCornerShape(OVERVIEW_PILL_HEIGHT / 2),
                            )
                            .then(pillModifier)
                            .padding(horizontal = MederiSpacing.MD),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Activity,
                            contentDescription = null,
                            tint = colors.accentSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        // 上下文用量（真实数据）
                        Text(
                            text = formatTokenShort(viewModel.contextUsedTokens),
                            color = colors.textPrimary,
                            style = MederiTypeScale.Body,
                            maxLines = 1,
                        )
                        // 运行中指示器（仅当有运行中子代理时显示）
                        if (runningCount > 0) {
                            val pulseTransition = rememberInfiniteTransition(label = "overview_pill_pulse")
                            val dotAlpha by pulseTransition.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                                label = "overview_pill_pulse_alpha",
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentPrimary.copy(alpha = dotAlpha))
                            )
                            Text(
                                text = stringResource(Res.string.subagent_running_count, runningCount),
                                color = colors.accentText,
                                style = MederiTypeScale.Body,
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    // ── 窗口态（展开） ──
                    val titleBarModifier = if (draggable) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x.toInt()
                                offsetY += dragAmount.y.toInt()
                            }
                        }
                    } else {
                        Modifier
                    }

                    Box(
                        modifier = Modifier
                            .width(OVERVIEW_WINDOW_WIDTH)
                            .shadow(
                                elevation = 8.dp,
                                shape = RoundedCornerShape(MederiRadius.Bubble),
                                clip = false,
                            )
                            .clip(RoundedCornerShape(MederiRadius.Bubble))
                            .background(colors.surfaceCard)
                            .border(
                                width = 1.dp,
                                color = colors.surfaceCardBorder,
                                shape = RoundedCornerShape(MederiRadius.Bubble),
                            ),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 标题栏 36dp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .then(titleBarModifier)
                                    .padding(start = MederiSpacing.LG),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(Res.string.overview_window_title),
                                    color = colors.textPrimary,
                                    style = MederiTypeScale.Title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable { state.minimized = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.Minus,
                                        contentDescription = stringResource(Res.string.overview_minimize),
                                        tint = colors.textMuted,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }

                            // 内容区：9 个手风琴行（后台任务行最前，其余顺延 +1）
                            OverviewRunningTaskRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 0,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 0) null else 0 },
                            )
                            OverviewContextRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 1,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 1) null else 1 },
                            )
                            OverviewUsageRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 2,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 2) null else 2 },
                            )
                            OverviewPlanRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 3,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 3) null else 3 },
                            )
                            OverviewSubAgentRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 4,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 4) null else 4 },
                            )
                            OverviewMcpRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 5,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 5) null else 5 },
                            )
                            OverviewSkillRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 6,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 6) null else 6 },
                            )
                            OverviewTodoRow(
                                viewModel = viewModel,
                                expanded = state.expandedRowIndex == 7,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 7) null else 7 },
                            )
                            OverviewRawMessagesRow(
                                viewModel = viewModel,
                                rawVm = rawVm,
                                expanded = state.expandedRowIndex == 8,
                                onToggle = { state.expandedRowIndex = if (state.expandedRowIndex == 8) null else 8 },
                            )
                        }
                    }
                }
            }
        }
    }
}
