package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronsDown
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Search
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.tree_collapse_all
import mederi.app.shared.generated.resources.tree_empty_dir
import mederi.app.shared.generated.resources.tree_read_error
import mederi.app.shared.generated.resources.tree_refresh
import mederi.app.shared.generated.resources.tree_search_placeholder
import mederi.app.shared.generated.resources.tree_unsupported
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.Project
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.FileType
import xyz.mederi.ui.components.atoms.MederiFileTypeIconSquare
import xyz.mederi.ui.components.atoms.MederiPanelHeaderIconButton
import xyz.mederi.ui.components.atoms.PanelEmptyState

/**
 * 项目文件树面板（Files 面板 Tree 子 Tab，S3）：toolbar（根目录名 + 全部折叠 + 刷新）、
 * 过滤搜索栏、递归目录树（缩进导轨 + chevron + 文件类型图标 + 名称 + 选中 2dp accent 左条）。
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

    var expandedPaths by remember { mutableStateOf(setOf<String>()) }
    var filterQuery by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    var activePath by remember { mutableStateOf<String?>(null) }

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

    Column(modifier = modifier.fillMaxSize().background(colors.surfaceWorkspace)) {
        // ---- toolbar：根目录名 + 全部折叠 + 刷新 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.Folder,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = root.name,
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MederiPanelHeaderIconButton(
                icon = FeatherIcons.ChevronsDown,
                onClick = { expandedPaths = emptySet() },
                contentDescription = stringResource(Res.string.tree_collapse_all),
            )
            MederiPanelHeaderIconButton(
                icon = FeatherIcons.RotateCw,
                onClick = { refreshTick++ },
                contentDescription = stringResource(Res.string.tree_refresh),
            )
        }

        // ---- 搜索过滤栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceCard)
                .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = FeatherIcons.Search,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (filterQuery.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.tree_search_placeholder),
                        fontSize = 11.sp,
                        color = colors.textMuted,
                    )
                }
                BasicTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 树区（递归，Column 而非 Lazy——工程目录规模可接受） ----
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp),
        ) {
            val readErrorText = stringResource(Res.string.tree_read_error)
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
                        fontSize = 11.sp,
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
                            viewModel.openFileViewer(
                                title = n.name,
                                content = provider.readText(n.path) ?: readErrorText,
                            )
                        },
                    )
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(25.dp)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interactionSource)
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .drawBehind {
                // 选中行：2dp accent 左条
                if (active) {
                    val rail = 2.dp.toPx()
                    drawRect(
                        color = colors.accentPrimary,
                        topLeft = Offset(0f, 0f),
                        size = Size(rail, size.height)
                    )
                }
                // 缩进导轨：每个祖先层级一条 1dp 竖线（divider 发丝级）
                if (depth > 0) {
                    val guideX = 8.dp.toPx()
                    val step = 14.dp.toPx()
                    val guideWidth = 1.dp.toPx()
                    for (k in 1..depth) {
                        val x = guideX + k * step - step / 2f - guideWidth / 2f
                        drawRect(
                            color = colors.divider,
                            topLeft = Offset(x, 0f),
                            size = Size(guideWidth, size.height)
                        )
                    }
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (isDir) onToggle(node.path) else onOpen(node)
            }
            .padding(start = 8.dp + (depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // chevron / 叶子占位
        if (isDir) {
            ExpandChevron(expanded = expanded, tint = colors.textMuted, size = 11.dp)
        } else {
            Spacer(modifier = Modifier.size(11.dp))
        }
        // 类型图标
        if (isDir) {
            Icon(
                imageVector = FeatherIcons.Folder,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(13.dp)
            )
        } else {
            val fileType = fileTypeOf(node.name)
            if (fileType != null) {
                MederiFileTypeIconSquare(type = fileType)
            } else {
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        // 名称（过滤命中 / 选中 → textPrimary）
        Text(
            text = node.name,
            fontSize = 11.5.sp,
            color = if (active || queryHit) colors.textPrimary else colors.textSecondary,
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

/** 目录折叠 chevron：展开 ↓ / 折叠 →。 */
@Composable
private fun ExpandChevron(expanded: Boolean, tint: Color, size: Dp) {
    Icon(
        imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
    )
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