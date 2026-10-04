package xyz.mederi.ui.components.atoms

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * @param icon 前置图标（14.dp，纯装饰）
 * @param iconTint 图标着色，默认弱化文字色
 * @param label 单行主文案（超长省略号截断）
 * @param labelColor 主文案着色，默认次要文字色
 * @param clickable 是否响应交互。false 时整行不可点、无 hover 底色、且不渲染尾部箭头
 *   （用于「该行暂不可展开」的场合，如准备中的子代理动作行）
 * @param leading 前置区自定义内容（可选）。非 null 时取代默认 [icon] 渲染，用于表达
 *   「运行中」等非静态图标状态（如旋转进度条）
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current

    // hover 底色：过程层常态透明，鼠标移入才浮出（120ms 淡入）
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val headerBg by animateColorAsState(
        if (isHovered) colors.surfaceHover else Color.Transparent,
        tween(120)
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (clickable) {
                        Modifier
                            .background(headerBg)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
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
                    modifier = Modifier.size(14.dp)
                )
            }

            Text(
                text = label,
                color = labelColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (clickable) {
                // 箭头紧跟文字：spacedBy 已给 6dp，再补 2.dp 模拟「两个空格」的视觉间隔
                Spacer(modifier = Modifier.width(2.dp))
                ExpandChevron(expanded = expanded, tint = colors.textSecondary, size = 12.dp)
            }
        }

        // 展开内容容器：左侧一条导轨线（贯通）标出「这是本行的下级内容」，
        // 导轨线对齐上方 Header 图标正中心 (horizontal 6dp + 14dp/2 = 13dp)，
        // 内边距 padding(start=22.dp, end=12.dp) 给内容和导轨线之间留出 9dp 留白，右侧留出 12dp 边距防止顶边。
        ExpandableContent(
            expanded = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val lineX = 13.dp.toPx()
                    val strokeWidth = 2.dp.toPx()
                    drawLine(
                        color = colors.divider,
                        start = Offset(lineX, 0f),
                        end = Offset(lineX, size.height),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
                .padding(start = 22.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
        ) {
            content()
        }
    }
}
