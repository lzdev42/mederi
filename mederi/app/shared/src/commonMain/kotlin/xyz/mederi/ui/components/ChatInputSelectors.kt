package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.X
import mederi.app.shared.generated.resources.Res
import xyz.mederi.core.contract.models.ModelOption
import xyz.mederi.core.contract.models.ProviderConfig
import mederi.app.shared.generated.resources.auto_approve_title
import mederi.app.shared.generated.resources.auto_approve_tooltip_main
import mederi.app.shared.generated.resources.auto_approve_tooltip_note
import mederi.app.shared.generated.resources.cap_image
import mederi.app.shared.generated.resources.cap_thinking
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.input_default_key
import mederi.app.shared.generated.resources.input_default_tag
import mederi.app.shared.generated.resources.input_model_mode_settings
import mederi.app.shared.generated.resources.input_no_project
import mederi.app.shared.generated.resources.input_open_project
import mederi.app.shared.generated.resources.input_provider_default
import mederi.app.shared.generated.resources.input_reasoning_level
import mederi.app.shared.generated.resources.input_select_model
import mederi.app.shared.generated.resources.input_select_project
import mederi.app.shared.generated.resources.input_selected
import mederi.app.shared.generated.resources.input_thinking_label
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.util.formatContextWindow

/**
 * 模型选择列表（按供应商分组）：供应商分组头 + 模型行（名称 + 能力 tag + 上下文窗口 + 选中勾）。
 *
 * 桌面 [ModelSelectorMenu]（DropdownMenu）与移动 [MobileModelBottomSheet]（Sheet）共用此组件，
 * 两处的外壳差异（DropdownMenuItem vs 普通 Row；分组是否带卡片容器；组间是否带分割线）
 * 通过 [itemWrapper] / [groupWrapper] 回调吸收，行内文字/颜色/勾尺寸微调通过显式样式参数吸收，
 * 保证两处渲染与原各自实现完全等价。
 *
 * - [itemWrapper]：包裹单个模型行——桌面用 DropdownMenuItem（自带菜单语义/点击），移动用普通 Row（自带 clickable/背景/内边距）。
 *   接收当前 model、是否选中、点击回调与行内容；行内容（[ModelPickerRowContent]）由本组件统一渲染。
 * - [groupWrapper]：包裹单个供应商分组——桌面在分组间插分割线（groupIndex>0），移动把整组包进卡片容器。
 */
@Composable
internal fun ModelPickerList(
    models: List<ModelOption>,
    providers: List<ProviderConfig>,
    selectedModelId: String?,
    nameFontSize: TextUnit,
    unselectedContextColor: Color,
    checkSize: Dp,
    onSelect: (ModelOption) -> Unit,
    colors: xyz.mederi.theme.MederiColors,
    modifier: Modifier = Modifier,
    itemWrapper: @Composable (model: ModelOption, isSelected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) -> Unit,
    groupWrapper: @Composable (groupIndex: Int, content: @Composable () -> Unit) -> Unit,
) {
    val providerNameMap = remember(providers) { providers.associate { it.id to it.name } }
    val groupedModels = remember(models) { models.groupBy { it.provider } }

    Column(modifier = modifier) {
        groupedModels.entries.forEachIndexed { groupIndex, (providerId, providerModels) ->
            val providerDisplayName = (providerNameMap[providerId] ?: providerId).uppercase()
            groupWrapper(groupIndex) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ModelPickerGroupHeader(
                        providerDisplayName = providerDisplayName,
                        count = providerModels.size,
                        colors = colors,
                    )
                    providerModels.forEach { model ->
                        val isSelected = selectedModelId == model.id
                        itemWrapper(model, isSelected, { onSelect(model) }) {
                            ModelPickerRowContent(
                                model = model,
                                isSelected = isSelected,
                                nameFontSize = nameFontSize,
                                unselectedContextColor = unselectedContextColor,
                                checkSize = checkSize,
                                colors = colors,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 供应商分组头：Cpu 图标 + 供应商名（大写）+ 模型数。 */
@Composable
private fun ModelPickerGroupHeader(
    providerDisplayName: String,
    count: Int,
    colors: xyz.mederi.theme.MederiColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(FeatherIcons.Cpu, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
            Text(
                text = providerDisplayName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMuted,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "$count",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textMuted.copy(alpha = 0.6f)
        )
    }
}

/** 模型行内容：名称 + 能力 tag + 上下文窗口 + 选中勾/高亮。外壳（DropdownMenuItem / Row）由调用方提供。 */
@Composable
private fun ModelPickerRowContent(
    model: ModelOption,
    isSelected: Boolean,
    nameFontSize: TextUnit,
    unselectedContextColor: Color,
    checkSize: Dp,
    colors: xyz.mederi.theme.MederiColors,
) {
    val contextSizeStr = formatContextWindow(model.contextWindow)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = model.name,
                fontSize = nameFontSize,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (model.supportsThinking) ModelCapabilityTag(stringResource(Res.string.cap_thinking), colors.thoughtAccent)
            if (model.supportsImages) ModelCapabilityTag(stringResource(Res.string.cap_image), colors.accentSecondary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (contextSizeStr != null) {
                Text(
                    text = contextSizeStr,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) colors.textPrimary else unselectedContextColor
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = stringResource(Res.string.input_selected),
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(checkSize)
                )
            } else {
                Spacer(modifier = Modifier.size(checkSize))
            }
        }
    }
}

/** 模型选择器（按供应商分组的下拉菜单）。业务数据来自 AppState，选择写入 ViewModel。 */
@Composable
internal fun ModelSelectorMenu(viewModel: WorkspaceViewModel, compact: Boolean) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val models by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    if (models.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            label = selectedModel?.name ?: stringResource(Res.string.input_select_model),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(min = if (compact) 400.dp else 500.dp, max = if (compact) 480.dp else 560.dp)
                .heightIn(max = 480.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
        ) {
            ModelPickerList(
                models = models,
                providers = providers,
                selectedModelId = selectedModel?.id,
                nameFontSize = if (compact) 12.sp else 12.5.sp,
                unselectedContextColor = colors.textSecondary,
                checkSize = 14.dp,
                onSelect = {
                    viewModel.selectModel(it)
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
                        HorizontalDivider(color = colors.divider.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    content()
                },
            )
        }
    }
}

/**
 * API Key 选择器（模型选择器左侧）。**派生自选中模型所在供应商**：读该 ProviderConfig 的 apiKeys，
 * 当前选中项 = AppState.selectedApiKeyIds[provider.id]（缺省 = 用默认 key）。
 * 选择写入 AppState（按供应商记忆，跨重启恢复）。
 */
@Composable
internal fun ApiKeySelectorMenu(viewModel: WorkspaceViewModel) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    val selectedApiKeys by appState.selectedApiKeyIds.collectAsState()

    // 关联：选中模型 → 其供应商 → 该供应商的 apiKeys。无供应商或无 key 时不显示选择器
    val provider = selectedModel?.let { m -> providers.find { it.id == m.provider } }
    val keys = provider?.apiKeys.orEmpty()
    if (provider == null || keys.isEmpty()) return

    val providerId = provider.id
    val selectedId = selectedApiKeys[providerId]
    val selectedKey = keys.find { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Key,
            label = selectedKey?.name ?: stringResource(Res.string.input_default_key),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            // 供应商默认 key（未选定）
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.input_provider_default),
                            fontSize = 12.sp,
                            fontWeight = if (selectedId == null) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selectedId == null) colors.accentPrimary else colors.textPrimary
                        )
                        if (selectedId == null) {
                            Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp))
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                onClick = {
                    viewModel.selectApiKey(providerId, null)
                    expanded = false
                }
            )
            if (keys.isNotEmpty()) HorizontalDivider(color = colors.divider.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            keys.forEach { key ->
                val isSelected = selectedId == key.id
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f, fill = false)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = key.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (key.isDefault) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(colors.accentSecondary.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(stringResource(Res.string.input_default_tag), fontSize = 9.sp, color = colors.accentSecondary, fontWeight = FontWeight.Medium, maxLines = 1)
                                        }
                                    }
                                }
                                Text(
                                    text = key.maskedValue,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp))
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        viewModel.selectApiKey(providerId, key.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 思考/推理等级选择器。选项由 ReasoningMenu 推导（NONE=关闭 恒为首项，其余为有值档位 ∩ 模型勾选）。 */
@Composable
internal fun ThinkingLevelMenu(viewModel: WorkspaceViewModel) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val selectedModel by appState.selectedModel.collectAsState()
    val levels = selectedModel?.reasoningLevels ?: emptyList()
    if (levels.isEmpty()) return

    // 唯一真理源：订阅 effectiveThinkingLevel（ReasoningMenu.resolve 推导的派生流）。
    // 模型切换/档位记忆更新自动重算；null（推导链未就绪，正常不会发生）时不渲染，不做兜底回退
    val currentLevel = viewModel.effectiveThinkingLevel.collectAsState().value ?: return
    if (currentLevel.isBlank()) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Sliders,
            label = stringResource(Res.string.input_thinking_label, formatReasoningLevelLabel(currentLevel)),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 150.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            // NONE（关闭）为 levels 首项（ReasoningMenu 推导），统一渲染
            levels.forEach { lvl ->
                val isSelected = currentLevel.equals(lvl, ignoreCase = true)
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
                        viewModel.updateThinkingLevel(lvl)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 项目选择器（下拉菜单，含清空选择与打开新项目目录入口）。选择写入 AppState。 */
@Composable
internal fun ProjectSelectorMenu(
    onOpenProjectPicker: () -> Unit,
    onSelect: (String?) -> Unit,
    iconOnly: Boolean = false,
    highlight: Boolean = false,
    openRequest: Int = 0
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val projects by appState.projects.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val selectedProject = projects.find { it.id == selectedProjectId }

    var expanded by remember { mutableStateOf(false) }
    // 引导条点击：外部请求打开菜单（openRequest 递增即打开一次）
    LaunchedEffect(openRequest) {
        if (openRequest > 0) expanded = true
    }
    Box {
        if (iconOnly) {
            IconToolButton(
                icon = FeatherIcons.Folder,
                onClick = { expanded = true },
                size = 40
            )
        } else {
            ContextToolChip(
                icon = FeatherIcons.Folder,
                label = selectedProject?.name ?: stringResource(Res.string.input_select_project),
                onClick = { expanded = true },
                highlight = highlight
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceSidebar,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
        ) {
            if (selectedProjectId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.input_no_project), fontSize = 11.5.sp, color = colors.textSecondary) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                if (projects.isNotEmpty()) HorizontalDivider(color = colors.divider)
            }
            projects.forEach { proj ->
                DropdownMenuItem(
                    text = { Text(proj.name, fontSize = 11.5.sp, color = colors.textPrimary) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(proj.id)
                        expanded = false
                    }
                )
            }
            if (projects.isNotEmpty()) HorizontalDivider(color = colors.divider)
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(FeatherIcons.Plus, null, tint = colors.accentPrimary, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.input_open_project), fontSize = 11.5.sp, color = colors.accentPrimary, fontWeight = FontWeight.Medium)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                onClick = {
                    expanded = false
                    onOpenProjectPicker()
                }
            )
        }
    }
}


/** 移动端底部抽屉（Modal Bottom Sheet）：选择模型、推理等级、执行策略 */
@Composable
internal fun MobileModelBottomSheet(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val models by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    val currentLevel by viewModel.effectiveThinkingLevel.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceOverlay)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 拦截点击，防止点击面板内部关闭抽屉 */ }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 顶部拖拽条
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.input_model_mode_settings),
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.close),
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 自动审批开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.auto_approve_title),
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            HelpCircleTooltip(
                                mainText = stringResource(Res.string.auto_approve_tooltip_main),
                                noteText = stringResource(Res.string.auto_approve_tooltip_note),
                                touchTargetSize = 24.dp,
                                iconSize = 14.dp
                            )
                        }
                        AutoApproveSwitch(
                            viewModel = viewModel,
                            scale = 0.8f,
                            switchWidth = 36.dp,
                            switchHeight = 22.dp
                        )
                    }

                    // 2. 推理等级选择（条件显示：仅当前模型支持推理且生效档位已推导出时展示）
                    val sheetModel = selectedModel
                    val sheetCurrentLevel = currentLevel
                    if (sheetModel?.supportsThinking == true && sheetModel.reasoningLevels.isNotEmpty() && sheetCurrentLevel != null) {
                        val levels = sheetModel.reasoningLevels
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Sliders,
                                    contentDescription = null,
                                    tint = colors.thoughtAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = stringResource(Res.string.input_reasoning_level),
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // NONE（关闭）为 levels 首项（ReasoningMenu 推导），统一渲染
                                levels.forEach { lvl ->
                                val isSelected = sheetCurrentLevel.equals(lvl, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceInput)
                                            .border(
                                                1.dp,
                                                if (isSelected) colors.accentPrimary else colors.surfaceCardBorder,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { viewModel.updateThinkingLevel(lvl) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = formatReasoningLevelLabel(lvl),
                                            color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. 模型列表
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(Res.string.input_select_model),
                            color = colors.textSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )

                        ModelPickerList(
                            models = models,
                            providers = providers,
                            selectedModelId = selectedModel?.id,
                            nameFontSize = 13.sp,
                            unselectedContextColor = colors.textMuted,
                            checkSize = 15.dp,
                            onSelect = {
                                viewModel.selectModel(it)
                                onDismiss()
                            },
                            colors = colors,
                            modifier = Modifier.fillMaxWidth(),
                            itemWrapper = { _, isSelected, onClick, content ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent)
                                        .clickable(onClick = onClick)
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) { content() }
                            },
                            groupWrapper = { _, content ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceInput)
                                        .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                        .padding(vertical = 4.dp)
                                ) { content() }
                            },
                        )
                    }
                }
            }
        }
    }
}
