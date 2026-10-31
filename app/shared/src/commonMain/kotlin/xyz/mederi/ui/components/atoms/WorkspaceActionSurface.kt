package xyz.mederi.ui.components.atoms

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle

/** 只决定表现强调，不承载审批/错误等业务状态。 */
enum class WorkspaceActionTone { Neutral, Accent, Danger }

/** 提问、审批与可恢复错误的同一外壳；中性底色，强调仅限低对比边界。 */
@Composable
fun WorkspaceActionSurface(
    modifier: Modifier = Modifier,
    tone: WorkspaceActionTone = WorkspaceActionTone.Accent,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMederiColors.current
    val shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius)
    val border = when (tone) {
        WorkspaceActionTone.Neutral -> colors.divider.copy(alpha = 0.6f)
        WorkspaceActionTone.Accent -> colors.accentBorder
        WorkspaceActionTone.Danger -> colors.accentDanger.copy(alpha = 0.35f)
    }
    Column(
        modifier = modifier.clip(shape).background(colors.surfaceCard)
            .border(1.dp, border, shape).padding(MederiSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
        content = content,
    )
}


/** 工作台交互卡共用文字动作；允许换行/字体缩放，不改变调用者的业务可用性。 */
@Composable
fun WorkspaceActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: ImageVector? = null,
    contentColor: Color? = null,
    colors: MederiColors = LocalMederiColors.current,
    tone: WorkspaceActionTone = WorkspaceActionTone.Accent,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            primary && tone == WorkspaceActionTone.Danger -> colors.accentDanger.copy(alpha = 0.1f)
            primary -> colors.accentBg
            hovered || focused -> colors.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "workspaceActionBackground",
    )
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = WorkspaceStyle.ControlHeight),
        shape = RoundedCornerShape(MederiRadius.Control),
        contentPadding = PaddingValues(horizontal = MederiSpacing.MD, vertical = MederiSpacing.Tight),
        interactionSource = source,
        colors = ButtonDefaults.textButtonColors(
            containerColor = background,
            contentColor = contentColor ?: when {
                tone == WorkspaceActionTone.Danger -> colors.dangerText
                primary -> colors.accentText
                else -> colors.textSecondary
            },
            disabledContentColor = colors.textMuted,
        ),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(WorkspaceStyle.IconSize))
            Spacer(Modifier.width(MederiSpacing.Tight))
        }
        Text(text = text, style = WorkspaceStyle.Body)
    }
}
