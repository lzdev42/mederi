package xyz.mederi.ui.settings

import xyz.mederi.ui.components.atoms.WorkspaceLoadingIndicator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.*
import xyz.mederi.theme.*
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.ErrorBoard
import xyz.mederi.ui.components.ModelPickerList
import xyz.mederi.ui.components.formatReasoningLevelLabel
import xyz.mederi.ui.components.atoms.*

/** Route 仅绑定权威流和配置 VM；不会在重组中读写 Core。 */
@Composable
fun AgentSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val vm: AgentSettingsViewModel = viewModel { AgentSettingsViewModel(appState.aiCore) }
    LaunchedEffect(vm) { vm.reloadConfigs(); vm.reloadGlobal() }
    val models by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val parentModel by appState.selectedModel.collectAsState()
    AgentSettingsContent(vm.uiState, models, providers, parentModel,
        AgentSettingsActions(vm::reloadConfigs, vm::reloadGlobal, vm::setConcurrency,
            vm::selectModel, vm::selectReasoning, vm::selectKey), isCompact = isCompact)
}

internal data class AgentSettingsActions(
    val reloadConfigs: () -> Unit,
    val reloadGlobal: () -> Unit,
    val concurrency: (Int) -> Unit,
    val model: (String, ModelOption?) -> Unit,
    val reasoning: (String, String?) -> Unit,
    val key: (String, String?) -> Unit,
)

@Composable
internal fun AgentSettingsContent(
    state: AgentSettingsUiState,
    models: List<ModelOption>,
    providers: List<ProviderConfig>,
    parentModel: ModelOption?,
    actions: AgentSettingsActions,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
) {
    val colors = LocalMederiColors.current
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "concurrency") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsRow(stringResource(Res.string.settings_agents_concurrency_title),
                    stringResource(Res.string.settings_agents_concurrency_desc), icon = FeatherIcons.Users) {
                    if (state.loadingGlobal) AgentSettingsProgress()
                    else state.global?.let {
                        NumberStepper(it.maxConcurrentAgents, actions.concurrency, minValue = 1, maxValue = 16,
                            enabled = state.concurrencyEnabled)
                    }
                }
                state.globalError?.let { message ->
                    ErrorBoard(stringResource(message.key, *message.args.toTypedArray()), showContinue = true,
                        onContinue = actions.reloadGlobal, continueLabel = stringResource(Res.string.settings_agents_retry))
                }
            }
        }
        item(key = "roles-status") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_agents_roles), style = WorkspaceStyle.Title, color = colors.textPrimary)
                if (state.loadingConfigs) AgentSettingsProgress()
                state.configsError?.let { message ->
                    ErrorBoard(stringResource(message.key, *message.args.toTypedArray()), showContinue = true,
                        onContinue = actions.reloadConfigs, continueLabel = stringResource(Res.string.settings_agents_retry))
                }
                if (state.configsReady && state.configs.isEmpty())
                    Text(stringResource(Res.string.settings_agents_empty), style = WorkspaceStyle.Body, color = colors.textMuted)
            }
        }
        items(state.configs, key = { "role:${it.role}" }) { item ->
            AgentRoleSettings(item, models, providers, parentModel, state.rolesEnabled,
                state.savingRole == item.role, actions, isCompact)
        }
    }
}

@Composable
private fun AgentSettingsProgress() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkspaceLoadingIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalMederiColors.current.accentPrimary)
        Text(stringResource(Res.string.settings_agents_loading), style = WorkspaceStyle.Caption,
            color = LocalMederiColors.current.textMuted)
    }
}

/** 每个角色复用相同设置行和菜单；展开状态只属于当前角色。 */
@Composable
internal fun AgentRoleSettings(
    item: SubagentConfigItem,
    models: List<ModelOption>,
    providers: List<ProviderConfig>,
    parentModel: ModelOption?,
    enabled: Boolean,
    saving: Boolean,
    actions: AgentSettingsActions,
    isCompact: Boolean = false,
) {
    val colors = LocalMederiColors.current
    val selected = models.find { it.id == item.modelId }
    // 与 Core 的模型删除回落一致；仍展示配置 ID，不伪装为继承。
    val effectiveModel = selected ?: parentModel
    val keys = providers.find { it.id == effectiveModel?.provider }?.apiKeys.orEmpty().filter { it.id != API_KEY_ROTATION_ID }
    Column(Modifier.fillMaxWidth().testTag("agent-role:${item.role}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(when (item.role) {
                "RESEARCHER" -> FeatherIcons.Search
                "BROWSER_OPERATOR", "BROWSER_BRAIN" -> FeatherIcons.Globe
                else -> FeatherIcons.Cpu
            }, null, Modifier.size(WorkspaceStyle.IconSize), tint = colors.textMuted)
            Text(item.displayName, style = WorkspaceStyle.Title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (saving) Text(stringResource(Res.string.settings_agents_saving), style = WorkspaceStyle.Caption, color = colors.textMuted)
        }
        if (item.description.isNotBlank()) Text(item.description, style = WorkspaceStyle.Caption, color = colors.textMuted)
        SettingsRow(stringResource(Res.string.settings_agents_model_label),
            subtitle = if (item.modelId == null) stringResource(Res.string.settings_agents_effective_desc,
                parentModel?.name ?: stringResource(Res.string.settings_panel_not_set)) else null) {
            AgentModelMenu(item.modelId, selected?.name ?: item.modelName, models, providers, enabled, isCompact) {
                actions.model(item.role, it)
            }
        }
        if (!item.isInheriting) {
            if (effectiveModel?.supportsThinking == true && effectiveModel.reasoningLevels.isNotEmpty()) {
                SettingsRow(stringResource(Res.string.settings_agents_reasoning_label)) {
                    AgentChoiceMenu(item.reasoningLevel?.let { formatReasoningLevelLabel(it) }
                        ?: stringResource(Res.string.settings_agents_inherit_reasoning_short),
                        item.reasoningLevel, listOf(null to stringResource(Res.string.settings_agents_inherit_reasoning)) +
                            effectiveModel.reasoningLevels.map { it to formatReasoningLevelLabel(it) }, enabled) {
                        actions.reasoning(item.role, it)
                    }
                }
            }
            if (keys.isNotEmpty() || item.apiKeyId != null) {
                val label = when (item.apiKeyId) {
                    null -> stringResource(Res.string.settings_agents_apikey_inherit)
                    API_KEY_ROTATION_ID -> stringResource(Res.string.settings_agents_apikey_rotate)
                    else -> keys.find { it.id == item.apiKeyId }?.name ?: item.apiKeyId
                }
                SettingsRow(stringResource(Res.string.settings_agents_apikey_label)) {
                    AgentChoiceMenu(label, item.apiKeyId,
                        listOf(null to stringResource(Res.string.settings_agents_apikey_inherit)) +
                            (if (keys.size > 1) listOf(API_KEY_ROTATION_ID to stringResource(Res.string.settings_agents_apikey_rotate)) else emptyList()) +
                            keys.map { it.id to it.name }, enabled, details = keys.associate { it.id to if (it.isDefault) "${it.maskedValue} · ${stringResource(Res.string.input_default_tag)}" else it.maskedValue }) {
                        actions.key(item.role, it)
                    }
                }
            }
            WorkspaceActionButton(stringResource(Res.string.settings_agents_reset), { actions.model(item.role, null) },
                enabled = enabled, icon = FeatherIcons.CornerDownRight)
        }
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AgentModelMenu(
    selectedId: String?, selectedName: String?, models: List<ModelOption>, providers: List<ProviderConfig>,
    enabled: Boolean, compact: Boolean, onSelect: (ModelOption?) -> Unit,
) {
    val colors = LocalMederiColors.current
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        WorkspaceActionButton(if (selectedId == null) stringResource(Res.string.settings_agents_inherit) else selectedName ?: selectedId,
            { expanded = true }, modifier = Modifier.widthIn(max = 240.dp), enabled = enabled, icon = FeatherIcons.ChevronDown)
        WorkspaceDropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = if (compact) 320.dp else 460.dp).heightIn(max = 440.dp)) {
            WorkspaceMenuItem({ Text(stringResource(Res.string.settings_agents_inherit), style = WorkspaceStyle.Body) },
                { expanded = false; onSelect(null) }, selected = selectedId == null)
            ModelPickerList(models, providers, selectedId, WorkspaceStyle.Body.fontSize,
                colors.textSecondary, WorkspaceStyle.IconSize, { expanded = false; onSelect(it) }, colors,
                itemWrapper = { _, selected, click, content -> WorkspaceMenuItem(content, click, selected = selected, twoLines = true) },
                groupWrapper = { _, content -> content() })
        }
    }
}

/** Key 与推理使用同一菜单行；选择语义和值来自调用方，不推导业务默认值。 */
@Composable
private fun AgentChoiceMenu(
    label: String, selected: String?, options: List<Pair<String?, String>>, enabled: Boolean,
    details: Map<String, String> = emptyMap(), onSelect: (String?) -> Unit,
) {
    val colors = LocalMederiColors.current
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        WorkspaceActionButton(label, { expanded = true }, modifier = Modifier.widthIn(max = 240.dp), enabled = enabled, icon = FeatherIcons.ChevronDown)
        WorkspaceDropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = 320.dp).heightIn(max = 360.dp)) {
            options.forEach { (id, text) ->
                WorkspaceMenuItem({ Column {
                    Text(text, style = WorkspaceStyle.Body, color = colors.textPrimary)
                    details[id]?.let { Text(it, style = WorkspaceStyle.Caption, color = colors.textMuted) }
                } }, { expanded = false; onSelect(id) }, selected = id == selected, twoLines = details.containsKey(id))
            }
        }
    }
}
