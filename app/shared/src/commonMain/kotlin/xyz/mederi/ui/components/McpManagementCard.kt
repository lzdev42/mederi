package xyz.mederi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.TextSubmissionDialog

/** 新增/编辑只装配文案和原JSON回调，不在UI解析或重建JSON。 */
@Composable
internal fun McpConfigDialog(
    title: String,
    initialJson: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Result<Unit>,
    colors: MederiColors,
) {
    CompositionLocalProvider(LocalMederiColors provides colors) {
        TextSubmissionDialog(title, stringResource(Res.string.mcp_config_hint), initialJson,
            stringResource(Res.string.mcp_configuration_json), stringResource(Res.string.mcp_save),
            stringResource(Res.string.mcp_saving), stringResource(Res.string.mcp_save_failed),
            onDismiss, onConfirm, singleLine = false)
    }
}

internal const val DEFAULT_MCP_TEMPLATE = """{
  "mcpServers": {
    "my-service": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"]
    }
  }
}"""
