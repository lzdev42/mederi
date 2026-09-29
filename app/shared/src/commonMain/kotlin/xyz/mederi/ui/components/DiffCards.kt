package xyz.mederi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import xyz.mederi.ui.components.atoms.MederiSurfaceButton

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

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) colors.surfaceHover else Color.Transparent,
        animationSpec = tween(120),
        label = "fileRowBg",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(rowBg)
            .hoverable(interactionSource)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interactionSource, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 左侧紫色文件图标
        Icon(
            imageVector = FeatherIcons.Edit3,
            contentDescription = null,
            tint = colors.accentPrimary,
            modifier = Modifier.size(14.dp)
        )

        // 文件名（等宽高亮）
        Text(
            text = fileName,
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )

        // 所在目录缩略（灰色省略）
        if (parentDir.isNotEmpty()) {
            Text(
                text = parentDir,
                color = colors.textMuted,
                fontSize = 10.5.sp,
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
                    color = colors.successText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (deletions > 0) {
                Text(
                    text = "-$deletions",
                    color = colors.dangerText,
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
            .widthIn(max = 560.dp)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 头部：N files changed +X -Y 与 Review 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.diff_files_changed, summary.files.size, summary.totalAdditions, summary.totalDeletions),
                color = colors.textPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            )

            // Review 按钮
            MederiSurfaceButton(
                text = stringResource(Res.string.diff_review),
                icon = FeatherIcons.BookOpen,
                onClick = onReviewClick
            )
        }

        // 文件列表
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
