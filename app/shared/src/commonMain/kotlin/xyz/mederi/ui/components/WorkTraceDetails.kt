package xyz.mederi.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Layers
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.worktrace_detail_commands
import mederi.app.shared.generated.resources.worktrace_detail_edits
import mederi.app.shared.generated.resources.worktrace_detail_failures
import mederi.app.shared.generated.resources.worktrace_detail_lists
import mederi.app.shared.generated.resources.worktrace_detail_mcp
import mederi.app.shared.generated.resources.worktrace_detail_notes
import mederi.app.shared.generated.resources.worktrace_detail_other
import mederi.app.shared.generated.resources.worktrace_detail_reads
import mederi.app.shared.generated.resources.worktrace_detail_searches
import mederi.app.shared.generated.resources.worktrace_detail_specs
import mederi.app.shared.generated.resources.worktrace_detail_subagent_status
import mederi.app.shared.generated.resources.worktrace_detail_verifications
import mederi.app.shared.generated.resources.worktrace_detail_web_searches
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import xyz.mederi.ui.ChatListItem
import xyz.mederi.ui.WorkTraceEntry
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.components.atoms.ProcessRow

/** 收起一段过程明细，展开后仍按消息顺序使用原来的步骤组件。 */
@Composable
internal fun WorkTraceDetails(
    details: WorkTraceEntry.Details,
    renderStepItem: @Composable (ChatListItem) -> Unit,
) {
    var expanded by rememberSaveable(details.key) { mutableStateOf(false) }
    val label = buildList {
        if (details.failureCount > 0) {
            add(pluralStringResource(Res.plurals.worktrace_detail_failures, details.failureCount, details.failureCount))
        }
        for ((kind, count) in details.actionCounts) {
            add(pluralStringResource(workTraceDetailLabel(kind), count, count))
        }
        if (details.noteCount > 0) {
            add(pluralStringResource(Res.plurals.worktrace_detail_notes, details.noteCount, details.noteCount))
        }
    }.joinToString(" · ")

    ProcessRow(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
        icon = FeatherIcons.Layers,
        label = label,
    ) {
        for (item in details.items) key(item.key) { renderStepItem(item) }
    }
}

private fun workTraceDetailLabel(kind: ToolActionKind): PluralStringResource = when (kind) {
    ToolActionKind.READ -> Res.plurals.worktrace_detail_reads
    ToolActionKind.SEARCH -> Res.plurals.worktrace_detail_searches
    ToolActionKind.EDIT -> Res.plurals.worktrace_detail_edits
    ToolActionKind.COMMAND -> Res.plurals.worktrace_detail_commands
    ToolActionKind.LIST -> Res.plurals.worktrace_detail_lists
    ToolActionKind.VERIFY -> Res.plurals.worktrace_detail_verifications
    ToolActionKind.GENERATE_SPEC -> Res.plurals.worktrace_detail_specs
    ToolActionKind.SUBAGENT_STATUS -> Res.plurals.worktrace_detail_subagent_status
    ToolActionKind.MCP -> Res.plurals.worktrace_detail_mcp
    ToolActionKind.WEB_SEARCH -> Res.plurals.worktrace_detail_web_searches
    else -> Res.plurals.worktrace_detail_other
}
