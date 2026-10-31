package xyz.mederi.ui.components.atoms

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import xyz.mederi.theme.WorkspaceStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing

/**
 * 统一对话框基底（12dp圆角、中性底色、20dp内部留白、有界滚动）。
 * 对齐 Sidebar/SkillManagementCard 等既有对话框的视觉结构，消灭 10+ 处复制粘贴骨架。
 * 壳用等价 Box 修饰链替代 M3 Surface（MederiDialog 自身即对话框壳原子，无需再套 MederiCard）。
 */
@Composable
fun MederiDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    title: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MederiDialogContent(modifier = modifier, width = width, title = title, content = content)
    }
}

/** 独立于平台窗口的共同壳；先限制高度再滚动，长表单与大字体保持可达。 */
@Composable
internal fun MederiDialogContent(
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    title: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    Column(
        modifier = modifier.padding(MederiSpacing.LG).widthIn(max = width).fillMaxWidth()
            .heightIn(max = 560.dp)
            .clip(RoundedCornerShape(MederiRadius.Dialog))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Dialog))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD),
    ) {
        title?.invoke()
        content()
    }
}

/**
 * 确认对话框：标题 + 正文 + (取消 | 确认) 尾部按钮。
 * [danger] = true 时确认按钮用 accentDanger（危险文字色）；[confirmIcon] 可选前置图标。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    confirmIcon: ImageVector? = null,
    width: Dp = 320.dp,
    titleColor: Color = LocalMederiColors.current.textPrimary,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    MederiDialog(
        onDismiss = { if (enabled) onDismiss() },
        width = width,
        title = { Text(title, style = WorkspaceStyle.Title, color = titleColor) },
    ) {
        Text(message, style = WorkspaceStyle.Body, color = colors.textSecondary)
        supportingContent?.invoke()
        WorkspaceDialogActions(confirmLabel, cancelLabel, onConfirm, onDismiss,
            confirmEnabled = enabled, cancelEnabled = enabled, danger = danger, confirmIcon = confirmIcon)
    }
}

/**
 * 输入对话框：标题 + 单行输入框 + (取消 | 确认) 尾部按钮。
 * 输入框内部状态由本组件持有；确认按钮在输入空白时禁用。
 */
@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    cancelLabel: String,
    placeholder: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    width: Dp = 320.dp,
) {
    val colors = LocalMederiColors.current
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    MederiDialog(
        onDismiss = onDismiss,
        width = width,
        title = { Text(title, style = WorkspaceStyle.Title, color = colors.textPrimary) },
    ) {
        WorkspaceTextField(value, { value = it }, Modifier.fillMaxWidth(), placeholder = placeholder)
        WorkspaceDialogActions(confirmLabel, cancelLabel, { onConfirm(value) }, onDismiss,
            confirmEnabled = value.isNotBlank())
    }
}
