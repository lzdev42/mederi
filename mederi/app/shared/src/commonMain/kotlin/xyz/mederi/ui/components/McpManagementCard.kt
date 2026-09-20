package xyz.mederi.ui.components

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.McpServerItem
import xyz.mederi.core.contract.models.McpServerStatus
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.McpStore
import xyz.mederi.theme.MederiColors
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.mcp_add
import mederi.app.shared.generated.resources.mcp_add_dialog_title
import mederi.app.shared.generated.resources.mcp_config_hint
import mederi.app.shared.generated.resources.mcp_delete_server
import mederi.app.shared.generated.resources.mcp_edit_config
import mederi.app.shared.generated.resources.mcp_edit_dialog_title
import mederi.app.shared.generated.resources.mcp_empty
import mederi.app.shared.generated.resources.mcp_empty_hint
import mederi.app.shared.generated.resources.mcp_more
import mederi.app.shared.generated.resources.mcp_refresh
import mederi.app.shared.generated.resources.mcp_reverify
import mederi.app.shared.generated.resources.mcp_save
import mederi.app.shared.generated.resources.mcp_save_failed
import mederi.app.shared.generated.resources.mcp_saving
import mederi.app.shared.generated.resources.mcp_status_failed
import mederi.app.shared.generated.resources.mcp_title
import org.jetbrains.compose.resources.stringResource

/**
 * 概览面板内的 MCP 管理卡片：具备展示列表、启停开关、刷新、新增、修改与删除能力。
 *
 * 依赖全局唯一真理源 [McpStore]，与后续 MCP 市场等组件实时共享状态。
 */
@Composable
fun McpManagementCard(
    mcpStore: McpStore,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    var isAddDialogOpen by remember { mutableStateOf(false) }
    var editServerName by remember { mutableStateOf<String?>(null) }

    val mcpServers by mcpStore.mcpServers.collectAsState()
    val isRefreshing by mcpStore.isRefreshing.collectAsState()
    val enabledCount = mcpServers.count { it.enabled }

    LaunchedEffect(Unit) {
        xyz.mederi.core.ui.DebugLog.info("MCP", "McpManagementCard mounted, current servers count=${mcpServers.size}")
        if (mcpServers.isEmpty()) {
            mcpStore.refresh()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 卡片 Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(Res.string.mcp_title),
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "$enabledCount/${mcpServers.size}",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 刷新按钮
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { mcpStore.refresh() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(Res.string.mcp_refresh),
                        tint = if (isRefreshing) colors.accentPrimary else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }

                // 添加按钮
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isAddDialogOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(Res.string.mcp_add),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 列表区
        if (mcpServers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(Res.string.mcp_empty),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(Res.string.mcp_empty_hint),
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                mcpServers.forEach { server ->
                    McpServerRow(
                        server = server,
                        mcpStore = mcpStore,
                        colors = colors,
                        onEdit = { editServerName = server.name }
                    )
                }
            }
        }
    }

    // 添加 MCP 弹窗
    if (isAddDialogOpen) {
        McpConfigDialog(
            title = stringResource(Res.string.mcp_add_dialog_title),
            initialJson = DEFAULT_MCP_TEMPLATE,
            onDismiss = { isAddDialogOpen = false },
            onConfirm = { json -> mcpStore.install(json) },
            colors = colors
        )
    }

    // 编辑 MCP 弹窗
    editServerName?.let { serverName ->
        var currentJson by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(serverName) {
            val res = mcpStore.getJson(serverName)
            currentJson = res.getOrDefault("{}")
            isLoading = false
        }

        if (!isLoading && currentJson != null) {
            McpConfigDialog(
                title = stringResource(Res.string.mcp_edit_dialog_title, serverName),
                initialJson = currentJson ?: "{}",
                onDismiss = { editServerName = null },
                onConfirm = { json -> mcpStore.update(serverName, json) },
                colors = colors
            )
        }
    }
}

/** 兼容 WorkspaceViewModel 调用的重载 */
@Composable
fun McpManagementCard(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    McpManagementCard(
        mcpStore = viewModel.mcpStore,
        colors = colors,
        modifier = modifier
    )
}

@Composable
private fun McpServerRow(
    server: McpServerItem,
    mcpStore: McpStore,
    colors: MederiColors,
    onEdit: () -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceWorkspace)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 微型开关
        Switch(
            checked = server.enabled,
            onCheckedChange = { checked -> mcpStore.toggleEnabled(server.name, checked) },
            modifier = Modifier
                .scale(0.65f)
                .size(width = 30.dp, height = 20.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accentPrimary,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.buttonSecondary
            )
        )

        // 服务名称与状态
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = server.name,
                    color = if (server.enabled) colors.textPrimary else colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                when (server.status) {
                    McpServerStatus.FAILED -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(colors.accentDanger.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.mcp_status_failed),
                                color = colors.accentDanger,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    McpServerStatus.OK -> {
                        val count = server.toolCount
                        if (count != null && count > 0) {
                            Text(
                                text = "${count} tools",
                                color = colors.accentSuccess,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Normal
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
                    overflow = TextOverflow.Ellipsis
                )
            } else if (server.summary.isNotBlank()) {
                Text(
                    text = server.summary,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 更多操作 (···)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable { isMenuOpen = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.MoreVertical,
                contentDescription = stringResource(Res.string.mcp_more),
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp)
            )

            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                containerColor = colors.surfaceSidebar,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_reverify), fontSize = 11.sp, color = colors.textPrimary) },
                    leadingIcon = { Icon(FeatherIcons.CheckCircle, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        mcpStore.verify(server.name)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_edit_config), fontSize = 11.sp, color = colors.textPrimary) },
                    leadingIcon = { Icon(FeatherIcons.Edit2, null, tint = colors.textSecondary, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        onEdit()
                    }
                )
                HorizontalDivider(color = colors.divider)
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.mcp_delete_server), fontSize = 11.sp, color = colors.accentDanger) },
                    leadingIcon = { Icon(FeatherIcons.Trash2, null, tint = colors.accentDanger, modifier = Modifier.size(13.dp)) },
                    onClick = {
                        isMenuOpen = false
                        mcpStore.delete(server.name)
                    }
                )
            }
        }
    }
}

/**
 * MCP 配置弹窗（新增 / 编辑）
 */
@Composable
private fun McpConfigDialog(
    title: String,
    initialJson: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors
) {
    var jsonText by remember { mutableStateOf(initialJson) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // 错误文案在组合上下文取值（scope.launch 不是 @Composable）
    val saveFailedMsg = stringResource(Res.string.mcp_save_failed)
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Res.string.mcp_config_hint),
                    color = colors.textMuted,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.divider,
                        focusedContainerColor = colors.surfaceWorkspace,
                        unfocusedContainerColor = colors.surfaceWorkspace
                    )
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = colors.accentDanger,
                        fontSize = 10.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text(stringResource(Res.string.cancel), color = colors.textMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val res = onConfirm(jsonText.trim())
                                isSaving = false
                                if (res.isSuccess) {
                                    onDismiss()
                                } else {
                                    errorMessage = res.exceptionOrNull()?.message ?: saveFailedMsg
                                }
                            }
                        },
                        enabled = !isSaving && jsonText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        Text(if (isSaving) stringResource(Res.string.mcp_saving) else stringResource(Res.string.mcp_save), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private const val DEFAULT_MCP_TEMPLATE = """{
  "mcpServers": {
    "my-service": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"]
    }
  }
}"""
