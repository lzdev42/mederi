package xyz.mederi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_lines
import mederi.app.shared.generated.resources.attachment_reader
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors

/**
 * 独立长文 Markdown 产物行（DocumentArtifactCard，02-components §2.7 document-artifact-card：chip 非 card）
 *
 * 遵循 Mederi 设计语言：
 * - 内容自适应 Row chip：圆角 8dp、surfaceCard 底 + divider 描边、padding 4/10dp、
 *   hover 底 surfaceHover + 描边 borderStrong（120ms 过渡）；
 * - 图标 FileText 13dp accentText（生成中换 10dp statusWorking 细圈状态指示，替代原 34dp 图标盒与 shimmer）；
 * - 中间两行：主标题（12sp/500 textPrimary，maxLines 1）+ 元信息（时间 / 行数与字符数，11sp textSecondary）；
 * - 尾部“阅读器”胶囊（Sidebar 11dp + attachment_reader 11sp，accentText）；
 * - 点击整行滑出右侧扩展窗口并流式排版渲染。
 */
@Composable
fun DocumentArtifactCard(
    title: String,
    lineCount: Int,
    charCount: Int,
    createdAt: Long,
    isStreaming: Boolean,
    isCompleted: Boolean,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isRunning = isStreaming && !isCompleted

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) colors.surfaceHover else colors.surfaceCard,
        animationSpec = tween(120),
        label = "artifactChipBg",
    )
    val border by animateColorAsState(
        targetValue = if (hovered) colors.borderStrong else colors.divider,
        animationSpec = tween(120),
        label = "artifactChipBorder",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, onClick = { onOpenInExtension() })
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 生成中：小状态指示；完成：文件图标
        if (isRunning) {
            CircularProgressIndicator(
                color = colors.statusWorking,
                strokeWidth = 1.3.dp,
                modifier = Modifier.size(10.dp)
            )
        } else {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(13.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val timeStr = if (createdAt > 0) xyz.mederi.formatMessageTime(createdAt) else ""
            val meta = listOfNotNull(
                timeStr.takeIf { it.isNotBlank() },
                stringResource(Res.string.attachment_lines, lineCount),
                formatCharCount(charCount)
            ).joinToString(" · ")
            Text(
                text = meta,
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 尾部“阅读器”胶囊（icon + 文案，accentText）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Sidebar,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = stringResource(Res.string.attachment_reader),
                color = colors.accentText,
                fontSize = 11.sp
            )
        }
    }
}
