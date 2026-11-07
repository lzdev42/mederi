package xyz.mederi.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.mcp.runtime.McpRuntime
import xyz.mederi.util.rememberClipboardCopy

@Composable
internal fun RuntimeSetupPage(
    missing: Set<McpRuntime.Component>,
    onChecked: (Set<McpRuntime.Component>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val copy = rememberClipboardCopy()
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var terminalCommand by remember(manual, missing) { mutableStateOf<Result<String>?>(null) }

    LaunchedEffect(manual, missing) {
        if (manual) {
            terminalCommand = try {
                Result.success(withContext(Dispatchers.IO) { RuntimeInstaller.terminalCommand(missing) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
    }

    fun check(install: Boolean) {
        busy = true
        failed = false
        details = null
        scope.launch {
            try {
                if (install) {
                    details = try {
                        RuntimeInstaller.install(missing)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failure.message
                    }
                }
                val remaining = withContext(Dispatchers.IO) { McpRuntime.missing() }
                failed = remaining.isNotEmpty()
                if (install && failed) manual = true
                onChecked(remaining)
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(Res.string.runtime_setup_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(Res.string.runtime_setup_description))
                    Text(stringResource(Res.string.runtime_setup_required), style = MaterialTheme.typography.titleSmall)
                    missing.forEach { component ->
                        val label = when (component) {
                            McpRuntime.Component.NODE -> "Node.js · npm · npx"
                            McpRuntime.Component.PYTHON -> "Python"
                            McpRuntime.Component.UV -> "uv · uvx"
                        }
                        Text("•  $label", style = MaterialTheme.typography.titleMedium)
                    }
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(Res.string.runtime_setup_busy))
                    } else if (failed) {
                        Text(stringResource(Res.string.runtime_setup_missing), color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { check(true) }, enabled = !busy) {
                            Text(stringResource(Res.string.runtime_setup_install))
                        }
                        if (!manual) {
                            OutlinedButton(onClick = { check(false) }, enabled = !busy) {
                                Text(stringResource(Res.string.runtime_setup_check))
                            }
                        }
                        TextButton(onClick = { manual = !manual }, enabled = !busy) {
                            Text(stringResource(Res.string.runtime_setup_manual))
                        }
                    }
                    if (manual) {
                        val command = terminalCommand?.getOrNull()
                        when {
                            command != null -> {
                                val open = when {
                                    RuntimeInstaller.windows -> Res.string.runtime_setup_windows
                                    RuntimeInstaller.mac -> Res.string.runtime_setup_macos
                                    else -> Res.string.runtime_setup_linux
                                }
                                val paste = when {
                                    RuntimeInstaller.windows -> Res.string.runtime_setup_paste_windows
                                    RuntimeInstaller.mac -> Res.string.runtime_setup_paste_macos
                                    else -> Res.string.runtime_setup_paste_linux
                                }
                                Text(stringResource(Res.string.runtime_setup_step_copy))
                                OutlinedButton(onClick = { copy(command) }, enabled = !busy) {
                                    Text(stringResource(Res.string.runtime_setup_copy))
                                }
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                                    SelectionContainer(Modifier.padding(12.dp)) {
                                        Text(command, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Text(stringResource(open))
                                Text(stringResource(paste))
                                Text(stringResource(Res.string.runtime_setup_step_return))
                            }
                            terminalCommand?.isFailure == true -> Text(stringResource(Res.string.runtime_setup_command_error))
                            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = { check(false) }, enabled = !busy) {
                            Text(stringResource(Res.string.runtime_setup_check))
                        }
                    }
                    if (failed && !busy) {
                        details?.takeIf { it.isNotBlank() }?.let {
                            SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
