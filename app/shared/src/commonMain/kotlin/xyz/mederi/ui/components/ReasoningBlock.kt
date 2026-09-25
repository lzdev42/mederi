package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent

/**
 * 顶层推理块（ReasoningBlock）展开内容的默认最大高度：超过后块内上下滚动，
 * 内容末尾的「展开/收起」按钮可切换为无限高度（真实动态高度），再点缩回限高。
 */
private val ReasoningMaxContentHeight = 200.dp

/**
 * 单独思维链/思考过程折叠面板 (ReasoningBlock)
 * 严格还原极简设计：大脑图标胶囊 + 展开后轻量导轨线。
 *
 * 顶层独立显示（enforceMaxHeight=true）时：展开内容默认限高 [ReasoningMaxContentHeight] 并内部滚动，
 * 内容末尾「展开/收起」按钮可切换为无限高度（真实动态高度）；限高状态下不点按钮也能滚动查看全部。
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 单行微条：整行可点击展开/收起（去卡片化：无背景无边框）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { localExpanded = !isExpanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = BrainIcon,
                contentDescription = null,
                tint = if (isReasoningActive) colors.accentPrimary.copy(alpha = pulseAlpha) else colors.textMuted,
                modifier = Modifier.size(13.dp)
            )

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

            Text(
                text = label,
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            // 箭头紧跟文字，两格间距（spacedBy 已提供 6dp，此处再加 2dp 间距通过宽 Spacer 模拟"两个空格"）
            Spacer(modifier = Modifier.width(2.dp))

            ExpandChevron(expanded = isExpanded, tint = colors.textSecondary, size = 11.dp)
        }

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

        // 展开后的思考旁白内容（左侧细垂直导轨线）。
        // enforceMaxHeight：默认限高 + 块内滚动，内容末尾「展开/收起」按钮切换无限高度（真实动态高度），
        // 限高状态下不点按钮也能上下滚动查看全部；WorkTraceCard 内子项（enforceMaxHeight=false）保持无限高。
        ExpandableContent(expanded = isExpanded && text.isNotBlank()) {
            val railColor = if (colors.isDark) Color(0xFF2E3240) else Color(0xFFD0D5DD)
            Column(
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
                    .padding(vertical = 3.dp, horizontal = 4.dp)
                    .drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = Offset(strokeWidth / 2f, 0f),
                            end = Offset(strokeWidth / 2f, size.height),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MarkdownView(
                    content = text,
                    modifier = Modifier.fillMaxWidth(),
                    enableScrollOverride = false,
                    markdownTheme = rememberMederiMarkdownTheme(compact = true)
                )

                if (enforceMaxHeight) {
                    // 底部切换按钮：位于内容末尾、随内容滚动，恒显示
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                isUnbounded = !isUnbounded
                                DebugLog.debug(
                                    "UI",
                                    "ReasoningBlock isUnbounded toggled to: $isUnbounded, contentKey=$contentKey"
                                )
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isUnbounded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isUnbounded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                            ),
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
