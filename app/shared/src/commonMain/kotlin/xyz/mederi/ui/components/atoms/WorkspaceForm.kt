package xyz.mederi.ui.components.atoms

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ErrorBoard

/** 单行/多行表单字段共用规格（30dp 紧凑单行控件框 + 主题语义描边）；领域标签与校验由调用方提供。 */
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(MederiRadius.Control)
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.divider.copy(alpha = 0.5f)
            focused -> colors.accentFocus
            hovered -> colors.borderStrong
            else -> colors.divider
        },
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "workspaceFieldBorder",
    )
    val containerColor = if (enabled) colors.surfaceInput else colors.surfaceHover.copy(alpha = 0.45f)
    val textStyle = WorkspaceStyle.Body.copy(
        color = if (enabled) colors.textPrimary else colors.textMuted,
        fontFamily = if (monospace) FontFamily.Monospace else null,
    )

    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.hoverable(source, enabled = enabled),
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 5,
            maxLines = if (singleLine) 1 else 12,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accentPrimary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = source,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .then(if (singleLine) Modifier.heightIn(min = WorkspaceStyle.ControlHeight) else Modifier)
                        .clip(shape)
                        .background(containerColor)
                        .border(1.dp, borderColor, shape)
                        .padding(
                            start = if (leadingIcon != null) MederiSpacing.SM else MederiSpacing.Compact,
                            end = if (trailingIcon != null && singleLine) MederiSpacing.XS else MederiSpacing.Compact,
                        ),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
                ) {
                    if (leadingIcon != null) {
                        Box(
                            modifier = if (singleLine) Modifier else Modifier.padding(top = MederiSpacing.SM),
                            contentAlignment = Alignment.Center,
                        ) {
                            leadingIcon()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = if (singleLine) 5.dp else MederiSpacing.SM),
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = WorkspaceStyle.Body.copy(
                                    fontFamily = if (monospace) FontFamily.Monospace else null,
                                ),
                                color = if (enabled) colors.textMuted else colors.textMuted.copy(alpha = 0.6f),
                                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingIcon != null) {
                        Box(
                            modifier = if (singleLine) Modifier else Modifier.padding(top = MederiSpacing.XS),
                            contentAlignment = Alignment.Center,
                        ) {
                            trailingIcon()
                        }
                    }
                }
            },
        )
    }

    if (label != null) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            Text(label, style = WorkspaceStyle.Caption, color = colors.textSecondary)
            field(Modifier.fillMaxWidth())
        }
    } else {
        field(modifier)
    }
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
