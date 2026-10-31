package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.copy_done
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_stepper_decrease
import mederi.app.shared.generated.resources.settings_stepper_increase
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.components.atoms.WorkspaceNavigationTab
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.MederiTypeScale

/**
 * 设置页统一卡片容器。
 * 同一8dp中性分组底，不叠加常态边框；普通设置分区也可直接使用无框SettingsSection。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
    padding: PaddingValues = PaddingValues(MederiSpacing.LG),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius))
            .background(colors.surfaceCard)
            .padding(padding),
        content = content
    )
}

/**
 * 设置项大分类 Section（标题 + 描述 + 内容）。
 */
@Composable
fun SettingsSection(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.MD)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = WorkspaceStyle.Title
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = colors.textMuted,
                    style = WorkspaceStyle.Caption
                )
            }
        }
        content()
    }
}

/**
 * 现代标准设置行（左侧：图标/标题/描述，右侧：操作控件）。
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
    action: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val text: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, null, tint = iconTint ?: colors.textSecondary,
                    modifier = Modifier.size(WorkspaceStyle.IconSize))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = colors.textPrimary, style = WorkspaceStyle.Body)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.textSecondary, style = WorkspaceStyle.Caption)
                }
            }
        }
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth < 440.dp * fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                text()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { text() }
            action()
        }
    }
}

/**
 * 精致的图标托盘容器（带柔光半透明底色）。
 */
@Composable
fun SettingsIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp, // 32 不在 MederiSpacing 刻度，现状直用
    iconSize: Dp = MederiSpacing.LG,
    cornerRadius: Dp = MederiRadius.Card
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 紧凑现代的数字步进调节器（- [数字] +）。
 */
@Composable
fun NumberStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minValue: Int = 1,
    maxValue: Int = 32,
    colors: MederiColors = LocalMederiColors.current,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        WorkspaceIconButton(
            icon = FeatherIcons.Minus,
            onClick = { onValueChange(value - 1) },
            contentDescription = stringResource(Res.string.settings_stepper_decrease),
            enabled = enabled && value > minValue,
            colors = colors,
        )
        Text(
            text = value.toString(),
            color = if (enabled) colors.textPrimary else colors.textMuted,
            style = WorkspaceStyle.Body,
            modifier = Modifier.widthIn(min = WorkspaceStyle.ControlHeight)
                .wrapContentWidth(Alignment.CenterHorizontally),
        )
        WorkspaceIconButton(
            icon = FeatherIcons.Plus,
            onClick = { onValueChange(value + 1) },
            contentDescription = stringResource(Res.string.settings_stepper_increase),
            enabled = enabled && value < maxValue,
            colors = colors,
        )
    }
}

/** 复用工作台选择项；窄屏按自然宽度换行，选择仍由调用方持有。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedPillToggle(
    selectedIndex: Int,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
) {
    CompositionLocalProvider(LocalMederiColors provides colors) {
        FlowRow(
            modifier = modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
            verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
        ) {
            options.forEachIndexed { index, title ->
                WorkspaceNavigationTab(
                    text = title,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    role = Role.RadioButton,
                )
            }
        }
    }
}

/**
 * 紧凑现代的通用设置输入框（基于 BasicTextField，杜绝 Material3 OutlinedTextField 强制矮高导致的文字上下挤压扁平问题）。
 */
@Composable
fun SettingsInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = MederiTypeScale.Row, // 12.5sp Normal
    colors: MederiColors = LocalMederiColors.current
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(horizontal = MederiSpacing.MD, vertical = 9.dp), // 9 不在 MederiSpacing 刻度（SM=8/Compact=10），现状直用
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)
    ) {
        leadingIcon?.invoke()
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.textMuted,
                    fontSize = textStyle.fontSize,
                    fontFamily = textStyle.fontFamily
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = textStyle.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accentPrimary),
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth()
            )
        }
        trailingIcon?.invoke()
    }
}


/** 系统信息和遥控地址共用同一完整值/复制布局；回调提供精确原值。 */
@Composable
internal fun SettingsCopyableValue(
    label: String,
    value: String,
    onCopy: (String) -> Unit,
    copied: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsRow(label) {
            xyz.mederi.ui.components.atoms.WorkspaceActionButton(
                org.jetbrains.compose.resources.stringResource(if (copied) mederi.app.shared.generated.resources.Res.string.copy_done else mederi.app.shared.generated.resources.Res.string.copy),
                { onCopy(value) }, icon = if (copied) compose.icons.FeatherIcons.Check else compose.icons.FeatherIcons.Copy)
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, style = WorkspaceStyle.Body, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = colors.textPrimary)
        }
    }
}
