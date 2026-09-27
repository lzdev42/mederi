package xyz.mederi.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.settings_stepper_decrease
import mederi.app.shared.generated.resources.settings_stepper_increase
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * 设置页统一卡片容器。
 * 现状 12dp 圆角：MederiRadius 通用刻度无 12（Card=8 / Dialog=12 为弹窗保留），按现状直用并注释。
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
            .clip(RoundedCornerShape(12.dp)) // 12 不在 MederiRadius 通用刻度（Card=8/Dialog=12 弹窗保留），现状直用
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(12.dp))
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
                style = MederiTypeScale.H2.copy(fontWeight = FontWeight.Bold) // 16sp Bold（H2 默认 Medium）
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = colors.textMuted,
                    style = MederiTypeScale.Body.copy(lineHeight = 16.sp) // 12sp Normal + 现状 16sp 行高
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = MederiSpacing.LG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MederiSpacing.MD)
        ) {
            if (icon != null) {
                SettingsIconBadge(
                    icon = icon,
                    tint = iconTint ?: colors.accentPrimary,
                    size = 32.dp, // 32 不在 MederiSpacing 刻度，现状直用
                    iconSize = MederiSpacing.LG
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    style = MederiTypeScale.Section.copy(fontWeight = FontWeight.SemiBold) // 13.5sp SemiBold
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.textMuted,
                        style = MederiTypeScale.BodyCompact.copy(lineHeight = 15.sp) // 11.5sp Normal + 现状 15sp 行高
                    )
                }
            }
        }
        action()
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
    colors: MederiColors = LocalMederiColors.current
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(horizontal = MederiSpacing.XS, vertical = MederiSpacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS)
    ) {
        val canDecrease = value > minValue
        val canIncrease = value < maxValue

        // 减号按钮
        Box(
            modifier = Modifier
                .size(MederiSpacing.XXL) // 24dp
                .clip(RoundedCornerShape(MederiRadius.Control))
                .background(if (canDecrease) colors.surfaceCard else Color.Transparent)
                .clickable(enabled = canDecrease) { onValueChange(value - 1) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Minus,
                contentDescription = stringResource(Res.string.settings_stepper_decrease),
                tint = if (canDecrease) colors.textPrimary else colors.iconMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(MederiSpacing.MD)
            )
        }

        // 数值显示
        Text(
            text = value.toString(),
            color = colors.textPrimary,
            style = MederiTypeScale.Title.copy(fontWeight = FontWeight.Bold), // 13sp Bold
            modifier = Modifier.widthIn(min = 28.dp).wrapContentWidth(Alignment.CenterHorizontally) // 28 不在刻度，直用
        )

        // 加号按钮
        Box(
            modifier = Modifier
                .size(MederiSpacing.XXL) // 24dp
                .clip(RoundedCornerShape(MederiRadius.Control))
                .background(if (canIncrease) colors.surfaceCard else Color.Transparent)
                .clickable(enabled = canIncrease) { onValueChange(value + 1) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = stringResource(Res.string.settings_stepper_increase),
                tint = if (canIncrease) colors.textPrimary else colors.iconMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(MederiSpacing.MD)
            )
        }
    }
}

/**
 * 紧凑型胶囊双选项切换控件（Segmented Pill Toggle）。
 */
@Composable
fun SegmentedPillToggle(
    selectedIndex: Int,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Card))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Card))
            .padding(MederiSpacing.Micro),
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.Micro),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, title ->
            val isSelected = selectedIndex == index
            val bg by animateColorAsState(if (isSelected) colors.surfaceCard else Color.Transparent)
            val textColor by animateColorAsState(if (isSelected) colors.textPrimary else colors.textMuted)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MederiRadius.Control))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = MederiSpacing.Compact, vertical = 5.dp), // 5 不在 MederiSpacing 刻度（XS=4/Tight=6），现状直用
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = textColor,
                    style = MederiTypeScale.BodyCompact.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ) // 11.5sp
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
