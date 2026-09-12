package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.X
import xyz.mederi.theme.LocalMederiColors

/**
 * 错误显示板（ErrorBoard）：错误/警告的唯一展示出口，位于输入框上方。
 *
 * 单行精简错误 + "详细报告"展开 + 关闭。正常时完全不占空间（null 即不渲染）。
 * 断流（[showContinue]=true）时额外显示"继续"按钮——点击重发 Continue 让模型续写半截回复。
 * StatusBar 只显示 AI 运转状态、SystemInfoBar 只显示资源监控——错误只进这里。
 */
@Composable
fun ErrorBoard(
    errorMessage: String?,
    onShowDetail: () -> Unit,
    onDismiss: () -> Unit,
    showContinue: Boolean = false,
    onContinue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (errorMessage == null) return
    val colors = LocalMederiColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.accentDanger.copy(alpha = 0.12f))
            .border(1.dp, colors.accentDanger.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { onShowDetail() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = FeatherIcons.AlertCircle,
            contentDescription = null,
            tint = colors.accentDanger,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = errorMessage,
            color = colors.accentDanger,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showContinue && onContinue != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accentPrimary.copy(alpha = 0.16f))
                    .border(1.dp, colors.accentPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .clickable { onContinue() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "继续",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onShowDetail() }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = "详细报告",
                color = colors.accentDanger,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.accentDanger,
                modifier = Modifier.size(12.dp),
            )
        }
        Icon(
            imageVector = FeatherIcons.X,
            contentDescription = "关闭",
            tint = colors.accentDanger.copy(alpha = 0.7f),
            modifier = Modifier
                .size(15.dp)
                .clickable { onDismiss() },
        )
    }
}