package xyz.mederi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.MederiGhostButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import mederi.app.shared.generated.resources.mcp_config_hint
import mederi.app.shared.generated.resources.mcp_save
import mederi.app.shared.generated.resources.mcp_save_failed
import mederi.app.shared.generated.resources.mcp_saving
import org.jetbrains.compose.resources.stringResource

/**
 * MCP 配置弹窗（新增 / 编辑）
 */
@Composable
internal fun McpConfigDialog(
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
                    // 取消/保存：收敛为 MederiGhostButton（TextButton 语义）+ MederiPrimaryDecisionButton（主操作）
                    MederiGhostButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismiss,
                        enabled = !isSaving,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MederiPrimaryDecisionButton(
                        text = if (isSaving) stringResource(Res.string.mcp_saving) else stringResource(Res.string.mcp_save),
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
                    )
                }
            }
        }
    }
}

internal const val DEFAULT_MCP_TEMPLATE = """{
  "mcpServers": {
    "my-service": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"]
    }
  }
}"""
