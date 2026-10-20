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
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiMarkdown
import xyz.mederi.ui.components.atoms.ProcessRow

/**
 * 顶层推理块（ReasoningBlock）文本视口最大高度：
 * 折叠态（未点开展开按钮）高度自适应（wrap content），最高高度上限为 [ReasoningMaxContentHeight]（200.dp）；
 * 展开态（点开「展开」按钮）解除限高与滚动，按真实内容高度无限展开（由外层 LazyColumn 滚动）；
 * 折叠态超过上限才在视口内独立上下滚动；溢出时提供底部「展开/收起」按钮，点击切换为无限高真实高度。
 */
private val ReasoningMaxContentHeight = 200.dp

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 * 严格还原极简设计：大脑图标胶囊 + 展开后轻量导轨线。
 *
 * 折叠头（行高 / 圆角 / hover / 箭头 / 导轨线）统一由 `atoms/ProcessRow.kt` 提供，
 * 本组件只负责「图标 + 文案 + 进行中脉冲色 + 展开后的推理正文视口」。
 *
 * 顶层独立显示（enforceMaxHeight=true）时：展开内容高度自适应（wrap content），
 * 未点展开按钮时最高高度上限为 [ReasoningMaxContentHeight]，内容溢出时在视口内滚动 + 流式即时吸底；
 * 底部「展开/收起」按钮在溢出或摊开时出现，点击可切换为抬大限高上限（500dp）的展开态，在视口内滚动看完整内容。
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
    // 用 contentKey 锚定展开态：防止 LazyColumn 复用串位
    var localExpanded by remember(contentKey) { mutableStateOf(false) }
    val isExpanded = localExpanded
    // 内容是否无限高：默认限高 + 块内滚动，点内容末尾按钮切换为全部摊开，再点缩回限高
    var isUnbounded by remember(contentKey) { mutableStateOf(false) }
    val shouldScroll = enforceMaxHeight

    // 限高滚动容器状态
    val contentScroll = rememberScrollState()

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

    // 流式吸底：限高视口期间文本增长（maxValue 增大）即时同步吸附到底部，无异步延迟
    LaunchedEffect(text, isStreaming, shouldScroll) {
        if (!shouldScroll || !isStreaming) return@LaunchedEffect
        if (contentScroll.maxValue > 0) contentScroll.scrollTo(contentScroll.maxValue)
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
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 推理文本视口：折叠态最高高度上限为 ReasoningMaxContentHeight (200.dp)，溢出后在视口内滚动；
            // 点击底部展开按钮后解除限高与滚动，按真实内容高度无限展开（由外层 LazyColumn 滚动）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shouldScroll && !isUnbounded) {
                            Modifier
                                .containScroll()
                                .heightIn(max = ReasoningMaxContentHeight)
                                .verticalScroll(contentScroll)
                        } else {
                            Modifier
                        }
                    )
            ) {
                MederiMarkdown(
                    content = text,
                    isStreaming = isStreaming,
                    compact = true,
                    enableScrollOverride = false,
                    modifier = Modifier.fillMaxWidth()
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
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
