package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.ui.components.atoms.rememberWorkspaceTaskVisibility
import xyz.mederi.ui.components.atoms.workspaceTaskPulseAlpha

/**
 * 会话消息列表骨架占位：会话已选中但首帧快照未到（首次打开、观察流在途）时使用。
 *
 * 开关信号 = WorkspaceViewModel 的 `conversationId != null && !isAttached`——快照一到
 * isAttached 置真，骨架即被正常内容替换；真空调会话（快照已到、消息为空）显示欢迎页，不显示骨架。
 * 运动语言与任务循环一致：单色呼吸 alpha（[workspaceTaskPulseAlpha]），
 * 减弱动态模式下静态呈现；无任何文本，无需 i18n。
 */
@Composable
internal fun ChatListSkeleton(
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp = ChatLayout.contentMaxWidth,
    horizontalPadding: Dp = 24.dp,
) {
    val colors = LocalMederiColors.current
    val visibility = rememberWorkspaceTaskVisibility()
    val blockColor = colors.surfaceHover.copy(
        alpha = workspaceTaskPulseAlpha(running = true, visible = visibility.visible)
    )

    Column(
        modifier = modifier
            .widthIn(max = contentMaxWidth)
            .fillMaxWidth()
            .padding(start = horizontalPadding, top = 20.dp, end = horizontalPadding)
            .then(visibility.modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 第一轮：用户气泡（右对齐）+ 助手正文行
        SkeletonBlock(
            widthFraction = 0.52f,
            height = 38.dp,
            shape = RoundedCornerShape(MederiRadius.Bubble),
            color = blockColor,
            alignment = Alignment.CenterEnd,
        )
        SkeletonBlock(widthFraction = 0.96f, height = 12.dp, shape = RoundedCornerShape(MederiRadius.Square), color = blockColor)
        SkeletonBlock(widthFraction = 0.88f, height = 12.dp, shape = RoundedCornerShape(MederiRadius.Square), color = blockColor)
        SkeletonBlock(widthFraction = 0.42f, height = 12.dp, shape = RoundedCornerShape(MederiRadius.Square), color = blockColor)

        // 第二轮：用户气泡 + 助手正文行 + 工具卡片
        SkeletonBlock(
            widthFraction = 0.38f,
            height = 34.dp,
            shape = RoundedCornerShape(MederiRadius.Bubble),
            color = blockColor,
            alignment = Alignment.CenterEnd,
        )
        SkeletonBlock(widthFraction = 0.92f, height = 12.dp, shape = RoundedCornerShape(MederiRadius.Square), color = blockColor)
        SkeletonBlock(widthFraction = 0.64f, height = 12.dp, shape = RoundedCornerShape(MederiRadius.Square), color = blockColor)
        SkeletonBlock(
            widthFraction = 0.8f,
            height = 68.dp,
            shape = RoundedCornerShape(MederiRadius.Card),
            color = blockColor,
        )
    }
}

/** 单个占位块；[widthFraction] 为 0..1 的宽度占比，块自身决定水平对齐。 */
@Composable
private fun SkeletonBlock(
    widthFraction: Float,
    height: Dp,
    shape: RoundedCornerShape,
    color: Color,
    alignment: Alignment? = null,
) {
    val block = Modifier
        .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
        .height(height)
        .clip(shape)
        .background(color)
    if (alignment != null) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
            Box(modifier = block)
        }
    } else {
        Box(modifier = block)
    }
}

