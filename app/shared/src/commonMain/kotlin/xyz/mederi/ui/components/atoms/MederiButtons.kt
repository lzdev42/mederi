package xyz.mederi.ui.components.atoms

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * Mederi 按钮变体合集（docs/design/standard/02-components.md §1）。
 *
 * 结构（尺寸/圆角/字号）按标准走 [MederiSpacing] / [MederiRadius] / [MederiTypeScale]；
 * 颜色读 [LocalMederiColors] 现有字段保持当前视觉（本轮不迁 Radix 色阶）。
 *
 * 现有色板缺口的近似约定（各变体 KDoc 亦注明）：
 * - bg-inverted / on-inverted → textPrimary + surfaceSidebar（dark 下近白/近黑、light 下近黑/近白，天然反色对）。
 * （border-strong 与 accent-bg/accent-text 等字段已入 [MederiColors]，soft iris 变体直接引用，不再近似。）
 *
 * 组件级尺寸不在全局刻度内（28dp 按钮、22dp 面板图标、11dp 小图标、5dp gap），按标准值直用并注释。
 */
/** §1.1 主操作字号 12sp/500（MederiTypeScale.Body + Medium） */
private val DecisionLabel: TextStyle = MederiTypeScale.Body.copy(fontWeight = FontWeight.Medium)

/** §1.2 / §1.4 紧凑字号 11sp/500（MederiTypeScale.Label） */
private val CompactLabel: TextStyle = MederiTypeScale.Label

/** hover / active 交互状态快照 */
private data class IconButtonState(val hovered: Boolean, val pressed: Boolean)

@Composable
private fun rememberIconButtonState(interactionSource: MutableInteractionSource): IconButtonState {
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    return IconButtonState(hovered, pressed)
}

/** 文字按钮通用骨架：clip/background/border/hover/click 自管理；[height] = null 时高度随内容。 */
@Composable
private fun MederiTextButtonBase(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp?,
    shape: Shape,
    backgroundColor: Color,
    borderColor: Color?,
    contentColor: Color,
    labelStyle: TextStyle,
    gap: Dp,
    paddingHorizontal: Dp,
    paddingVertical: Dp = 0.dp,
    interactionSource: MutableInteractionSource,
) {
    Box(
        modifier = modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(backgroundColor)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = paddingHorizontal, vertical = paddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(MederiSpacing.MD), tint = contentColor)
            }
            Text(
                text = text,
                color = contentColor,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 图标按钮通用骨架：clip/background/border/hover/click 自管理；[scale] 供按压缩放（send）。 */
@Composable
private fun MederiIconButtonBase(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    size: Dp,
    shape: Shape,
    backgroundColor: Color,
    borderColor: Color?,
    iconTint: Color,
    iconSize: Dp,
    interactionSource: MutableInteractionSource,
    scale: Float = 1f,
) {
    Box(
        modifier = modifier
            .scale(scale)
            .size(size)
            .clip(shape)
            .background(backgroundColor)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = iconTint)
    }
}

/**
 * 主操作按钮（02-components §1.1 btn-primary-decision，soft iris）。
 * 高 28dp / padding 0-12dp / 圆角 Control / 12sp-500 / gap 5dp。
 * 常态 bg accentBg + text accentText + border accentBorder（[danger] = true 时切 dangerBg/dangerText +
 * dangerText 0.35 描边）；hover bg 向 text 色 lerp 0.12、border → accentFocus（danger → dangerText）；
 * active 向黑压暗（iris-5 等效）；disabled = surfaceHover 底 + textMuted + 透明描边。
 * 调用点（QuestionCard 提交 / PlanApprovalCard Proceed / Dialog 确认）统一 soft iris，属标准预期。
 */
@Composable
fun MederiPrimaryDecisionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    // soft iris：常态底/字/描边 = accentBg/accentText/accentBorder（danger 变体 = dangerBg/dangerText）
    val base = if (danger) colors.dangerBg else colors.accentBg
    val textColor = if (danger) colors.dangerText else colors.accentText
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceHover
            pressed -> lerp(base, Color.Black, 0.22f) // active ≈ iris-5（Color.Black 为压暗原语，非 hex 字面量）
            hovered -> lerp(base, textColor, 0.12f) // hover 向 text 色 lerp（iris-4 等效）
            else -> base
        },
        animationSpec = tween(120),
        label = "primaryDecisionBg",
    )
    val border by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            hovered -> if (danger) colors.dangerText else colors.accentFocus
            else -> if (danger) colors.dangerText.copy(alpha = 0.35f) else colors.accentBorder
        },
        animationSpec = tween(120),
        label = "primaryDecisionBorder",
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) textColor else colors.textMuted,
        animationSpec = tween(120),
        label = "primaryDecisionContent",
    )
    MederiTextButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = 28.dp, // 标准 §1.1；28 不在全局刻度内，按标准值直用
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = bg,
        borderColor = border,
        contentColor = contentColor,
        labelStyle = DecisionLabel,
        gap = 5.dp, // 标准 §1.1；MederiSpacing 刻度无 5 命名（5 归 Tiny/Tight 按需），按标准值直用
        paddingHorizontal = MederiSpacing.MD,
        interactionSource = interactionSource,
    )
}

/**
 * 次级凸起按钮（02-components §1.2 rt-variant-surface / btn-review-diff）。
 * 高 24dp / padding 0-8dp / 圆角 Control / 11sp-500 / gap 4dp。
 * bg surfaceCard + border surfaceCardBorder + text textPrimary；hover bg surfaceHover + border 强一档（borderStrong）；
 * disabled text textMuted。
 */
@Composable
fun MederiSurfaceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) colors.surfaceHover else colors.surfaceCard,
        animationSpec = tween(120),
        label = "surfaceButtonBg",
    )
    val border by animateColorAsState(
        targetValue = if (hovered && enabled) colors.borderStrong else colors.surfaceCardBorder,
        animationSpec = tween(120),
        label = "surfaceButtonBorder",
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) colors.textPrimary else colors.textMuted,
        animationSpec = tween(120),
        label = "surfaceButtonContent",
    )
    MederiTextButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = MederiSpacing.XXL, // 24dp
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = bg,
        borderColor = border,
        contentColor = contentColor,
        labelStyle = CompactLabel,
        gap = MederiSpacing.XS, // 4dp
        paddingHorizontal = MederiSpacing.SM,
        interactionSource = interactionSource,
    )
}

/**
 * 无边框文字按钮（02-components §1.3 rt-variant-ghost，footer 文字按钮）。
 * 透明 bg + text textSecondary；hover bg surfaceHover + text textPrimary；disabled text textMuted。
 * 标准未给尺寸，沿用紧凑家族：高 24dp / padding 0-8dp / 圆角 Control / 11sp-500 / gap 4dp。
 */
@Composable
fun MederiGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) colors.surfaceHover else Color.Transparent,
        animationSpec = tween(120),
        label = "ghostButtonBg",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textMuted
            hovered -> colors.textPrimary
            else -> colors.textSecondary
        },
        animationSpec = tween(120),
        label = "ghostButtonContent",
    )
    MederiTextButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = MederiSpacing.XXL, // 24dp
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = bg,
        borderColor = null,
        contentColor = contentColor,
        labelStyle = CompactLabel,
        gap = MederiSpacing.XS,
        paddingHorizontal = MederiSpacing.SM,
        interactionSource = interactionSource,
    )
}

/**
 * 紧凑描边按钮（02-components §1.4 btn-compact-stroke）。
 * padding 2-8dp / 圆角 Control / 11sp-500 / gap 4dp；bg surfaceHover + border surfaceCardBorder + text textPrimary，
 * hover border 强一档（borderStrong）。无固定高度，高度随内容（2dp 上下 padding）。
 * 注：标准裁定 hover 文字色 = text-primary（原型裸白 #ffffff 在 light 下错误），本实现遵循裁定。
 */
@Composable
fun MederiCompactStrokeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val border by animateColorAsState(
        targetValue = if (hovered && enabled) colors.borderStrong else colors.surfaceCardBorder,
        animationSpec = tween(120),
        label = "compactStrokeBorder",
    )
    val contentColor = if (enabled) colors.textPrimary else colors.textMuted
    MederiTextButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = null,
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = colors.surfaceHover,
        borderColor = border,
        contentColor = contentColor,
        labelStyle = CompactLabel,
        gap = MederiSpacing.XS,
        paddingHorizontal = MederiSpacing.SM,
        paddingVertical = MederiSpacing.Micro, // 2dp
        interactionSource = interactionSource,
    )
}

/**
 * 极简图标按钮（02-components §1.5 icon-btn-minimal）。
 * 28×28 / 圆角 4dp（MederiRadius.Square）/ 透明 bg / icon textSecondary；hover bg surfaceHover + icon textPrimary（120ms）。
 * [active] = 高亮态（accent 底 + accent 图标），Sidebar 顶栏固定/收起按钮在用。
 * 与 [MederiIconButton]（CircleShape + buttonSecondary solid bg + active 高亮）互补而非重复——
 * 本变体为透明方形 + hover 反馈；标准建议给 MederiIconButton 加 shape 参数，收敛时按需统一。
 */
@Composable
fun MederiMinimalIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val state = rememberIconButtonState(interactionSource)
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            active -> colors.accentPrimary.copy(alpha = 0.15f)
            state.hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "minimalIconBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textMuted
            active -> colors.accentPrimary
            state.hovered -> colors.textPrimary
            else -> colors.textSecondary
        },
        animationSpec = tween(120),
        label = "minimalIconTint",
    )
    MederiIconButtonBase(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = 28.dp, // 标准 §1.5；28 不在全局刻度内，按标准值直用
        shape = RoundedCornerShape(MederiRadius.Square),
        backgroundColor = bg,
        borderColor = null,
        iconTint = tint,
        iconSize = MederiSpacing.Loose, // 14dp（0.5×28，对齐 MederiIconButton 图标比例）
        interactionSource = interactionSource,
    )
}

/**
 * 方形图标按钮（02-components §1.6 icon-square-btn）。
 * 28×28 / 圆角 Control / bg surfaceHover + border surfaceCardBorder + icon textSecondary；
 * hover icon textPrimary + border 强一档（borderStrong）。
 * [active]（dock 选中态等）：accentPrimary 0.15 底纹 + accent 描边 + accent 图标，优先级高于 hover（对齐 RightDock 现状）。
 */
@Composable
fun MederiIconSquareButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val state = rememberIconButtonState(interactionSource)
    val border by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceCardBorder
            active -> colors.accentPrimary.copy(alpha = 0.35f)
            state.hovered -> colors.borderStrong
            else -> colors.surfaceCardBorder
        },
        animationSpec = tween(120),
        label = "iconSquareBorder",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textMuted
            active -> colors.accentPrimary
            state.hovered -> colors.textPrimary
            else -> colors.textSecondary
        },
        animationSpec = tween(120),
        label = "iconSquareTint",
    )
    MederiIconButtonBase(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = 28.dp, // 标准 §1.6；28 不在全局刻度内，按标准值直用
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = if (active) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceHover,
        borderColor = border,
        iconTint = tint,
        iconSize = MederiSpacing.Loose, // 14dp
        interactionSource = interactionSource,
    )
}

/**
 * 面板头部图标按钮（02-components §1.7 panel-header-btn）。
 * 22×22 / 圆角 4dp（MederiRadius.Square）/ 透明 + icon textMuted；hover bg surfaceHover + textPrimary。
 * chevron 展开 rotate 180°（0.8s）由调用方处理，本 atom 只负责按钮本体。
 */
@Composable
fun MederiPanelHeaderIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val state = rememberIconButtonState(interactionSource)
    val bg by animateColorAsState(
        targetValue = if (state.hovered && enabled) colors.surfaceHover else Color.Transparent,
        animationSpec = tween(120),
        label = "panelHeaderIconBg",
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textMuted
            state.hovered -> colors.textPrimary
            else -> colors.textMuted
        },
        animationSpec = tween(120),
        label = "panelHeaderIconTint",
    )
    MederiIconButtonBase(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = 22.dp, // 标准 §1.7；22 不在全局刻度内，按标准值直用
        shape = RoundedCornerShape(MederiRadius.Square),
        backgroundColor = bg,
        borderColor = null,
        iconTint = tint,
        iconSize = 11.dp, // 0.5×22（对齐 MederiIconButton 图标比例）；11 不在全局刻度内
        interactionSource = interactionSource,
    )
}

/**
 * 发送按钮（02-components §1.8 send-round-btn，反色控件）。
 * 28×28 / 圆角 Control / bg bgInverted + icon onInverted（dark 近白底深图标 / light 近黑底浅图标，反色对）；
 * hover bg 提亮为 bgInvertedHover（dark 纯白 / light gray-12，01-tokens §1.2 bg-inverted-hover）；
 * disabled = buttonSecondary + textMuted；active scale 0.96（80ms）。
 * 现状：ChatInputCard SendButton 已收敛到本 atom。
 */
@Composable
fun MederiSendRoundButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MederiColors = LocalMederiColors.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val state = rememberIconButtonState(interactionSource)
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.buttonSecondary
            state.hovered -> colors.bgInvertedHover
            else -> colors.bgInverted
        },
        animationSpec = tween(120),
        label = "sendRoundBg",
    )
    val tint = if (enabled) colors.onInverted else colors.textMuted
    val scale by animateFloatAsState(
        targetValue = if (state.pressed && enabled) 0.96f else 1f,
        animationSpec = tween(80),
        label = "sendRoundScale",
    )
    MederiIconButtonBase(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = 28.dp, // 标准 §1.8；28 不在全局刻度内，按标准值直用
        shape = RoundedCornerShape(MederiRadius.Control),
        backgroundColor = bg,
        borderColor = null,
        iconTint = tint,
        iconSize = MederiSpacing.Loose, // 14dp
        interactionSource = interactionSource,
        scale = scale,
    )
}
