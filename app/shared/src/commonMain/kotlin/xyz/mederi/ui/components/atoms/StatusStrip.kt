package xyz.mederi.ui.components.atoms

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import xyz.mederi.theme.LocalMederiColors

/**
 * 通用单行轻量通知微条 (StatusStrip)
 *
 * 本组件是这类微条**唯一的外观壳**：容器（8dp 圆角 / surfaceCard 底 / hover 边框换 borderStrong /
 * 可选流光动效）、前置小图标（13.dp，可选旋转）、单行省略文本、右侧「文字 + 箭头」入口、整行点击，
 * 全部收在这里。
 *
 * 设计规格 = **对象层卡片壳**（与 `DocumentArtifactCard` 同源）：底色静止、圆角 8dp、1dp 描边，
 * 状态**只由图标色与边框色表达，文字一律主文字色不染色**——失败/取消时整行文字保持 textPrimary，
 * 避免整片红字造成的视觉噪声（红色由图标与 35% 边框承载）。右侧入口不再用带底色的胶囊，
 * 改为「accentText 文字 + ChevronRight 箭头」，hover 时整体切 accentHover。
 *
 * 组件本身**不携带任何状态语义**——边框色、图标、图标色、是否流光、是否旋转、
 * 是否可点击，全部由调用方通过参数决定；同一个外观壳可以被任意业务语义复用。
 *
 * 历史背景：子代理派发/运行条（`ToolCallsBlock.kt` 的 `SubagentCallStrip`）与终态通知条
 * （`EventMessageCard.kt`）曾是两份逐字重复的实现（圆角/底色/border/hover/pointerInput/
 * 胶囊各写一遍），改一处忘另一处就会出现两条微条长得不一样的事故。现统一到本组件，
 * 两处只保留各自的「文案 + 图标 + 颜色 + 动效开关」决策。
 *
 * @param text 单行状态文本（超长自动省略号截断）
 * @param icon 前置小图标（13.dp）
 * @param modifier 外部修饰符，排在容器链最前
 * @param iconTint 图标着色，默认次要文字色
 * @param iconContentDescription 图标无障碍描述（纯装饰图标传 null）
 * @param borderColor **静止态**边框色（hover 时自动换 borderStrong，120ms 过渡），默认分割线色
 * @param spinning 前置图标是否匀速旋转（1000ms/圈；false 时不创建无限动画，零开销）
 * @param animated 是否启用流光边框动效（[WorkingAnimationStyle.BorderBeam]）
 * @param actionLabel 右侧「文字 + 箭头」入口文案；为 null 时不渲染入口
 * @param onAction 整行点击回调；为 null 时不挂 pointerHoverIcon / clickable（整行不可点）
 */
@Composable
fun StatusStrip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalMederiColors.current.textSecondary,
    iconContentDescription: String? = null,
    borderColor: Color = LocalMederiColors.current.divider,
    spinning: Boolean = false,
    animated: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current

    // 对象层卡片壳：8dp 圆角（clip / border / 流光三处共用同一 shape，避免重复字面量）
    val shellShape = RoundedCornerShape(8.dp)

    // hover 态：整行 Enter/Exit 驱动，底色与边框色都靠它（与 DocumentArtifactCard 同一套 120ms 过渡）
    var isHovered by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = if (isHovered && onAction != null) colors.surfaceHover else colors.surfaceCard,
        animationSpec = tween(120),
        label = "statusStripBg",
    )
    val shellBorderColor by animateColorAsState(
        targetValue = if (isHovered) colors.borderStrong else borderColor,
        animationSpec = tween(120),
        label = "statusStripBorder",
    )

    // 前置图标：旋转与否只影响 modifier；spinning=false 时不创建无限动画
    val iconModifier = if (spinning) {
        val rotation by rememberInfiniteTransition(label = "status_strip_spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "status_strip_angle"
        )
        Modifier.size(13.dp).rotate(rotation)
    } else {
        Modifier.size(13.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shellShape)
            .background(bgColor)
            .border(1.dp, shellBorderColor, shellShape)
            .workingAnimation(
                style = WorkingAnimationStyle.BorderBeam,
                enabled = animated,
                shape = shellShape,
                primaryColor = colors.accentSecondary
            )
            .then(
                if (onAction != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onAction() }
                } else Modifier
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> isHovered = true
                            PointerEventType.Exit -> isHovered = false
                        }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 左侧微型状态图标
        Icon(
            imageVector = icon,
            contentDescription = iconContentDescription,
            tint = iconTint,
            modifier = iconModifier
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 2. 单行状态文本（过长省略号截断；固定主文字色，状态不靠文字染色表达）
        Text(
            text = text,
            color = colors.textPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 3. 右侧入口：「文字 + 箭头」，无底色胶囊（随整行 onAction 一起生效，自身不单独挂 click）
        if (actionLabel != null) {
            Spacer(modifier = Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = if (isHovered) colors.accentHover else colors.accentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (isHovered) colors.accentHover else colors.accentText,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
