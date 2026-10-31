package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ErrorBoard

/** 单行/多行表单字段共用规格，领域标签与校验由调用方提供。 */
@Composable
internal fun WorkspaceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    singleLine: Boolean = true,
    monospace: Boolean = false,
    label: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    OutlinedTextField(value, onValueChange, modifier = modifier, enabled = enabled,
        placeholder = { Text(placeholder, style = WorkspaceStyle.Body, color = colors.textMuted) },
        label = label?.let { { Text(it, style = WorkspaceStyle.Caption) } },
        singleLine = singleLine, minLines = if (singleLine) 1 else 5, maxLines = if (singleLine) 1 else 12,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        textStyle = WorkspaceStyle.Body.copy(color = colors.textPrimary,
            fontFamily = if (monospace) FontFamily.Monospace else null),
        shape = RoundedCornerShape(MederiRadius.Control),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceInput, unfocusedContainerColor = colors.surfaceInput,
            disabledContainerColor = colors.surfaceInput, focusedBorderColor = colors.accentFocus,
            unfocusedBorderColor = colors.divider, disabledBorderColor = colors.divider,
            focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textSecondary, cursorColor = colors.accentPrimary))
}

/** 确认、输入与异步表单共用同一个动作行；文字增长时自然回流。 */
@Composable
internal fun WorkspaceDialogActions(
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    danger: Boolean = false,
    confirmIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WorkspaceActionButton(cancelLabel, onDismiss, enabled = cancelEnabled)
        WorkspaceActionButton(confirmLabel, onConfirm, enabled = confirmEnabled, primary = true,
            tone = if (danger) WorkspaceActionTone.Danger else WorkspaceActionTone.Accent, icon = confirmIcon)
    }
}

/** 仅协调一次表单提交，不持有领域数据；业务仍由传入的 Store/VM 操作负责。 */
@Stable
internal class DialogSubmissionState(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clearError() { error = null }

    fun submit(fallbackError: String, operation: suspend () -> Result<*>, onSuccess: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = operation()
                if (result.isSuccess) onSuccess() else error = result.exceptionOrNull()?.message ?: fallbackError
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: fallbackError
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberDialogSubmission(): DialogSubmissionState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DialogSubmissionState(scope) }
}

/** Skill URL/目录与 MCP JSON 使用同一提交表单，草稿只属于当前弹窗。 */
@Composable
internal fun TextSubmissionDialog(
    title: String,
    description: String,
    initialValue: String,
    fieldLabel: String,
    confirmLabel: String,
    busyLabel: String,
    fallbackError: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<*>,
    placeholder: String = "",
    singleLine: Boolean = true,
    onPick: (suspend () -> String?)? = null,
    pickLabel: String? = null,
) {
    var value by remember { mutableStateOf(initialValue) }
    val submission = rememberDialogSubmission()
    val picker = rememberDialogSubmission()
    val busy = submission.busy || picker.busy
    MederiDialog(onDismiss = { if (!busy) onDismiss() }, width = 480.dp) {
        TextSubmissionContent(title, description, value, { value = it; submission.clearError(); picker.clearError() },
            fieldLabel, confirmLabel, if (submission.busy) busyLabel else confirmLabel, busy, submission.error ?: picker.error,
            onConfirm = { if (!busy && value.isNotBlank()) submission.submit(fallbackError, { onConfirm(value.trim()) }, onDismiss) },
            onDismiss = onDismiss, placeholder = placeholder, singleLine = singleLine,
            onPick = onPick?.let { pick -> {
                if (!busy) picker.submit(fallbackError, {
                    val selected = pick()
                    if (!selected.isNullOrBlank()) { value = selected; submission.clearError() }
                    Result.success(Unit)
                }, {})
            } }, pickLabel = pickLabel)
    }
}

/** 无 I/O 的表单 Content，实际窗口和组件预览均使用它。 */
@Composable
internal fun TextSubmissionContent(
    title: String, description: String, value: String, onValueChange: (String) -> Unit,
    fieldLabel: String, confirmLabel: String, busyLabel: String, busy: Boolean, error: String?,
    onConfirm: () -> Unit, onDismiss: () -> Unit, placeholder: String = "", singleLine: Boolean = true,
    onPick: (() -> Unit)? = null, pickLabel: String? = null,
) {
    val colors = LocalMederiColors.current
    Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary)
    Text(description, style = WorkspaceStyle.Body, color = colors.textSecondary)
    WorkspaceTextField(value, onValueChange, Modifier.fillMaxWidth(), enabled = !busy, placeholder = placeholder,
        singleLine = singleLine, monospace = true, label = fieldLabel)
    if (onPick != null && pickLabel != null) WorkspaceActionButton(pickLabel, onPick, enabled = !busy)
    error?.let { ErrorBoard(it) }
    WorkspaceDialogActions(if (busy) busyLabel else confirmLabel, stringResource(Res.string.cancel), onConfirm, onDismiss,
        confirmEnabled = !busy && value.isNotBlank(), cancelEnabled = !busy)
}
