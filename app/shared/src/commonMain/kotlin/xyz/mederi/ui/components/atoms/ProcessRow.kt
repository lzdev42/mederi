package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.LocalMederiColors

/**
 * 过程层「一行折叠头」唯一外观壳 (ProcessRow)
 *
 * 本组件是「过程层一行」的**唯一外观**：折叠态的单行 header（圆角 / hover 才显形的底色 /
 * 整行点击 / 前置图标 / 单行主文案 / 尾部箭头）+ 展开内容容器（左侧导轨线 + 内边距 +
 * 展开动画），全部收在这里。
 *
 * 共用方（三处，历史上各写一遍）：思考行（`ReasoningBlock`）、工作过程栏（`WorkTraceCard`）、
 * 工具动作行（`ToolCallsBlock` 的 `ToolActionGroupRow`）。三者本就属于同一层语义——「过程进行中 /
 * 可展开看细节」，但此前圆角（4.dp / 6.dp 混用）、hover 底色（有无）、行内边距（2/4/6.dp 混用）、
 * 箭头尺寸（11.dp / 12.dp）各写各的，改一处就会让三行长得不一样。现统一到本壳，三处只保留
 * 各自的「图标 + 文案 + 状态色 + 展开内容」。
 *
 * 组件本身**不携带任何业务语义**：是否失败、是否进行中、是否可展开、展开后是什么内容，全部由
 * 调用方通过参数与 content 决定；同一个壳可被任意「一行 + 展开区」结构复用。
 *
 * 视觉定义：过程层**无外壳**——不描边、不加常态底色、不加阴影，只有 hover 时才浮出
 * [xyz.mederi.theme.MederiColors.surfaceHover] 底色。
 *
 * @param expanded 当前是否展开（箭头朝向与展开动画的唯一真理源）
 * @param onExpandedChange 点击整行时回调新的展开态
 * @param modifier 外部修饰符，排在根 Column 链最前
 * @param icon 前置图标（16.dp，纯装饰）
 * @param iconTint 图标着色，默认弱化文字色
 * @param label 单行主文案（超长省略号截断）
 * @param labelColor 主文案着色，默认次要文字色
 * @param clickable 是否响应交互。false 时整行不可点、无 hover 底色、且不渲染尾部箭头
 *   （用于「该行暂不可展开」的场合，如正文尚未到达的推理状态行）
 * @param leading 前置区自定义内容（可选）。非 null 时取代默认 [icon] 渲染，用于表达
 *   「运行中」等非静态图标状态（如旋转进度条）
 * @param summary 可选当前活动单行摘要，占剩余宽度并将箭头置于最右侧
 * @param summaryContent 可选自定义摘要，优先于 summary（推理流式单行预览）
 * @param labelRunning 当前标题是否显示运行中扫光（遵循可见性与减弱动效）
 * @param content 展开内容（左侧自带导轨线容器）
 */
@Composable
fun ProcessRow(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color = LocalMederiColors.current.textMuted,
    label: String,
    labelColor: Color = LocalMederiColors.current.textSecondary,
    clickable: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    summary: String? = null,
    summaryContent: (@Composable () -> Unit)? = null,
    labelRunning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current

    // hover 底色：过程层常态透明，鼠标移入才浮出（120ms 淡入）
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val headerBg by animateColorAsState(
        if (isHovered || isFocused) colors.surfaceHover else Color.Transparent,
        LocalWorkspaceMotion.current.feedbackSpec()
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WorkspaceStyle.ControlHeight)
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (clickable) {
                        Modifier
                            .background(headerBg)
                            .expansionSemantics(expanded, onExpandedChange)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button
                            ) { onExpandedChange(!expanded) }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leading != null) {
                leading()
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(WorkspaceStyle.IconSize)
                )
            }

            ProcessLabel(
                text = label,
                color = labelColor,
                running = labelRunning,
                modifier = if (summary == null && summaryContent == null) Modifier.weight(1f, fill = false) else Modifier.widthIn(max = 200.dp)
            )
            if (summaryContent != null) {
                Box(Modifier.weight(1f)) { summaryContent() }
            } else if (summary != null) {
                Crossfade(targetState = summary, animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
                    modifier = Modifier.weight(1f), label = "processActivity") { activity ->
                    Text(text = activity, color = colors.textMuted, style = WorkspaceStyle.Caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (clickable) {
                // 箭头紧跟文字：spacedBy 已给 6dp，再补 2.dp 模拟「两个空格」的视觉间隔
                Spacer(modifier = Modifier.width(2.dp))
                ExpandChevron(expanded = expanded, tint = colors.textSecondary, size = WorkspaceStyle.IconSize)
            }
        }

        // 展开内容容器：左侧一条导轨线（贯通）标出「这是本行的下级内容」，
        // 导轨线对齐上方 Header 图标正中心 (horizontal 6dp + 16dp/2 = 14dp)，
        // 内边距 padding(start=24.dp, end=12.dp) 给内容和导轨线之间留出 9dp 留白，右侧留出 12dp 边距防止顶边。
        ExpandableContent(
            expanded = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val lineX = 14.dp.toPx()
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = colors.divider,
                        start = Offset(lineX, 0f),
                        end = Offset(lineX, size.height),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
                .padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
        ) {
            content()
        }
    }
}
