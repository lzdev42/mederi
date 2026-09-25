package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.diff_files_changed
import mederi.app.shared.generated.resources.diff_review
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors

/**
 * 单个文件的变更行组件（对照用户图 1 与图 2 列表项样式）。
 */
@Composable
fun FileDiffRow(
    path: String,
    additions: Int,
    deletions: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val fileName = path.substringAfterLast("/")
    val parentDir = path.substringBeforeLast("/", "").let {
        if (it.isNotEmpty()) ".../$it" else ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 左侧紫色文件图标
        Icon(
            imageVector = FeatherIcons.Edit3,
            contentDescription = null,
            tint = colors.thoughtAccent,
            modifier = Modifier.size(14.dp)
        )

        // 文件名（加粗高亮）
        Text(
            text = fileName,
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )

        // 所在目录缩略（灰色省略）
        if (parentDir.isNotEmpty()) {
            Text(
                text = parentDir,
                color = colors.textMuted.copy(alpha = 0.8f),
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 增删行数统计
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (additions > 0 || (additions == 0 && deletions == 0)) {
                Text(
                    text = "+$additions",
                    color = colors.accentSuccess,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (deletions > 0) {
                Text(
                    text = "-$deletions",
                    color = colors.accentDanger,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * 轮次结束后的文件变更汇总卡片（对照用户图 2）。
 */

@Composable
fun TurnDiffSummaryCard(
    summary: xyz.mederi.core.contract.models.TurnDiffSummaryUi,
    onReviewClick: () -> Unit,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.files.isEmpty()) return
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard.copy(alpha = 0.5f))
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部：N files changed +X -Y 与 Review 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(Res.string.diff_files_changed, summary.files.size, summary.totalAdditions, summary.totalDeletions),
                    color = colors.textPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Review 按钮
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceWorkspace)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(4.dp))
                    .clickable { onReviewClick() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.BookOpen,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = stringResource(Res.string.diff_review),
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

        // 文件列表
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            summary.files.forEach { file ->
                FileDiffRow(
                    path = file.path,
                    additions = file.additions,
                    deletions = file.deletions,
                    onClick = { onFileClick(file.path) }
                )
            }
        }
    }
}
