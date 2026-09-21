package xyz.mederi.ui.components

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
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_duration
import mederi.app.shared.generated.resources.worktrace_expand
import mederi.app.shared.generated.resources.worktrace_has_failure
import mederi.app.shared.generated.resources.worktrace_steps_count
import mederi.app.shared.generated.resources.worktrace_summary_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.ChatListItem
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent

/**
 * 工作过程（WorkTraceCard）展开内容的最大高度：超过后栏内上下滚动，
 * 避免推理/工具步骤把整个聊天内容顶得过长（折叠条仍可点击收起/展开）。
 */
private val WorkTraceMaxContentHeight = 320.dp

/**
 * 结构化多步工作过程聚合栏 (WorkTraceCard)
 * 统一汇总折叠条：将一轮对话中的全部中间步骤（思考过程、过渡语、工具调用）折叠进一个栏目，
 * 默认显示：[Activity图标] 工作过程 (N 项操作 · 耗时 X.Xs)  ›
 * 展开后，在内部按时序渲染各个步骤子项，左侧带有贯通的微导轨线。
 */
@Composable
fun WorkTraceCard(
    workTrace: ChatListItem.WorkTraceBlock,
    modifier: Modifier = Modifier,
    renderStepItem: @Composable (ChatListItem) -> Unit,
) {
    if (workTrace.items.isEmpty()) return

    val colors = LocalMederiColors.current
    var userChoice by remember(workTrace.key) { mutableStateOf<Boolean?>(null) }
    val autoExpanded = false
    val isExpanded = userChoice ?: autoExpanded

    // 内容是否无限高：默认限高 + 栏内滚动，点块底部按钮切换为全部摊开，再点缩回限高
    var isUnbounded by remember(workTrace.key) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 汇总微栏整行可点击
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { userChoice = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (workTrace.hasFailedTool) {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = colors.accentDanger,
                    modifier = Modifier.size(13.dp)
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Activity,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }

            val durationStr = if (workTrace.totalDurationMs > 0) {
                val sec = workTrace.totalDurationMs / 1000
                if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
            } else ""

            val titleText = run {
                val title = stringResource(Res.string.worktrace_summary_title)
                val details = mutableListOf<String>()
                if (workTrace.totalToolsCount > 0) {
                    details.add(stringResource(Res.string.worktrace_steps_count, workTrace.totalToolsCount))
                }
                if (durationStr.isNotBlank()) {
                    details.add(stringResource(Res.string.worktrace_duration, durationStr))
                }
                if (workTrace.hasFailedTool) {
                    details.add(stringResource(Res.string.worktrace_has_failure))
                }
                if (details.isNotEmpty()) "$title (${details.joinToString(" · ")})" else title
            }

            Text(
                text = titleText,
                color = if (workTrace.hasFailedTool) colors.accentDanger else colors.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.width(2.dp))

            ExpandChevron(expanded = isExpanded, tint = colors.textSecondary, size = 11.dp)
        }

        // 展开后的完整工作轨迹子项（左侧细微导轨线）。
        // 默认最高高度限制：内容超高时栏内上下滚动，避免推理/工具步骤把整个聊天顶得过长；
        // 内容末尾的「展开/收起」按钮随内容滚动、永远位于块的最底部：
        // 点击切换为无限高度（全部摊开，不再限高），再点缩回限高。
        ExpandableContent(expanded = isExpanded) {
            val railColor = if (colors.isDark) Color(0xFF2E3240) else Color(0xFFD0D5DD)
            val traceScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isUnbounded) {
                            Modifier
                                .containScroll()
                                .heightIn(max = WorkTraceMaxContentHeight)
                                .verticalScroll(traceScrollState)
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 2.dp, horizontal = 4.dp)
                    .drawBehind {
                        val strokeWidth = 1.5.dp.toPx()
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
                workTrace.items.forEach { stepItem ->
                    renderStepItem(stepItem)
                }

                // 底部切换按钮：位于内容末尾、随内容滚动（永远在块的最底部），恒显示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            isUnbounded = !isUnbounded
                            DebugLog.debug("UI", "WorkTraceCard isUnbounded toggled to: $isUnbounded")
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
