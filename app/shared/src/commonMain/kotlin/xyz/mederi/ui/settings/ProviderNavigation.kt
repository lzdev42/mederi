package xyz.mederi.ui.settings

/** 添加是页面流程，不用多个互相独立的弹窗布尔维护。凭据草稿只存在于对应View。 */
sealed interface ProviderPage {
    data object Manage : ProviderPage
    data object Detail : ProviderPage
    data object Catalog : ProviderPage
    data object Custom : ProviderPage
    data class Preset(val name: String) : ProviderPage
}

data class ProviderPresetItem(val name: String, val configuredIds: List<String>)

/** 只使用真实名称与内置来源，不把同名自定义连接当作已添加预设。 */
internal fun providerPresetItems(
    presets: List<String>, providers: List<ProviderItemUiState>, query: String,
): List<ProviderPresetItem> = presets.distinct()
    .filter { it.contains(query.trim(), ignoreCase = true) }
    .sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })
    .map { name -> ProviderPresetItem(name,
        providers.filter { it.isBuiltin && it.name.equals(name, ignoreCase = true) }.map { it.id }) }
