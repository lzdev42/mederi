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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.mcp.runtime.McpRuntime
import xyz.mederi.util.rememberClipboardCopy

private enum class SetupOperation { INSTALL, CHECK }

@Composable
internal fun RuntimeSetupPage(
    missing: Set<McpRuntime.Component>,
    onMinimize: () -> Unit,
    onChecked: (Set<McpRuntime.Component>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val copy = rememberClipboardCopy()
    var operation by remember { mutableStateOf<SetupOperation?>(SetupOperation.INSTALL) }
    var manual by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var terminalCommand by remember(manual, missing) { mutableStateOf<Result<String>?>(null) }

    suspend fun runSetup(request: SetupOperation) {
        try {
            if (request == SetupOperation.INSTALL) {
                manual = false
                showDetails = false
                details = try {
                    RuntimeInstaller.install(missing)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure.message
                }
            }
            onChecked(runInterruptible(Dispatchers.IO) { McpRuntime.missing() })
        } finally {
            operation = null
        }
    }

    fun start(request: SetupOperation) {
        if (operation != null) return
        operation = request
        scope.launch { runSetup(request) }
    }

    LaunchedEffect(Unit) { runSetup(SetupOperation.INSTALL) }

    LaunchedEffect(manual, operation, missing) {
        if (!manual || operation != null) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val remaining = runInterruptible(Dispatchers.IO) { McpRuntime.missing() }
            if (remaining != missing) {
                onChecked(remaining)
                break
            }
        }
    }

    LaunchedEffect(manual, missing) {
        if (manual && !RuntimeInstaller.mac) {
            terminalCommand = try {
                Result.success(withContext(Dispatchers.IO) { RuntimeInstaller.terminalCommand(missing) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.runtime_setup_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        missing.joinToString(" · ") {
                            when (it) {
                                McpRuntime.Component.NODE -> "Node.js"
                                McpRuntime.Component.PYTHON -> "Python 3"
                                McpRuntime.Component.GIT -> "Git"
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val activeOperation = operation
                    if (activeOperation != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        val status = when (activeOperation) {
                            SetupOperation.INSTALL -> Res.string.runtime_setup_installing
                            SetupOperation.CHECK -> Res.string.runtime_setup_checking
                        }
                        Text(stringResource(status))
                        OutlinedButton(onClick = onMinimize) {
                            Text(stringResource(Res.string.runtime_setup_minimize))
                        }
                    } else {
                        Text(stringResource(Res.string.runtime_setup_missing), color = MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { start(SetupOperation.INSTALL) }) {
                                Text(stringResource(Res.string.runtime_setup_retry))
                            }
                            OutlinedButton(onClick = { start(SetupOperation.CHECK) }) {
                                Text(stringResource(Res.string.runtime_setup_check))
                            }
                            TextButton(onClick = { manual = !manual }) {
                                Text(stringResource(Res.string.runtime_setup_manual))
                            }
                        }
                        if (manual) {
                            if (RuntimeInstaller.mac) {
                                Text(stringResource(Res.string.runtime_setup_homebrew_install))
                                RuntimeCommand(RuntimeInstaller.HOMEBREW_INSTALL, copy)
                                Text(stringResource(Res.string.runtime_setup_homebrew_packages))
                                RuntimeCommand(RuntimeInstaller.homebrewCommand(missing), copy)
                            } else {
                                val command = terminalCommand?.getOrNull()
                                when {
                                    command != null -> {
                                        val hint = if (RuntimeInstaller.windows) Res.string.runtime_setup_windows
                                        else Res.string.runtime_setup_linux
                                        Text(stringResource(hint))
                                        RuntimeCommand(command, copy)
                                    }
                                    terminalCommand?.isFailure == true -> Text(stringResource(Res.string.runtime_setup_command_error))
                                    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                        details?.takeIf { it.isNotBlank() }?.let { output ->
                            TextButton(onClick = { showDetails = !showDetails }) {
                                Text(
                                    stringResource(
                                        if (showDetails) Res.string.runtime_setup_hide_details
                                        else Res.string.runtime_setup_show_details
                                    )
                                )
                            }
                            if (showDetails) {
                                SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeCommand(command: String, copy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            SelectionContainer(Modifier.padding(12.dp)) {
                Text(command, fontFamily = FontFamily.Monospace)
            }
        }
        OutlinedButton(onClick = { copy(command) }) {
            Text(stringResource(Res.string.runtime_setup_copy))
        }
    }
}
