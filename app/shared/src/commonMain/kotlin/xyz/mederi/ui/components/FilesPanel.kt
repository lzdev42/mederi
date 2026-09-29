package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.GitCommit
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.files_subtab_diff
import mederi.app.shared.generated.resources.files_subtab_tree
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.MederiTabBadge

/**
 * Files 面板双 Tab 外壳（原型 view-files + subtab-header；标准 04 §8 switchFilesSubTab）。
 * Tab 1 = 项目目录树（TreePanelContent，S3 提供）；Tab 2 = 已修改文件（复用 DiffPanelContent）。
 */
@Composable
internal fun FilesPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(FilesTab.Tree) }
    val diffCount = viewModel.diffItems.size
    Column(modifier.fillMaxSize()) {
        // subtab-header：双 Tab 切换栏（原型 .subtab-header：bg-card + 底部 border + 按钮组）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceCard)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SubTabButton(
                tab = FilesTab.Tree,
                selectedTab = selectedTab,
                colors = colors,
                onClick = { selectedTab = FilesTab.Tree }
            )
            SubTabButton(
                tab = FilesTab.Diff,
                selectedTab = selectedTab,
                colors = colors,
                onClick = { selectedTab = FilesTab.Diff },
                badgeCount = diffCount
            )
        }
        HorizontalDivider(color = colors.divider)
        when (selectedTab) {
            FilesTab.Tree -> TreePanelContent(viewModel = viewModel, colors = colors) // S3 提供
            FilesTab.Diff -> DiffPanelContent(viewModel = viewModel, colors = colors)
        }
    }
}

/** Files 面板二级 Tab：Tree = 项目目录 / Diff = 已修改。 */
private enum class FilesTab { Tree, Diff }

/**
 * Files 面板单个 subtab 按钮（原型 .subtab-btn）：icon + label（+ 可选徽标）。
 * 选中态 = accent 色 + Medium 字重 + drawBehind 底部 2dp accent 圆角 underline
 * （原型 .subtab-btn.active::after）；hover = surfaceHover 底。
 */
@Composable
private fun SubTabButton(
    tab: FilesTab,
    selectedTab: FilesTab,
    colors: MederiColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int? = null,
) {
    val selected = tab == selectedTab
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val icon: ImageVector
    val label: String
    when (tab) {
        FilesTab.Tree -> {
            icon = FeatherIcons.Folder
            label = stringResource(Res.string.files_subtab_tree)
        }
        FilesTab.Diff -> {
            icon = FeatherIcons.GitCommit
            label = stringResource(Res.string.files_subtab_diff)
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .drawBehind {
                if (selected) {
                    val underlineHeight = 2.dp.toPx()
                    drawRoundRect(
                        color = colors.accentPrimary,
                        topLeft = Offset(0f, size.height - underlineHeight),
                        size = Size(size.width, underlineHeight),
                        cornerRadius = CornerRadius(underlineHeight / 2f)
                    )
                }
            }
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.accentPrimary else colors.textSecondary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.accentPrimary else colors.textSecondary
        )
        if (badgeCount != null && badgeCount > 0) {
            MederiTabBadge(count = badgeCount)
        }
    }
}