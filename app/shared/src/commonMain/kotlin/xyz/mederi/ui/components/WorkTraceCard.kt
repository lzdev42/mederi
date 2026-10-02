package xyz.mederi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.ChatListItem
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.ProcessRow

/**
 * 工作过程（WorkTraceCard）展开内容的步骤视口**最大**高度：子步骤在视口内独立上下滚动，
 * 内容超出该上限才出现滚动条；短内容按实际高度收缩（不再撑出固定 320.dp 空盒）。
 * 视口溢出时，容器底部出现「展开/收起」按钮，点击可切换为无限高度（展示全量步骤），再点缩回限高。
 */
private val WorkTraceMaxContentHeight = 320.dp

/**
 * 结构化多步工作过程聚合栏 (WorkTraceCard)
 * 统一汇总折叠条：将一轮对话中的全部中间步骤（思考过程、过渡语、工具调用）折叠进一个栏目，
 * 默认显示：[Activity图标] 工作过程 (N 项操作 · 耗时 X.Xs)  ›
 * 展开后，在内部按时序渲染各个步骤子项，左侧带有贯通的微导轨线。
 *
 * 汇总头（行高 / 圆角 / hover / 箭头 / 展开区导轨线）统一由 `atoms/ProcessRow.kt` 提供，
 * 本组件只负责「图标（失败/正常）+ 状态色 + 标题拼接 + 展开后的步骤视口」。
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

    // 汇总标题文案：栏目名 + 步骤数 + 耗时 + 失败提示（拼接逻辑一字不改）
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

    // 步骤视口滚动状态提到 content 之外：底部按钮的显隐需要读 maxValue（内容是否溢出视口），
    // 必须在组合期求值，不能是展开区内部的局部变量
    val traceScrollState = rememberScrollState()
    val showHeightToggle = shouldShowHeightToggle(
        enforceMaxHeight = true,
        isUnbounded = isUnbounded,
        hasOverflow = !isUnbounded && traceScrollState.maxValue > 0,
    )

    // 汇总折叠栏：整行可点，hover 才浮出 surfaceHover 底色（无常态底色、无描边）。
    ProcessRow(
        expanded = isExpanded,
        onExpandedChange = { userChoice = !isExpanded },
        modifier = modifier.fillMaxWidth(),
        icon = if (workTrace.hasFailedTool) FeatherIcons.AlertCircle else FeatherIcons.Activity,
        iconTint = if (workTrace.hasFailedTool) colors.accentDanger else colors.accentPrimary,
        label = titleText,
        labelColor = if (workTrace.hasFailedTool) colors.accentDanger else colors.textSecondary,
    ) {
        // 展开后的完整工作轨迹子项（导轨线由 ProcessRow 统一绘制）。
        // 容器高度自适应内容（不设固定高），上限 320.dp 由可滚动的步骤视口自己承担
        // （与 ToolCallsBlock 输出块同一套写法：.heightIn(max) + .verticalScroll，无 weight）：
        // 步骤少时按实际高度收缩，避免推理/工具步骤把整个聊天顶得过长；
        // 内容溢出时底部出现「展开/收起」按钮（不随内容滚动），点击切换为无限高度全部摊开，再点缩回。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!isUnbounded) {
                        Modifier.containScroll()
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 步骤列表视口：高度自适应内容，上限 WorkTraceMaxContentHeight 由本节点自己承担
            // （不能用 weight(1f)：Column 中带 weight 的子项会被分配满整个最大约束，
            //   父级换成 heightIn(max) 也会被撑成固定高——这正是之前 320.dp 空盒的成因）
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
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                workTrace.items.forEach { stepItem ->
                    renderStepItem(stepItem)
                }
            }

            // 底部切换按钮：仅步骤溢出视口时出现（点开摊开后恒显示），不随步骤内容滚动
            // （原型 height-toggle 样式：顶部 1dp divider 分割 + 居中 chevron 12dp + 11sp Medium textMuted 文案）
            if (showHeightToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRect(
                                color = colors.divider,
                                topLeft = Offset(0f, 0f),
                                size = Size(size.width, 1.dp.toPx())
                            )
                        }
                        .clickable {
                            isUnbounded = !isUnbounded
                            DebugLog.debug("UI", "WorkTraceCard isUnbounded toggled to: $isUnbounded")
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isUnbounded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
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
