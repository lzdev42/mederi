package xyz.mederi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.util.pickDirectory
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.pick_directory_title
import mederi.app.shared.generated.resources.skill_deleting
import mederi.app.shared.generated.resources.skill_install
import mederi.app.shared.generated.resources.skill_install_action
import mederi.app.shared.generated.resources.skill_install_failed
import mederi.app.shared.generated.resources.skill_install_prompt
import mederi.app.shared.generated.resources.skill_installing
import mederi.app.shared.generated.resources.skill_path_required
import mederi.app.shared.generated.resources.skill_pick_dir_native
import mederi.app.shared.generated.resources.skill_root_prompt
import mederi.app.shared.generated.resources.skill_root_prompt_remote
import mederi.app.shared.generated.resources.skill_save
import mederi.app.shared.generated.resources.skill_save_dir_failed
import mederi.app.shared.generated.resources.skill_saving
import mederi.app.shared.generated.resources.skill_set_root_title
import mederi.app.shared.generated.resources.skill_set_root_title_remote
import mederi.app.shared.generated.resources.skill_uninstall_confirm
import mederi.app.shared.generated.resources.skill_uninstall_confirm_action
import mederi.app.shared.generated.resources.skill_uninstall_title
import mederi.app.shared.generated.resources.skill_url_required
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InstallSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<SkillItem>,
    colors: MederiColors
) {
    var urlText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 错误文案在组合上下文取值（Button onClick / scope.launch 不是 @Composable）
    val urlRequiredMsg = stringResource(Res.string.skill_url_required)
    val installFailedMsg = stringResource(Res.string.skill_install_failed)

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
                    text = stringResource(Res.string.skill_install),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Res.string.skill_install_prompt),
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
                    // 取消/安装：收敛为 MederiGhostButton + MederiPrimaryDecisionButton（安装中 spinner 收敛为文案切换）
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isInstalling,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = if (isInstalling) stringResource(Res.string.skill_installing) else stringResource(Res.string.skill_install_action),
                        onClick = {
                            val trimmed = urlText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = urlRequiredMsg
                            } else {
                                scope.launch {
                                    isInstalling = true
                                    val res = onConfirm(trimmed)
                                    isInstalling = false
                                    if (res.isSuccess) {
                                        onDismiss()
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: installFailedMsg
                                    }
                                }
                            }
                        },
                        enabled = !isInstalling && urlText.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SetSkillRootDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors
) {
    var pathText by remember { mutableStateOf(initialPath) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 错误文案在组合上下文取值（Button onClick / scope.launch 不是 @Composable）
    val pathRequiredMsg = stringResource(Res.string.skill_path_required)
    val saveDirFailedMsg = stringResource(Res.string.skill_save_dir_failed)
    val pickDirectoryTitle = stringResource(Res.string.pick_directory_title)

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
                    text = if (isDesktopPlatform) stringResource(Res.string.skill_set_root_title)
                           else stringResource(Res.string.skill_set_root_title_remote),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (isDesktopPlatform) stringResource(Res.string.skill_root_prompt)
                           else stringResource(Res.string.skill_root_prompt_remote),
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
                                        val picked = pickDirectory(pickDirectoryTitle)
                                        if (!picked.isNullOrBlank()) {
                                            pathText = picked
                                            errorMessage = null
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Folder,
                                    contentDescription = stringResource(Res.string.skill_pick_dir_native),
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
                    // 取消/保存：收敛为 MederiGhostButton + MederiPrimaryDecisionButton
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isSaving,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = if (isSaving) stringResource(Res.string.skill_saving) else stringResource(Res.string.skill_save),
                        onClick = {
                            val trimmed = pathText.trim()
                            if (trimmed.isBlank()) {
                                errorMessage = pathRequiredMsg
                            } else {
                                scope.launch {
                                    isSaving = true
                                    val res = onConfirm(trimmed)
                                    isSaving = false
                                    if (res.isSuccess) {
                                        onDismiss()
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: saveDirFailedMsg
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && pathText.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun UninstallSkillConfirmDialog(
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
                    text = stringResource(Res.string.skill_uninstall_title),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(Res.string.skill_uninstall_confirm, skill.name),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isDeleting,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // 危险确认按钮自绘保留：atom 无 danger 变体（accentDanger 容器 + onAccentPrimary 文案），待配色迁移时补 danger 变体
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentDanger)
                            .clickable(enabled = !isDeleting) {
                                scope.launch {
                                    onConfirm()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isDeleting) stringResource(Res.string.skill_deleting) else stringResource(Res.string.skill_uninstall_confirm_action),
                            color = colors.onAccentPrimary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
