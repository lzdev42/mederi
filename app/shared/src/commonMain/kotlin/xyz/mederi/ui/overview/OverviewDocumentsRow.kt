package xyz.mederi.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.rp_documents_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.DocumentTarget

/** VM入口：本会话全部文档（计划除外）；空则整行不渲染。 */
@Composable
fun OverviewDocumentsRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverviewDocumentsContent(
        documents = viewModel.sessionDocuments,
        expanded = expanded,
        onToggle = onToggle,
        onOpen = { doc ->
            viewModel.openDocument(
                DocumentTarget.Content(
                    id = doc.artifactId,
                    title = doc.title,
                    content = doc.content,
                    lineCount = doc.lineCount,
                    charCount = doc.charCount,
                    isStreaming = doc.isStreaming,
                )
            )
        },
        modifier = modifier,
    )
}

/** 纯内容行：documents/onOpen 可注入，独立可测（不依赖 VM）。 */
@Composable
internal fun OverviewDocumentsContent(
    documents: List<ChatListItem.DocumentCard>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (ChatListItem.DocumentCard) -> Unit,
    modifier: Modifier = Modifier,
    colors: MederiColors = LocalMederiColors.current,
) {
    if (documents.isEmpty()) return
    OverviewAccordionRow(
        icon = FeatherIcons.FileText,
        title = stringResource(Res.string.rp_documents_title),
        summary = { Text("${documents.size}", style = WorkspaceStyle.Caption, color = colors.textSecondary) },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MederiSpacing.XS)) {
            documents.forEach { doc ->
                DocumentEntryRow(doc, onOpen, colors)
            }
        }
    }
}

/** 单个文档条目：标题 + 简要统计，点击打开文档阅读面板。 */
@Composable
private fun DocumentEntryRow(
    doc: ChatListItem.DocumentCard,
    onOpen: (ChatListItem.DocumentCard) -> Unit,
    colors: MederiColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceStyle.ControlHeight)
            .clickable { onOpen(doc) }
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        Icon(
            FeatherIcons.FileText,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize),
        )
        Text(
            text = doc.title,
            color = colors.textPrimary,
            style = WorkspaceStyle.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
