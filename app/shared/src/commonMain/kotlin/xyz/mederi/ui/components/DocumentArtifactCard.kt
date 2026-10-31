package xyz.mederi.ui.components

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_lines
import mederi.app.shared.generated.resources.attachment_reader
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.formatMessageTime
import androidx.compose.ui.semantics.Role
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.LocalMederiColors

/**
 * 轻量文档对象行。整行保持可打开，标题与元信息承载内容层级，
 * 不叠加图标底座、格式徽标和独立 CTA 描边。
 */
@Composable
fun DocumentArtifactCard(
    title: String,
    lineCount: Int,
    charCount: Int,
    createdAt: Long,
    isStreaming: Boolean,
    isCompleted: Boolean,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isRunning = isStreaming && !isCompleted
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) colors.surfaceHover else colors.surfaceCard,
        animationSpec = LocalWorkspaceMotion.current.feedbackSpec(),
        label = "artifactRowBackground",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WorkspaceStyle.ObjectRadius))
            .background(bg)
            .clickable(interactionSource = interactionSource, role = Role.Button, onClick = onOpenInExtension)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isRunning) {
            WorkspaceLoadingIndicator(
                color = colors.accentPrimary,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
        } else {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = WorkspaceStyle.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                createdAt.takeIf { it > 0 }?.let(::formatMessageTime),
                stringResource(Res.string.attachment_lines, lineCount),
                formatCharCount(charCount),
            ).joinToString(" · ")
            Text(
                text = meta,
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.heightIn(min = WorkspaceStyle.IconTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.attachment_reader),
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
            )
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(WorkspaceStyle.IconSize),
            )
        }
    }
}
