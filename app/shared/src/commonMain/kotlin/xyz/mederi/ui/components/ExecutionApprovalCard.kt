package xyz.mederi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ExecutionApprovalRequest
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface
import xyz.mederi.ui.components.atoms.WorkspaceActionTone

/** 只展示 core 提出的这一次操作，不把批准转成长期白名单或全局权限。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExecutionApprovalCard(
    request: ExecutionApprovalRequest,
    submitting: Boolean,
    error: String?,
    onResolve: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    WorkspaceActionSurface(
        tone = WorkspaceActionTone.Accent,
        modifier = modifier.widthIn(max = ChatLayout.actionCardMaxWidth),
    ) {
        Text(stringResource(Res.string.execution_approval_title),
            style = WorkspaceStyle.Title, color = colors.textPrimary)
        Text(stringResource(when (request.access.lowercase()) {
            "network" -> Res.string.execution_approval_network
            "unsandboxed", "off" -> Res.string.execution_approval_unsandboxed
            else -> Res.string.execution_approval_outside
        }), style = WorkspaceStyle.Body, color = colors.textSecondary)
        Text(request.toolName, style = WorkspaceStyle.Caption, color = colors.textMuted)
        request.reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = WorkspaceStyle.Body, color = colors.textSecondary)
        }
        request.command?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer {
                Text(it, Modifier.fillMaxWidth(), color = colors.textPrimary,
                    style = WorkspaceStyle.Body.copy(fontFamily = FontFamily.Monospace))
            }
        }
        request.paths.forEach { path ->
            SelectionContainer {
                Text(path, Modifier.fillMaxWidth(), color = colors.textPrimary,
                    style = WorkspaceStyle.Body.copy(fontFamily = FontFamily.Monospace))
            }
        }
        request.cwd?.takeIf { it.isNotBlank() }?.let {
            SelectionContainer {
                Text(stringResource(Res.string.execution_approval_cwd, it),
                    color = colors.textSecondary, style = WorkspaceStyle.Caption.copy(fontFamily = FontFamily.Monospace))
            }
        }
        error?.let {
            Text(it, color = colors.accentDanger, style = WorkspaceStyle.Body)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            WorkspaceActionButton(stringResource(Res.string.execution_approval_allow_once),
                onClick = { onResolve(true) }, enabled = !submitting, primary = true)
            WorkspaceActionButton(stringResource(Res.string.execution_approval_deny),
                onClick = { onResolve(false) }, enabled = !submitting)
        }
    }
}
