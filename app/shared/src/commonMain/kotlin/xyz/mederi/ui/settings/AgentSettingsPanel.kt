package xyz.mederi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ProviderConfig
import xyz.mederi.core.contract.models.SubagentConfigItem
import xyz.mederi.core.contract.models.SubagentGlobalSettings
import xyz.mederi.core.contract.models.UpdateSubagentConfigInput
import xyz.mederi.core.contract.models.UpdateSubagentGlobalSettingsInput
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.ui.components.ChipSelectorPill
import xyz.mederi.ui.components.ModelPickerList
import xyz.mederi.ui.components.formatReasoningLevelLabel

/**
 * Agent 代理角色模型与并发设置面板。
 */
@Composable
fun AgentSettingsPanel(isCompact: Boolean = false) {
    val appState = LocalAppState.current
    val colors = LocalMederiColors.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var configs by remember { mutableStateOf<List<SubagentConfigItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val availableModels by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val parentModel by appState.selectedModel.collectAsState()

    val refreshConfigs: () -> Unit = {
        scope.launch {
            val list = appState.aiCore.listSubagentConfigs().getOrDefault(emptyList())
            DebugLog.info("AgentSettingsPanel", "Subagent configs fetched: count=${list.size}, configs=$list")
            configs = list
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshConfigs()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ─── 并发子代理上限（全局设置） ───
        var globalSettings by remember { mutableStateOf<SubagentGlobalSettings?>(null) }
        LaunchedEffect(Unit) {
            globalSettings = appState.aiCore.getSubagentGlobalSettings().getOrNull()
        }

        SettingsCard(colors = colors) {
            val current = globalSettings?.maxConcurrentAgents ?: 2
            SettingsRow(
                title = stringResource(Res.string.settings_agents_concurrency_title),
                subtitle = stringResource(Res.string.settings_agents_concurrency_desc),
                icon = FeatherIcons.Users,
                iconTint = colors.accentPrimary,
                colors = colors
            ) {
                NumberStepper(
                    value = current,
                    onValueChange = { newLimit ->
                        scope.launch {
                            appState.aiCore.updateSubagentGlobalSettings(
                                UpdateSubagentGlobalSettingsInput(maxConcurrentAgents = newLimit)
                            )
                            globalSettings = appState.aiCore.getSubagentGlobalSettings().getOrNull()
                        }
                    },
                    minValue = 1,
                    maxValue = 16,
                    colors = colors
                )
            }
        }

        // ─── 角色模型配置列表 ───
        if (isLoading && configs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accentPrimary, strokeWidth = 2.dp)
            }
        } else {
            configs.forEach { item ->
                AgentConfigCard(
                    item = item,
                    availableModels = availableModels,
                    providers = providers,
                    parentModel = parentModel,
                    isCompact = isCompact,
                    colors = colors,
                    onUpdate = { modelId, reasoningLevel ->
                        DebugLog.info("AgentSettingsPanel", "Update config for role=${item.role}: modelId=$modelId, reasoningLevel=$reasoningLevel")
                        scope.launch {
                            appState.aiCore.updateSubagentConfig(
                                item.role,
                                UpdateSubagentConfigInput(modelId = modelId, reasoningLevel = reasoningLevel)
                            )
                            refreshConfigs()
                        }
                    }
                )
            }
        }
    }
}

/**
 * 单个 Agent 角色的现代化配置卡片。
 * 遵循不兜底原则：不自动偷塞默认模型写库，用户显式通过选择器指派或恢复继承。
 */
@Composable
private fun AgentConfigCard(
    item: SubagentConfigItem,
    availableModels: List<ModelOption>,
    providers: List<ProviderConfig>,
    parentModel: ModelOption?,
    isCompact: Boolean,
    colors: MederiColors,
    onUpdate: (modelId: String?, reasoningLevel: String?) -> Unit
) {
    val currentSelectedModel = remember(item.modelId, availableModels) {
        item.modelId?.let { id -> availableModels.find { it.id == id } }
    }
    val effectiveModel = currentSelectedModel ?: parentModel
    val reasoningLevels = effectiveModel?.reasoningLevels ?: emptyList()
    val supportsReasoning = effectiveModel?.supportsThinking == true && reasoningLevels.isNotEmpty()

    val (roleIcon, roleTint) = when (item.role) {
        "RESEARCHER" -> FeatherIcons.Search to colors.accentSecondary
        "BROWSER_OPERATOR", "BROWSER_BRAIN" -> FeatherIcons.Globe to colors.accentSuccess
        else -> FeatherIcons.Cpu to colors.accentPrimary
    }

    SettingsCard(colors = colors) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1. 卡片头部：角色图标托盘 + 角色名与描述 + 状态徽章与操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                ) {
                    SettingsIconBadge(
                        icon = roleIcon,
                        tint = roleTint,
                        size = 36.dp,
                        iconSize = 18.dp,
                        cornerRadius = 10.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.displayName,
                            color = colors.textPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (item.description.isNotBlank()) {
                            Text(
                                text = item.description,
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // 右侧状态与操作
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (item.isInheriting) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceWorkspace)
                                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_agents_status_inheriting),
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // 已指定独立模型徽章
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.accentPrimary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_agents_status_custom),
                                color = colors.accentPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 恢复继承按钮
                        Text(
                            text = stringResource(Res.string.settings_agents_inherit),
                            color = colors.accentPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onUpdate(null, null) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))

            // 2. 卡片配置内容区
            if (item.isInheriting) {
                // 跟随主会话态：清晰展示继承事实
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceWorkspace)
                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.CornerDownRight,
                            contentDescription = null,
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(
                                Res.string.settings_agents_effective_desc,
                                effectiveModel?.name ?: effectiveModel?.id ?: stringResource(Res.string.settings_panel_not_set)
                            ),
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }

                    // 允许用户直接从菜单选择独立模型
                    SubagentModelMenu(
                        selectedModelId = null,
                        selectedModelName = null,
                        models = availableModels,
                        providers = providers,
                        compact = isCompact,
                        colors = colors,
                        buttonLabel = stringResource(Res.string.settings_agents_custom_action),
                        onSelect = { selectedModel ->
                            if (selectedModel != null) {
                                onUpdate(selectedModel.id, null)
                            }
                        }
                    )
                }
            } else {
                // 自定义模型态：展示整洁对齐的模型选择器与思考档位选择器
                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_agents_model_label),
                                color = colors.textMuted,
                                fontSize = 12.sp
                            )
                            SubagentModelMenu(
                                selectedModelId = item.modelId,
                                selectedModelName = currentSelectedModel?.name ?: item.modelName,
                                models = availableModels,
                                providers = providers,
                                compact = true,
                                colors = colors,
                                onSelect = { selectedModel ->
                                    if (selectedModel == null) {
                                        onUpdate(null, null)
                                    } else {
                                        val newLevels = selectedModel.reasoningLevels
                                        val validReasoning = if (selectedModel.supportsThinking && item.reasoningLevel != null && newLevels.contains(item.reasoningLevel)) {
                                            item.reasoningLevel
                                        } else null
                                        onUpdate(selectedModel.id, validReasoning)
                                    }
                                }
                            )
                        }

                        if (supportsReasoning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_agents_reasoning_label),
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                                SubagentReasoningMenu(
                                    currentReasoningLevel = item.reasoningLevel,
                                    levels = reasoningLevels,
                                    colors = colors,
                                    onSelect = { lvl -> onUpdate(item.modelId, lvl) }
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // 模型选择项
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_agents_model_label),
                                color = colors.textMuted,
                                fontSize = 12.sp
                            )
                            SubagentModelMenu(
                                selectedModelId = item.modelId,
                                selectedModelName = currentSelectedModel?.name ?: item.modelName,
                                models = availableModels,
                                providers = providers,
                                compact = false,
                                colors = colors,
                                onSelect = { selectedModel ->
                                    if (selectedModel == null) {
                                        onUpdate(null, null)
                                    } else {
                                        val newLevels = selectedModel.reasoningLevels
                                        val validReasoning = if (selectedModel.supportsThinking && item.reasoningLevel != null && newLevels.contains(item.reasoningLevel)) {
                                            item.reasoningLevel
                                        } else null
                                        onUpdate(selectedModel.id, validReasoning)
                                    }
                                }
                            )
                        }

                        // 推理思考档位选择项（仅当生效模型支持推理时展示）
                        if (supportsReasoning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_agents_reasoning_label),
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                                SubagentReasoningMenu(
                                    currentReasoningLevel = item.reasoningLevel,
                                    levels = reasoningLevels,
                                    colors = colors,
                                    onSelect = { lvl -> onUpdate(item.modelId, lvl) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 子代理模型选择器菜单。
 */
@Composable
private fun SubagentModelMenu(
    selectedModelId: String?,
    selectedModelName: String?,
    models: List<ModelOption>,
    providers: List<ProviderConfig>,
    compact: Boolean,
    colors: MederiColors,
    buttonLabel: String? = null,
    onSelect: (ModelOption?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val pillLabel = buttonLabel ?: if (selectedModelId == null) {
        stringResource(Res.string.settings_agents_inherit)
    } else {
        selectedModelName ?: selectedModelId
    }

    Box {
        ChipSelectorPill(
            label = pillLabel,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(min = if (compact) 360.dp else 460.dp, max = if (compact) 440.dp else 520.dp)
                .heightIn(max = 440.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
        ) {
            // 首项：继承主会话模型
            val isInheritSelected = selectedModelId == null
            DropdownMenuItem(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isInheritSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                FeatherIcons.CornerDownRight,
                                null,
                                tint = if (isInheritSelected) colors.accentPrimary else colors.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(Res.string.settings_agents_inherit),
                                fontSize = if (compact) 12.sp else 12.5.sp,
                                fontWeight = if (isInheritSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isInheritSelected) colors.accentPrimary else colors.textPrimary
                            )
                        }
                        if (isInheritSelected) {
                            Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )

            HorizontalDivider(
                color = colors.divider.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // 供应商分组模型列表
            ModelPickerList(
                models = models,
                providers = providers,
                selectedModelId = selectedModelId,
                nameFontSize = if (compact) 12.sp else 12.5.sp,
                unselectedContextColor = colors.textSecondary,
                checkSize = 14.dp,
                onSelect = { model ->
                    onSelect(model)
                    expanded = false
                },
                colors = colors,
                itemWrapper = { _, isSelected, onClick, content ->
                    DropdownMenuItem(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                        text = { content() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        onClick = onClick,
                    )
                },
                groupWrapper = { groupIndex, content ->
                    if (groupIndex > 0) {
                        HorizontalDivider(
                            color = colors.divider.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    content()
                }
            )
        }
    }
}

/**
 * 子代理推理等级菜单。
 */
@Composable
private fun SubagentReasoningMenu(
    currentReasoningLevel: String?,
    levels: List<String>,
    colors: MederiColors,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val pillLabel = if (currentReasoningLevel == null) {
        stringResource(Res.string.input_thinking_label, stringResource(Res.string.settings_agents_inherit_reasoning_short))
    } else {
        stringResource(Res.string.input_thinking_label, formatReasoningLevelLabel(currentReasoningLevel))
    }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Sliders,
            label = pillLabel,
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 160.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            val isInheritSelected = currentReasoningLevel == null
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_agents_inherit_reasoning),
                            fontSize = 12.sp,
                            fontWeight = if (isInheritSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isInheritSelected) colors.accentPrimary else colors.textPrimary
                        )
                        if (isInheritSelected) {
                            Icon(
                                FeatherIcons.Check,
                                null,
                                tint = colors.accentPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )

            if (levels.isNotEmpty()) {
                HorizontalDivider(
                    color = colors.divider.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            levels.forEach { lvl ->
                val isSelected = currentReasoningLevel.equals(lvl, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatReasoningLevelLabel(lvl),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.accentPrimary else if (lvl == "NONE") colors.textSecondary else colors.textPrimary
                            )
                            if (isSelected) {
                                Icon(
                                    FeatherIcons.Check,
                                    null,
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(lvl)
                        expanded = false
                    }
                )
            }
        }
    }
}
