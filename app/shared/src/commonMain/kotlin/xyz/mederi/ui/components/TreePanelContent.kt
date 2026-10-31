package xyz.mederi.ui.components

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronsDown
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Search
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.tree_collapse_all
import mederi.app.shared.generated.resources.tree_empty_dir
import mederi.app.shared.generated.resources.tree_refresh
import mederi.app.shared.generated.resources.tree_search_placeholder
import mederi.app.shared.generated.resources.tree_unsupported
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.DocumentTarget
import xyz.mederi.ui.components.atoms.FileType
import xyz.mederi.ui.host.FileNode
import xyz.mederi.ui.host.LocalProjectFileTreeProvider
import xyz.mederi.ui.host.ProjectFileTreeProvider
import xyz.mederi.ui.components.atoms.MederiFileTypeIconSquare
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.expansionSemantics
import xyz.mederi.ui.components.atoms.PanelEmptyState

/**
 * 项目文件树面板（Files 面板 Tree 子 Tab，S3）：toolbar（根目录名 + 全部折叠 + 刷新）、
 * 过滤搜索栏、递归目录树（轻量缩进导轨 + chevron + 文件类型图标 + 名称 + 选中淡底）。
 * 数据源 = [LocalProjectFileTreeProvider]：桌面 java.io 实现，其他平台 null → 显示不支持提示。
 */
@Composable
internal fun TreePanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier,
) {
    val provider = LocalProjectFileTreeProvider.current
    if (provider == null) {
        Box(modifier = modifier.fillMaxSize()) {
            PanelEmptyState(
                icon = FeatherIcons.Folder,
                title = stringResource(Res.string.tree_unsupported),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    // 树根 = 当前选中项目目录（用户期望的项目根）；未选中/空目录时回退 provider 根（user.dir）。
    val projects by viewModel.projects.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val projectDir = projects.find { it.id == selectedProjectId }?.directory?.takeIf { it.isNotBlank() }
    val fallbackRoot = remember(provider) { provider.rootNode() }
    val root = remember(projectDir, fallbackRoot) {
        if (projectDir != null) {
            val projectName = projects.find { it.id == selectedProjectId }?.name
            FileNode(name = projectName?.takeIf { it.isNotBlank() } ?: projectDir.substringAfterLast('/').ifBlank { projectDir }, path = projectDir, isDirectory = true)
        } else {
            fallbackRoot
        }
    }

    ProjectTreeContent(
        root = root,
        provider = provider,
        colors = colors,
        onOpen = { viewModel.openDocument(DocumentTarget.Path(it.path)) },
        modifier = modifier,
    )
}

/**
 * 实际目录树视图；本地状态仅是展开/筛选/选中/刷新，不复制文件内容或业务事实。
 * Provider 与打开回调可注入，平台与路由仍由 TreePanelContent 负责。
 */
@Composable
internal fun ProjectTreeContent(
    root: FileNode,
    provider: ProjectFileTreeProvider,
    colors: MederiColors,
    onOpen: (FileNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedPaths by remember { mutableStateOf(setOf<String>()) }
    var filterQuery by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    var activePath by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
        TreeToolbar(
            rootName = root.name,
            query = filterQuery,
            onQueryChange = { filterQuery = it },
            onCollapseAll = { expandedPaths = emptySet() },
            onRefresh = { refreshTick++ },
            colors = colors,
        )

        // ---- 树区（递归，Column 而非 Lazy——工程目录规模可接受） ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp),
        ) {
            // 刷新只让原有 provider 查询失效；筛选、展开、选中均保留。
            key(refreshTick) {
                val query = filterQuery.trim()
                val rootChildren = provider.listChildren(root.path)
                val rootNodes =
                    if (query.isEmpty()) rootChildren
                    else rootChildren.filter { matchesFilter(it, query, provider) }
                if (rootNodes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.tree_empty_dir),
                            style = WorkspaceStyle.Caption,
                            color = colors.textMuted,
                        )
                    }
                } else {
                    rootNodes.forEach { node ->
                        TreeNodeRow(
                            node = node,
                            depth = 0,
                            provider = provider,
                            query = query,
                            allShown = false,
                            expandedPaths = expandedPaths,
                            activePath = activePath,
                            colors = colors,
                            onToggle = { path ->
                                expandedPaths =
                                    if (path in expandedPaths) expandedPaths - path else expandedPaths + path
                            },
                            onOpen = { n ->
                                activePath = n.path
                                onOpen(n)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个树行 + 递归展开子项。过滤语义：目录名命中查询 → 其下子节点全部展示（allShown 传播）；
 * 否则逐级过滤（目录含命中后代也保留）。
 */
@Composable
private fun TreeNodeRow(
    node: FileNode,
    depth: Int,
    provider: ProjectFileTreeProvider,
    query: String,
    allShown: Boolean,
    expandedPaths: Set<String>,
    activePath: String?,
    colors: MederiColors,
    onToggle: (String) -> Unit,
    onOpen: (FileNode) -> Unit,
) {
    val isDir = node.isDirectory
    val expanded = node.path in expandedPaths
    val active = node.path == activePath
    val queryHit = query.isNotEmpty() && node.name.contains(query, ignoreCase = true)

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rowBackground by animateColorAsState(
        targetValue = when {
            active -> colors.accentBg
            hovered || focused -> colors.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "fileTreeRowFeedback",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(rowBackground)
            .hoverable(interactionSource)
            .then(if (isDir) {
                Modifier.expansionSemantics(expanded) { onToggle(node.path) }
            } else Modifier.semantics { selected = active })
            .drawBehind {
                // 缩进导轨：每个祖先层级一条 1dp 竖线（divider 发丝级）
                if (depth > 0) {
                    val guideX = 8.dp.toPx()
                    val step = 14.dp.toPx()
                    val guideWidth = 1.dp.toPx()
                    for (k in 1..depth) {
                        val x = guideX + k * step - step / 2f - guideWidth / 2f
                        drawRect(
                            color = colors.divider.copy(alpha = 0.5f),
                            topLeft = Offset(x, 0f),
                            size = Size(guideWidth, size.height)
                        )
                    }
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                if (isDir) onToggle(node.path) else onOpen(node)
            }
            .padding(start = 8.dp + (depth * 14).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // chevron / 叶子占位
        if (isDir) {
            ExpandChevron(expanded = expanded, tint = colors.textMuted, size = WorkspaceStyle.IconSize)
        } else {
            Spacer(modifier = Modifier.size(WorkspaceStyle.IconSize))
        }
        // 类型图标
        if (isDir) {
            Icon(
                imageVector = FeatherIcons.Folder,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(WorkspaceStyle.IconSize)
            )
        } else {
            val fileType = fileTypeOf(node.name)
            if (fileType != null) {
                Box(Modifier.size(WorkspaceStyle.IconSize), contentAlignment = Alignment.Center) {
                    MederiFileTypeIconSquare(type = fileType)
                }
            } else {
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(WorkspaceStyle.IconSize)
                )
            }
        }
        // 名称：选中使用单一强调色，过滤命中保持可读主文字色。
        Text(
            text = node.name,
            style = WorkspaceStyle.Body,
            color = if (active) colors.accentText else if (queryHit) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    // 递归展开子项
    if (isDir && expanded) {
        val children = provider.listChildren(node.path)
        val childAllShown = allShown || node.name.contains(query, ignoreCase = true)
        val childNodes =
            if (query.isEmpty() || childAllShown) children
            else children.filter { matchesFilter(it, query, provider) }
        childNodes.forEach { child ->
            TreeNodeRow(
                node = child,
                depth = depth + 1,
                provider = provider,
                query = query,
                allShown = childAllShown,
                expandedPaths = expandedPaths,
                activePath = activePath,
                colors = colors,
                onToggle = onToggle,
                onOpen = onOpen,
            )
        }
    }
}

/** 文件类型推断：kotlin/kt → Kt、md → Md、gradle/kts → Gradle，其余 null（用通用 File 图标）。 */
private fun fileTypeOf(name: String): FileType? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kotlin", "kt" -> FileType.Kt
        "md" -> FileType.Md
        "gradle", "kts" -> FileType.Gradle
        else -> null
    }
}

/** 过滤判定：名称命中查询，或目录下（递归）存在命中节点。 */
private fun matchesFilter(node: FileNode, query: String, provider: ProjectFileTreeProvider): Boolean {
    if (node.name.contains(query, ignoreCase = true)) return true
    if (!node.isDirectory) return false
    return provider.listChildren(node.path).any { matchesFilter(it, query, provider) }
}

/** 树工具栏只收展示值与动作；搜索和刷新使用相同尺寸与反馈。 */
@Composable
private fun TreeToolbar(
    rootName: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapseAll: () -> Unit,
    onRefresh: () -> Unit,
    colors: MederiColors,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.Folder,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
            Text(
                text = rootName,
                style = WorkspaceStyle.Title,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TreeToolbarButton(
                icon = FeatherIcons.ChevronsDown,
                description = stringResource(Res.string.tree_collapse_all),
                onClick = onCollapseAll,
                colors = colors,
            )
            TreeToolbarButton(
                icon = FeatherIcons.RotateCw,
                description = stringResource(Res.string.tree_refresh),
                onClick = onRefresh,
                colors = colors,
            )
        }
        var searchFocused by remember { mutableStateOf(false) }
        val searchBorder by animateColorAsState(
            targetValue = if (searchFocused) colors.accentBorder else Color.Transparent,
            animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
            label = "fileSearchFocus",
        )
        val searchLabel = stringResource(Res.string.tree_search_placeholder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = WorkspaceStyle.ControlHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceHover)
                .border(1.dp, searchBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.Search,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(text = searchLabel, style = WorkspaceStyle.Body, color = colors.textMuted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = WorkspaceStyle.Body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { searchFocused = it.isFocused }
                        .semantics { contentDescription = searchLabel },
                )
            }
        }
    }
}

@Composable
private fun TreeToolbarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    colors: MederiColors,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = if (hovered || focused) colors.surfaceHover else Color.Transparent,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "treeToolbarFeedback",
    )
    Box(
        modifier = Modifier.size(WorkspaceStyle.IconTarget)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize),
        )
    }
}
