package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.reasoning_thinking
import mederi.app.shared.generated.resources.reasoning_thinking_for
import mederi.app.shared.generated.resources.reasoning_thought
import mederi.app.shared.generated.resources.reasoning_thought_for
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.ui.DebugLog
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.ProcessRow

/**
 * 顶层推理块（ReasoningBlock）展开内容的文本视口**最大**高度：推理文本在视口内独立上下滚动，
 * 内容超出该上限才出现滚动条；短内容按实际高度收缩（不再撑出固定 200.dp 空盒）。
 * 视口溢出时，容器底部出现「展开/收起」按钮，点击可切换为无限高度（展示全量文本），再点缩回限高。
 */
private val ReasoningMaxContentHeight = 200.dp

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 * 严格还原极简设计：大脑图标胶囊 + 展开后轻量导轨线。
 *
 * 折叠头（行高 / 圆角 / hover / 箭头 / 导轨线）统一由 `atoms/ProcessRow.kt` 提供，
 * 本组件只负责「图标 + 文案 + 进行中脉冲色 + 展开后的推理正文视口」。
 *
 * 顶层独立显示（enforceMaxHeight=true）时：展开内容高度自适应（wrap content），上限 [ReasoningMaxContentHeight]
 * 加在可滚动文本视口自己身上——内容短就短，超过上限才在视口内独立滚动，底部「展开/收起」按钮仅在溢出时出现；
 * 点击切换为无限高度全量展示。
 * 位于 WorkTraceCard 内（enforceMaxHeight=false）时保持无限高——卡整体已限高滚动，子项不再重复限制。
 */
@Composable
fun ReasoningBlock(
    text: String,
    durationMs: Long = 0,
    isStreaming: Boolean = false,
    isReasoningActive: Boolean = false,
    modifier: Modifier = Modifier,
    enforceMaxHeight: Boolean = true,
    contentKey: String? = null,
) {
    if (text.isBlank() && !isReasoningActive) return

    val colors = LocalMederiColors.current
    var localExpanded by remember { mutableStateOf(false) }
    val isExpanded = localExpanded
    // 内容是否无限高：默认限高 + 块内滚动，点内容末尾按钮切换为全部摊开，再点缩回限高
    var isUnbounded by remember(contentKey) { mutableStateOf(false) }
    val shouldScroll = shouldEnableReasoningScroll(enforceMaxHeight, isUnbounded)
    DebugLog.debug(
        "UI",
        "ReasoningBlock compose: contentKey=$contentKey, enforceMaxHeight=$enforceMaxHeight, isUnbounded=$isUnbounded, shouldScroll=$shouldScroll"
    )

    // 限高滚动容器 + 自动贴底（stick-to-bottom，与聊天列表同一套语义）：
    // 默认自动滚动到底部（流式时跟随最新推理），用户手动滚开即停止；滚回底部恢复跟随。
    val contentScroll = rememberScrollState()
    var stickToBottom by remember(contentKey) { mutableStateOf(true) }
    // 首次自动滚底完成前禁用位置跟踪：否则初始布局在顶部时跟踪 effect 会立刻把 stickToBottom
    // 打成 false，与"打开即贴底"互相打架（与聊天列表 bottomTrackingEnabled 同源防抖）。
    var bottomTrackingEnabled by remember(contentKey) { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // 折叠头文案：进行中 / 已完成 × 有无耗时，四态（资源文案与耗时拼法一字不改）
    val durationText = if (durationMs > 0) {
        val sec = durationMs / 1000
        if (sec > 0) "${sec}s" else "${durationMs}ms"
    } else ""

    val label = when {
        isReasoningActive && durationText.isNotBlank() -> stringResource(Res.string.reasoning_thinking_for, durationText)
        isReasoningActive -> stringResource(Res.string.reasoning_thinking)
        durationText.isNotBlank() -> stringResource(Res.string.reasoning_thought_for, durationText)
        else -> stringResource(Res.string.reasoning_thought)
    }

    // 进行中：800ms 脉冲的 accent 色（图标沿用原有脉冲色，文案同色高亮）
    val activePulseColor = colors.accentPrimary.copy(alpha = pulseAlpha)

    // 位置跟踪：滚动落定后按"是否仍在底部"更新贴底意图。
    // 用户滚动离开底部 → !canScrollForward=false → stickToBottom=false（停止跟随）；
    // 用户滚回底部 → canScrollForward=false → stickToBottom=true（恢复跟随）。
    LaunchedEffect(contentKey, shouldScroll) {
        if (!shouldScroll) return@LaunchedEffect
        snapshotFlow { contentScroll.value }
            .collect { _ ->
                if (!bottomTrackingEnabled) return@collect
                if (contentScroll.isScrollInProgress) return@collect
                stickToBottom = !contentScroll.canScrollForward
            }
    }

    // 跟随滚动（tail -f）：贴底期间内容增长（流式推理持续变高 / maxValue 增大）自动吸附到底部。
    // 首次滚底落地后启用位置跟踪。scrollTo 是同步瞬时操作，落定后的下一帧 maxValue 无变化即停。
    LaunchedEffect(contentKey, shouldScroll) {
        if (!shouldScroll) return@LaunchedEffect
        snapshotFlow { contentScroll.maxValue }
            .collect { max ->
                if (stickToBottom && !contentScroll.isScrollInProgress && contentScroll.canScrollForward) {
                    contentScroll.scrollTo(max)
                    stickToBottom = true
                    bottomTrackingEnabled = true
                }
            }
    }

    // 文本是否溢出视口（maxValue > 0 等价于内容高度 > 视口上限）：决定底部切换按钮是否出现
    val hasOverflow = shouldScroll && contentScroll.maxValue > 0
    val showHeightToggle = shouldShowHeightToggle(enforceMaxHeight, isUnbounded, hasOverflow)

    // 思考行折叠头（整行可点，展开后轻量导轨线 + 推理正文视口）
    // 说明：文案为空（刚开始流式、尚无正文）时不进入展开态——与原实现一致，
    // 避免点开后出现一个空视口 + 导轨线残影。
    ProcessRow(
        expanded = isExpanded && text.isNotBlank(),
        onExpandedChange = { localExpanded = it },
        modifier = modifier.fillMaxWidth(),
        icon = BrainIcon,
        iconTint = if (isReasoningActive) activePulseColor else colors.textMuted,
        label = label,
        labelColor = if (isReasoningActive) activePulseColor else colors.textSecondary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (shouldScroll) {
                        Modifier.containScroll()
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 推理文本视口：高度自适应内容，上限 ReasoningMaxContentHeight 由本节点自己承担
            // （不能用 weight(1f)：Column 中带 weight 的子项会被分配满整个最大约束，
            //   父级换成 heightIn(max) 也会被撑成固定高——这正是之前 200.dp 空盒的成因）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shouldScroll) {
                            Modifier
                                .containScroll()
                                .heightIn(max = ReasoningMaxContentHeight)
                                .verticalScroll(contentScroll)
                        } else {
                            Modifier
                        }
                    )
            ) {
                MarkdownView(
                    content = text,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false,
                    markdownTheme = rememberMederiMarkdownTheme(compact = true)
                )
            }

            if (showHeightToggle) {
                // 底部切换按钮：仅内容溢出视口时出现（点开摊开后恒显示），不随推理内容滚动
                MederiGhostButton(
                    text = stringResource(
                        if (isUnbounded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                    ),
                    icon = if (isUnbounded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    onClick = {
                        isUnbounded = !isUnbounded
                        DebugLog.debug(
                            "UI",
                            "ReasoningBlock isUnbounded toggled to: $isUnbounded, contentKey=$contentKey"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
