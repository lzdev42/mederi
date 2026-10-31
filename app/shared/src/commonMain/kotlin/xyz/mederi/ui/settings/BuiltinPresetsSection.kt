package xyz.mederi.ui.settings

import xyz.mederi.theme.LocalWorkspaceMotion

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.*
import xyz.mederi.ui.components.ProviderIcon
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspacePanelEmptyState

/** 同一搜索字段用于目录/已配置；查询与数据归VM，不在字段里持有副本。 */
@Composable
internal fun ProviderSearchField(query: String, label: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val colors = LocalMederiColors.current
    OutlinedTextField(query, onChange, enabled = enabled, singleLine = true,
        label = { Text(label, style = WorkspaceStyle.Caption) },
        textStyle = WorkspaceStyle.Body.copy(color = colors.textPrimary),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(WorkspaceStyle.ObjectRadius),
        leadingIcon = { Icon(FeatherIcons.Search, null, modifier = Modifier.size(WorkspaceStyle.IconSize)) },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accentText,
            unfocusedBorderColor = colors.surfaceCardBorder, focusedContainerColor = colors.surfaceInput,
            unfocusedContainerColor = colors.surfaceInput))
}

/** 管理与目录共用一行外观；可选中项用Tab语义，目录选择用Button，不复制宽窄实现。 */
@Composable
internal fun ProviderChoiceRow(
    name: String, subtitle: String?, detail: String?, selected: Boolean?, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, baseUrl: String? = null,
) {
    val colors = LocalMederiColors.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(when {
        selected == true -> colors.accentBg
        enabled && (hovered || focused) -> colors.surfaceHover
        else -> Color.Transparent
    }, LocalWorkspaceMotion.current.feedbackSpec(), label = "providerRowFeedback")
    Row(modifier.fillMaxWidth().heightIn(min = WorkspaceStyle.ControlHeight)
        .clip(RoundedCornerShape(MederiRadius.Control)).background(background).hoverable(source)
        .then(if (selected != null) Modifier.selectable(selected, enabled = enabled, role = Role.Tab,
            interactionSource = source, indication = null, onClick = onClick)
        else Modifier.clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick))
        .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderIcon(name, baseUrl = baseUrl, size = 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = if (selected == true) WorkspaceStyle.Title else WorkspaceStyle.Body,
                color = if (selected == true) colors.accentText else colors.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = WorkspaceStyle.Caption,
                color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (detail != null) Text(detail, style = WorkspaceStyle.Caption, color = colors.textSecondary,
            modifier = Modifier.widthIn(max = 120.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ProviderCatalogContent(
    items: List<ProviderPresetItem>, query: String, onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit, onCustom: () -> Unit, onBack: () -> Unit,
    listState: LazyListState, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceActionButton(stringResource(Res.string.provider_catalog_back), onBack, enabled = enabled, icon = FeatherIcons.ArrowLeft)
        ProviderSearchField(query, stringResource(Res.string.provider_catalog_search), onQueryChange, enabled)
        WorkspaceActionButton(stringResource(Res.string.provider_custom_entry), onCustom, enabled = enabled, icon = FeatherIcons.Plus)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.provider_catalog_title), style = WorkspaceStyle.Title, color = colors.textPrimary)
            Text(stringResource(Res.string.provider_catalog_count, items.size), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        }
        if (items.isEmpty()) WorkspacePanelEmptyState(FeatherIcons.Search, stringResource(Res.string.provider_search_empty),
            modifier = Modifier.weight(1f), action = {
                if (query.isNotEmpty()) WorkspaceActionButton(stringResource(Res.string.provider_search_clear), { onQueryChange("") })
            })
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.name }) { item ->
                ProviderChoiceRow(item.name, null,
                    stringResource(if (item.configuredIds.isEmpty()) Res.string.provider_catalog_select else Res.string.provider_catalog_manage),
                    null, { onSelect(item.name) }, enabled = enabled)
            }
        }
    }
}

/** Key只在对应凭据步骤的Composition里存在；失败不离开该步骤，不把secret存入偏好或目录状态。 */
@Composable
internal fun ProviderPresetCredentials(
    name: String, saving: Boolean, onSubmit: (String) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by remember(name) { mutableStateOf("") }
    val colors = LocalMederiColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkspaceActionButton(stringResource(Res.string.provider_preset_back), onBack, enabled = !saving, icon = FeatherIcons.ArrowLeft)
        Text(name, style = WorkspaceStyle.PageTitle, color = colors.textPrimary)
        Text(stringResource(Res.string.settings_panel_builtin_base_url_hint), style = WorkspaceStyle.Caption, color = colors.textSecondary)
        LabeledTextField(
            stringResource(Res.string.provider_api_key), apiKey, { apiKey = it },
            stringResource(Res.string.provider_api_key_placeholder), colors, isPassword = true, enabled = !saving)
        WorkspaceActionButton(stringResource(if (saving) Res.string.provider_adding else Res.string.settings_panel_add),
            { onSubmit(apiKey.trim()) }, enabled = !saving && apiKey.isNotBlank(), primary = true)
    }
}
