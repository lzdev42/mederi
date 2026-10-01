package xyz.mederi.ui.components.atoms

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
import xyz.mederi.theme.LocalMederiColors

/**
 * 通用单行轻量通知微条 (StatusStrip)
 *
 * 本组件是这类微条**唯一的外观壳**：容器（圆角 / 底色 / hover 底色 / 边框 / 可选流光动效）、
 * 前置小图标（13.dp，可选旋转）、单行省略文本、右侧胶囊、整行点击，全部收在这里。
 *
 * 组件本身**不携带任何状态语义**——边框色、图标、图标色、文案色、是否流光、是否旋转、
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
 * @param textColor 文本着色，默认主文字色
 * @param borderColor 边框色，默认分割线色
 * @param spinning 前置图标是否匀速旋转（1000ms/圈；false 时不创建无限动画，零开销）
 * @param animated 是否启用流光边框动效（[WorkingAnimationStyle.BorderBeam]）
 * @param actionLabel 右侧胶囊文案；为 null 时不渲染胶囊
 * @param onAction 整行点击回调；为 null 时不挂 pointerHoverIcon / clickable（整行不可点）
 */
@Composable
fun StatusStrip(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalMederiColors.current.textSecondary,
    iconContentDescription: String? = null,
    textColor: Color = LocalMederiColors.current.textPrimary,
    borderColor: Color = LocalMederiColors.current.divider,
    spinning: Boolean = false,
    animated: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current

    // hover 态：整行 Enter/Exit 驱动，底色与右侧胶囊的 alpha 都靠它
    var isHovered by remember { mutableStateOf(false) }

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
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered && onAction != null) colors.surfaceHover else colors.surfaceCode)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .workingAnimation(
                style = WorkingAnimationStyle.BorderBeam,
                enabled = animated,
                shape = RoundedCornerShape(6.dp),
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

        // 2. 单行状态文本（过长省略号截断）
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 3. 右侧胶囊（随整行 onAction 一起生效，胶囊自身不单独挂 click）
        if (actionLabel != null) {
            Spacer(modifier = Modifier.width(10.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentSecondary.copy(alpha = if (isHovered) 0.18f else 0.08f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = colors.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
