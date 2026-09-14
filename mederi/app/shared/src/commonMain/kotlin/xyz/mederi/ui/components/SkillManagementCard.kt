package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.core.ui.appstate.SkillStore
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.MederiColors
import xyz.mederi.util.pickDirectory

/**
 * 概览面板内的 Skill 管理卡片：具备展示列表、刷新、安装、设定根目录与卸载能力。
 *
 * 依赖全局唯一真理源 [SkillStore]，与未来的 Skill 市场等其他组件实时共享状态。
 */
@Composable
fun SkillManagementCard(
    skillStore: SkillStore,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    var isAddDialogOpen by remember { mutableStateOf(false) }
    var isRootDialogOpen by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<SkillItem?>(null) }

    val skills by skillStore.skills.collectAsState()
    val skillsRoot by skillStore.skillsRoot.collectAsState()
    val isRefreshing by skillStore.isRefreshing.collectAsState()
    val isOperating by skillStore.isOperating.collectAsState()
    val scope = rememberCoroutineScope()

    val onSelectDirectory: () -> Unit = {
        scope.launch {
            if (isDesktopPlatform) {
                val picked = pickDirectory()
                if (!picked.isNullOrBlank()) {
                    skillStore.setRootDirectory(picked)
                }
            } else {
                isRootDialogOpen = true
            }
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
                    imageVector = FeatherIcons.Package,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Skill 技能管理",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "${skills.size}",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设定根目录按钮（优先调用系统原生选择器）
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSelectDirectory() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Folder,
                        contentDescription = "设置 Skill 根目录",
                        tint = colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }

                // 刷新按钮
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { skillStore.refresh() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = "刷新 Skill",
                        tint = if (isRefreshing) colors.accentPrimary else colors.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }

                // 安装按钮
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isAddDialogOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = "安装 Skill",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 当前目录提示小字
        if (skillsRoot.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceWorkspace.copy(alpha = 0.5f))
                    .clickable { onSelectDirectory() }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Folder,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(10.dp)
                )
                Text(
                    text = "目录: $skillsRoot",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 列表区
        if (skills.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "暂无已安装的 Skill",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "点击右上角 + 按钮下载安装，或通过文件夹图标修改根目录",
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                skills.forEach { skill ->
                    SkillItemRow(
                        skill = skill,
                        colors = colors,
                        onUninstall = { uninstallTarget = skill }
                    )
                }
            }
        }
    }

    // 安装 Skill 弹窗
    if (isAddDialogOpen) {
        InstallSkillDialog(
            onDismiss = { isAddDialogOpen = false },
            onConfirm = { url -> skillStore.install(url) },
            colors = colors
        )
    }

    // 设置根目录弹窗
    if (isRootDialogOpen) {
        SetSkillRootDialog(
            initialPath = skillsRoot,
            onDismiss = { isRootDialogOpen = false },
            onConfirm = { path -> skillStore.setRootDirectory(path) },
            colors = colors
        )
    }

    // 卸载确认弹窗
    uninstallTarget?.let { target ->
        UninstallSkillConfirmDialog(
            skill = target,
            isDeleting = isOperating,
            onDismiss = { uninstallTarget = null },
            onConfirm = {
                val res = skillStore.uninstall(target.name)
                if (res.isSuccess) {
                    uninstallTarget = null
                }
            },
            colors = colors
        )
    }
}

@Composable
private fun SkillItemRow(
    skill: SkillItem,
    colors: MederiColors,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceWorkspace)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Skill 信息
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = skill.name,
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                skill.compatibility?.takeIf { it.isNotBlank() }?.let { compat ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accentPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = compat,
                            color = colors.accentPrimary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 卸载按钮
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onUninstall() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Trash2,
                contentDescription = "卸载 Skill",
                tint = colors.textMuted,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun InstallSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<SkillItem>,
    colors: MederiColors
) {
    var urlText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "安装 Skill",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "请输入包含 SKILL.md 的 zip 压缩包下载地址（http/https）：",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorMessage = null
                    },
                    placeholder = {
                        Text(
                            text = "https://example.com/skills/my-skill.zip",
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                    ),
                    singleLine = true
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
                    TextButton(onClick = onDismiss, enabled = !isInstalling) {
                        Text("取消", color = colors.textMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val trimmed = urlText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = "下载地址不能为空"
                                return@Button
                            }
                            scope.launch {
                                isInstalling = true
                                val res = onConfirm(trimmed)
                                isInstalling = false
                                if (res.isSuccess) {
                                    onDismiss()
                                } else {
                                    errorMessage = res.exceptionOrNull()?.message ?: "安装失败"
                                }
                            }
                        },
                        enabled = !isInstalling && urlText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("正在下载与安装...", fontSize = 11.sp)
                        } else {
                            Text("安装", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetSkillRootDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors
) {
    var pathText by remember { mutableStateOf(initialPath) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isDesktopPlatform) "设置 Skill 根目录" else "设置宿主 Skill 根目录",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isDesktopPlatform) {
                        "存放 Skill 的扫描根目录（支持 ~ 缩写），修改后将自动创建并扫描该目录："
                    } else {
                        "当前端为遥控端，请输入远程宿主机器上的 Skill 目录路径（支持 ~ 缩写，默认 ~/.mederi/skills）："
                    },
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                OutlinedTextField(
                    value = pathText,
                    onValueChange = {
                        pathText = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (isDesktopPlatform) {
                        {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val picked = pickDirectory()
                                        if (!picked.isNullOrBlank()) {
                                            pathText = picked
                                            errorMessage = null
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Folder,
                                    contentDescription = "系统原生选择目录",
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else null,
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
                    ),
                    singleLine = true
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
                        Text("取消", color = colors.textMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val trimmed = pathText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = "目录路径不能为空"
                                return@Button
                            }
                            scope.launch {
                                isSaving = true
                                val res = onConfirm(trimmed)
                                isSaving = false
                                if (res.isSuccess) {
                                    onDismiss()
                                } else {
                                    errorMessage = res.exceptionOrNull()?.message ?: "保存目录失败"
                                }
                            }
                        },
                        enabled = !isSaving && pathText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                    ) {
                        Text(if (isSaving) "保存中..." else "保存", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun UninstallSkillConfirmDialog(
    skill: SkillItem,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit,
    colors: MederiColors
) {
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(360.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "卸载技能确认",
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "确定要卸载技能「${skill.name}」吗？该技能的整个目录将被删除。",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isDeleting) {
                        Text("取消", color = colors.textMuted, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                onConfirm()
                            }
                        },
                        enabled = !isDeleting,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentDanger)
                    ) {
                        Text(if (isDeleting) "正在删除..." else "确认卸载", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
