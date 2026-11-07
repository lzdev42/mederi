package xyz.mederi.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ManualPlanAcceptanceInput

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanManualAcceptanceDialog(
    caseIds: List<String>,
    onDismiss: () -> Unit,
    onSubmit: suspend (ManualPlanAcceptanceInput) -> Result<Unit>
) {
    var caseId by remember(caseIds) { mutableStateOf(caseIds.firstOrNull().orEmpty()) }
    var observed by remember(caseId) { mutableStateOf("") }
    var evidence by remember(caseId) { mutableStateOf("") }
    var reviewer by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(Res.string.plan_manual_acceptance)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.plan_manual_case))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    caseIds.forEach { id ->
                        FilterChip(selected = caseId == id, onClick = { caseId = id }, label = { Text(id) }, enabled = !submitting)
                    }
                }
                OutlinedTextField(observed, { observed = it }, label = { Text(stringResource(Res.string.plan_manual_observed)) },
                    enabled = !submitting)
                OutlinedTextField(evidence, { evidence = it }, label = { Text(stringResource(Res.string.plan_manual_evidence)) },
                    enabled = !submitting)
                OutlinedTextField(reviewer, { reviewer = it }, label = { Text(stringResource(Res.string.plan_manual_reviewer)) },
                    enabled = !submitting)
                error?.let { Text(stringResource(Res.string.plan_manual_error, it)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && listOf(caseId, observed, evidence, reviewer).all { it.isNotBlank() },
                onClick = {
                    submitting = true
                    error = null
                    scope.launch {
                        val result = onSubmit(ManualPlanAcceptanceInput(caseId, observed, evidence, reviewer.trim()))
                        submitting = false
                        result.fold({ onDismiss() }, { error = it.message.orEmpty() })
                    }
                }
            ) { Text(stringResource(Res.string.plan_manual_submit)) }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}
