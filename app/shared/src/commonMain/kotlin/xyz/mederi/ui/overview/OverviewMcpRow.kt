package xyz.mederi.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.mcp_add
import mederi.app.shared.generated.resources.mcp_add_dialog_title
import mederi.app.shared.generated.resources.mcp_delete_server
import mederi.app.shared.generated.resources.mcp_edit_config
import mederi.app.shared.generated.resources.mcp_edit_dialog_title
import mederi.app.shared.generated.resources.mcp_empty
import mederi.app.shared.generated.resources.mcp_empty_hint
import mederi.app.shared.generated.resources.overview_mcp_enabled_count
import mederi.app.shared.generated.resources.mcp_more
import mederi.app.shared.generated.resources.mcp_refresh
import mederi.app.shared.generated.resources.mcp_reverify
import mederi.app.shared.generated.resources.mcp_status_failed
import mederi.app.shared.generated.resources.mcp_tools_count
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.McpServerItem
import xyz.mederi.core.contract.models.McpServerStatus
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.appstate.McpStore
import xyz.mederi.ui.components.DEFAULT_MCP_TEMPLATE
import xyz.mederi.ui.components.McpConfigDialog
import xyz.mederi.ui.components.atoms.MederiMiniSwitch

/**
 * 概览浮动窗 MCP 服务行。
 *
 * 折叠态摘要：`启用数 / 总数 启用`（textSecondary 12sp）。
 *
 * 展开态：操作栏（刷新 + 新增）+ 每个服务一行
 * （MederiMiniSwitch 启停 + 名称 + 状态 chip + 脱敏 summary/失败信息 + 三点菜单）。
 * 列表为空时显示空态文案；mount 时列表为空自动触发 [McpStore.refresh]。
 *
 * 复用 [McpConfigDialog]（新增 / 编辑），不重复实现弹窗。
 */
@Composable
fun OverviewMcpRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val mcpStore = viewModel.mcpStore
    val mcpServers by mcpStore.mcpServers.collectAsState()
    val isRefreshing by mcpStore.isRefreshing.collectAsState()
    val enabledCount = mcpServers.count { it.enabled }

    var isAddDialogOpen by remember { mutableStateOf(false) }
    var editServerName by remember { mutableStateOf<String?>(null) }
    var editJson by remember { mutableStateOf<String?>(null) }
    var isLoadingJson by remember { mutableStateOf(false) }

    // 自动刷新：mount 时列表为空则触发首次加载（参照 McpManagementCard）
    LaunchedEffect(Unit) {
        if (mcpServers.isEmpty()) mcpStore.refresh()
    }

    OverviewAccordionRow(
        icon = FeatherIcons.Cpu,
        title = "MCP",
        summary = {
            Text(
                text = stringResource(Res.string.overview_mcp_enabled_count, enabledCount, mcpServers.size),
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        ) {
            // 操作栏：刷新 + 新增
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .clickable { mcpStore.refresh() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(Res.string.mcp_refresh),
                        tint = if (isRefreshing) colors.accentPrimary else colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(MederiRadius.Square))
                        .clickable { isAddDialogOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(Res.string.mcp_add),
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            if (mcpServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MederiSpacing.SM),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.mcp_empty),
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(Res.string.mcp_empty_hint),
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
                ) {
                    mcpServers.forEach { server ->
                        OverviewMcpServerRow(
                            server = server,
                            mcpStore = mcpStore,
                            colors = colors,
                            onEdit = { editServerName = server.name },
                        )
                    }
                }
            }
        }
    }

    // 新增 MCP 弹窗
    if (isAddDialogOpen) {
        McpConfigDialog(
            title = stringResource(Res.string.mcp_add_dialog_title),
            initialJson = DEFAULT_MCP_TEMPLATE,
            onDismiss = { isAddDialogOpen = false },
            onConfirm = { json -> mcpStore.install(json) },
            colors = colors,
        )
    }

    // 编辑 MCP 弹窗
    editServerName?.let { name ->
        LaunchedEffect(name) {
            isLoadingJson = true
            val res = mcpStore.getJson(name)
            editJson = res.getOrNull()
            isLoadingJson = false
        }
        if (!isLoadingJson && editJson != null) {
            McpConfigDialog(
                title = stringResource(Res.string.mcp_edit_dialog_title, name),
                initialJson = editJson!!,
                onDismiss = { editServerName = null; editJson = null },
                onConfirm = { json -> mcpStore.update(name, json) },
                colors = colors,
            )
        }
    }
}

/**
 * 单个 MCP 服务行（紧凑版，参照 McpManagementCard 的私有 McpServerRow 自绘）。
 *
 * 左侧 MederiMiniSwitch 启停；中间名称（enabled → textPrimary / disabled → textMuted）+
 * 状态 chip（FAILED 红色"失败"badge / OK 工具数 mono / UNCHECKED 无）+ 子行
 * （FAILED 显示 lastError，否则显示脱敏后的 summary）；右侧三点菜单
 * （重新检测 / 编辑配置 / 删除服务）。
 */
@Composable
private fun OverviewMcpServerRow(
    server: McpServerItem,
    mcpStore: McpStore,
    colors: MederiColors,
    onEdit: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 微型开关
        MederiMiniSwitch(
            checked = server.enabled,
            onCheckedChange = { checked -> mcpStore.toggleEnabled(server.name, checked) },
        )

        // 服务名称与状态
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = server.name,
                    color = if (server.enabled) colors.textPrimary else colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                when (server.status) {
                    McpServerStatus.FAILED -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MederiRadius.Badge))
                                .background(colors.accentDanger.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.mcp_status_failed),
                                color = colors.accentDanger,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    McpServerStatus.OK -> {
                        val count = server.toolCount
                        if (count != null && count > 0) {
                            Text(
                                text = stringResource(Res.string.mcp_tools_count, count),
                                color = colors.successText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    McpServerStatus.UNCHECKED -> {}
                }
            }

            if (server.status == McpServerStatus.FAILED && !server.lastError.isNullOrBlank()) {
                // 失败时优先展示具体原因（verify/连接的真实错误），避免只看到"失败"两个字无从排查
                Text(
                    text = server.lastError,
                    color = colors.accentDanger.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (server.summary.isNotBlank()) {
                Text(
                    text = desensitizeUrl(server.summary),
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 更多操作 (···)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(MederiRadius.Badge))
                .clickable { isMenuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.MoreVertical,
                contentDescription = stringResource(Res.string.mcp_more),
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp),
            )

            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                containerColor = colors.surfaceSidebar,
                shape = RoundedCornerShape(MederiRadius.Card),
                modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card)),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_reverify), fontSize = 11.sp, color = colors.textPrimary) },
                    leadingIcon = { Icon(FeatherIcons.CheckCircle, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        mcpStore.verify(server.name)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_edit_config), fontSize = 11.sp, color = colors.textPrimary) },
                    leadingIcon = { Icon(FeatherIcons.Edit2, null, tint = colors.textSecondary, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        onEdit()
                    },
                )
                HorizontalDivider(color = colors.divider)
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_delete_server), fontSize = 11.sp, color = colors.accentDanger) },
                    leadingIcon = { Icon(FeatherIcons.Trash2, null, tint = colors.accentDanger, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        mcpStore.delete(server.name)
                    },
                )
            }
        }
    }
}

/**
 * URL 脱敏：若文本包含 `://`（http/https/sse 等），只保留 `scheme://` 之后的 host:port 段，
 * 剥离 path 与 query params（apiKey / token 等敏感信息不会出现在 UI）。
 * 非 URL 文本原样返回。纯字符串处理，KMP 兼容，不依赖 URL 解析库。
 */
private fun desensitizeUrl(text: String): String {
    val idx = text.indexOf("://")
    if (idx < 0) return text
    val afterScheme = text.substring(idx + 3)
    val endIdx = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
    return if (endIdx >= 0) afterScheme.substring(0, endIdx) else afterScheme
}