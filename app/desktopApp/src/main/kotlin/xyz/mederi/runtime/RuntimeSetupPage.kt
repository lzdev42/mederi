package xyz.mederi.runtime

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.TerminalSession
import xyz.mederi.mcp.runtime.McpRuntime
import xyz.mederi.ui.components.RuntimeInstallTerminal

private enum class SetupStep { CONFIRM, INSTALL, CHECK, FAILED, READY }

@Composable
internal fun RuntimeSetupPage(
    missing: Set<McpRuntime.Component>,
    onMinimize: () -> Unit,
    onChecked: (Set<McpRuntime.Component>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    var step by remember { mutableStateOf(SetupStep.CONFIRM) }
    var needsHomebrew by remember { mutableStateOf<Boolean?>(null) }
    var stage by remember { mutableStateOf(InstallStage.DOWNLOADING) }
    var terminal by remember { mutableStateOf<TerminalSession?>(null) }
    var errorDetails by remember { mutableStateOf<String?>(null) }
    var installExitCode by remember { mutableStateOf<Int?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        needsHomebrew = runInterruptible(Dispatchers.IO) { RuntimeInstaller.needsHomebrew() }
    }

    fun start(install: Boolean) {
        if (step != SetupStep.CONFIRM && step != SetupStep.FAILED) return
        step = if (install) SetupStep.INSTALL else SetupStep.CHECK
        errorDetails = null
        showDetails = false
        if (install) {
            terminal = null
            stage = InstallStage.DOWNLOADING
            installExitCode = null
        }

        scope.launch {
            var session: TerminalSession? = null
            try {
                if (install) {
                    val started = RuntimeInstaller.start(missing)
                    session = started
                    terminal = started
                    val status = InstallTerminalStatus()
                    started.output().collect { chunk ->
                        status.accept(chunk)?.let { stage = it }
                    }
                    started.isRunning.first { !it }
                    installExitCode = started.exitCode.value
                }
                step = SetupStep.CHECK
                val remaining = runInterruptible(Dispatchers.IO) { McpRuntime.missing() }
                if (remaining.isEmpty()) {
                    step = SetupStep.READY
                    delay(600)
                    onChecked(remaining)
                } else {
                    step = SetupStep.FAILED
                    onChecked(remaining)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errorDetails = failure.message
                step = SetupStep.FAILED
            } finally {
                session?.takeIf { it.isRunning.value }?.kill()
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (step == SetupStep.CONFIRM) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.runtime_setup_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(Res.string.runtime_setup_intro))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (McpRuntime.Component.NODE in missing) Text(stringResource(Res.string.runtime_setup_node))
                            if (McpRuntime.Component.PYTHON in missing) Text(stringResource(Res.string.runtime_setup_python))
                            if (McpRuntime.Component.GIT in missing) Text(stringResource(Res.string.runtime_setup_git))
                            Text(stringResource(Res.string.runtime_setup_uv))
                            if (needsHomebrew == true) Text(stringResource(Res.string.runtime_setup_homebrew))
                        }
                        Text(
                            stringResource(Res.string.runtime_setup_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(Res.string.runtime_setup_terminal), style = MaterialTheme.typography.titleSmall)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                        ) {
                            Box(Modifier.padding(12.dp), contentAlignment = Alignment.TopStart) {
                                Text(stringResource(Res.string.runtime_setup_waiting))
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { start(install = true) }, enabled = needsHomebrew != null) {
                                Text(stringResource(Res.string.runtime_setup_install))
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val heading = when (step) {
                            SetupStep.CONFIRM -> Res.string.runtime_setup_title
                            SetupStep.INSTALL -> when (stage) {
                                InstallStage.DOWNLOADING -> Res.string.runtime_setup_downloading
                                InstallStage.INSTALLING -> Res.string.runtime_setup_installing
                                InstallStage.PASSWORD, InstallStage.WINDOWS_APPROVAL -> Res.string.runtime_setup_permission
                                InstallStage.CONTINUE_HOMEBREW -> Res.string.runtime_setup_confirm_homebrew
                            }
                            SetupStep.CHECK -> Res.string.runtime_setup_checking
                            SetupStep.FAILED -> Res.string.runtime_setup_missing
                            SetupStep.READY -> Res.string.runtime_setup_ready
                        }
                        Text(stringResource(heading), style = MaterialTheme.typography.headlineMedium)

                        if (step == SetupStep.INSTALL) {
                            val instruction = when (stage) {
                                InstallStage.PASSWORD -> Res.string.runtime_setup_password
                                InstallStage.WINDOWS_APPROVAL -> Res.string.runtime_setup_windows
                                InstallStage.CONTINUE_HOMEBREW -> Res.string.runtime_setup_homebrew_return
                                else -> Res.string.runtime_setup_background
                            }
                            Text(stringResource(instruction))
                        } else if (step == SetupStep.READY) {
                            Text(stringResource(Res.string.runtime_setup_opening))
                        } else if (step == SetupStep.FAILED) {
                            Text(stringResource(Res.string.runtime_setup_retry_hint))
                            installExitCode?.takeIf { it != 0 }?.let { code ->
                                Text(
                                    stringResource(Res.string.runtime_setup_exit_code, code),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        val session = terminal
                        if (session != null) {
                            Text(stringResource(Res.string.runtime_setup_terminal), style = MaterialTheme.typography.titleSmall)
                            RuntimeInstallTerminal(
                                session = session,
                                isDark = isDark,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        } else {
                            if (step == SetupStep.INSTALL || step == SetupStep.CHECK) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (showDetails) {
                                    SelectionContainer(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        Text(errorDetails.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (step == SetupStep.FAILED) {
                                if (session == null && !errorDetails.isNullOrBlank()) {
                                    TextButton(onClick = { showDetails = !showDetails }) {
                                        Text(stringResource(
                                            if (showDetails) Res.string.runtime_setup_hide_details
                                            else Res.string.runtime_setup_show_details
                                        ))
                                    }
                                }
                                OutlinedButton(onClick = { start(install = false) }) {
                                    Text(stringResource(Res.string.runtime_setup_check))
                                }
                                Button(onClick = { start(install = true) }) {
                                    Text(stringResource(Res.string.runtime_setup_retry))
                                }
                            } else if (step != SetupStep.READY) {
                                OutlinedButton(onClick = onMinimize) {
                                    Text(stringResource(Res.string.runtime_setup_minimize))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
