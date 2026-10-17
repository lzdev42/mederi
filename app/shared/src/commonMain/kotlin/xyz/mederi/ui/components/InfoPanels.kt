package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_refresh_changes
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.DiffView
import xyz.mederi.ui.ArtifactItem
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiSurfaceButton
import xyz.mederi.ui.components.atoms.PanelEmptyState

@Composable
internal fun DiffPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val diffs = viewModel.diffItems
    val selectedFilePath = viewModel.selectedDiffFilePath ?: diffs.firstOrNull()?.filePath

    if (diffs.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.GitCommit,
            title = stringResource(Res.string.dock_diff_empty),
            hint = stringResource(Res.string.dock_diff_empty_hint),
            action = {
                // 空态刷新动作：收敛为 MederiSurfaceButton（次级凸起变体，icon + 11sp 文本）
                MederiSurfaceButton(
                    text = stringResource(Res.string.dock_refresh_changes),
                    onClick = { viewModel.openDiff() },
                    icon = FeatherIcons.RefreshCw,
                )
            }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceCard)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                diffs.forEach { diff ->
                    val isSelected = diff.filePath == selectedFilePath
                    val fileName = diff.filePath.substringAfterLast("/")
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) colors.divider else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { viewModel.selectDiffFile(diff.filePath) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = fileName,
                            color = if (isSelected) colors.textPrimary else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (diff.additions > 0) {
                            Text("+${diff.additions}", color = colors.accentSuccess, fontSize = 10.sp)
                        }
                        if (diff.deletions > 0) {
                            Text("-${diff.deletions}", color = colors.accentDanger, fontSize = 10.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)

            val currentDiff = diffs.find { it.filePath == selectedFilePath } ?: diffs.first()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                DiffView(
                    oldText = currentDiff.before,
                    newText = currentDiff.after,
                    filePath = currentDiff.filePath,
                    modifier = Modifier.fillMaxSize(),
                    showHeader = false,
                )
            }
        }
    }
}


@Composable
internal fun ArtifactsPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val items = viewModel.artifactItems
    val activeId = viewModel.activeArtifactId

    if (items.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.File,
            title = stringResource(Res.string.dock_artifact_empty),
            hint = stringResource(Res.string.dock_artifact_empty_hint)
        )
    } else {
        val activeItem = items.find { it.id == activeId } ?: items.last()
        Column(modifier = Modifier.fillMaxSize()) {
            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceCard)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { item ->
                        val isSelected = item.id == activeItem.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                                .clickable { viewModel.openArtifact(item.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = item.title,
                                color = if (isSelected) colors.accentPrimary else colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.divider)
            }

            when (activeItem) {
                is ArtifactItem.Image -> {
                    ImageViewerTabContent(
                        title = activeItem.title,
                        imageUrl = activeItem.imageUrl,
                        colors = colors
                    )
                }
                is ArtifactItem.Text -> {
                    val liveItem = viewModel.getLiveArtifactItem(activeItem.id) ?: activeItem
                    TextReaderTabContent(
                        title = liveItem.title,
                        content = liveItem.content,
                        lineCount = liveItem.lineCount,
                        charCount = liveItem.charCount,
                        isStreaming = liveItem.isStreaming,
                        colors = colors
                    )
                }
            }
        }
    }
}

/**
 * Token 短格式（自动切换单位）：
 * ≥1_000_000 显示一位小数 m（4_200_000 → 4.2m），
 * ≥1000 显示一位小数 k（42_800 → 42.8k），否则原值。
 */
internal fun formatTokenShort(tokens: Long): String {
    return when {
        tokens >= 1_000_000 -> {
            val m = tokens / 1_000_000
            val dec = (tokens % 1_000_000) / 100_000
            "${m}.${dec}m"
        }
        tokens >= 1000 -> {
            val k = tokens / 1000
            val dec = (tokens % 1000) / 100
            "${k}.${dec}k"
        }
        else -> tokens.toString()
    }
}

/** 参考费用展示（三位小数收敛），模块内可见（overview 行复用）。 */
internal fun formatCost(cost: Double): String {
    return if (cost < 0.001) "0.000" else (kotlin.math.round(cost * 1000) / 1000.0).toString()
}
