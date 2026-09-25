package xyz.mederi.ui

import org.jetbrains.compose.resources.StringResource

/**
 * ViewModel 层的用户可见消息（successMessage / errorMessage 等）。
 *
 * 硬性规则（AGENTS.md §5）：用户可见文案禁止硬编码在代码里，ViewModel 不能调 stringResource（非 Composable）。
 * 因此 VM 只产出 [资源 key + 格式化参数]，UI 层用 `stringResource(msg.key, *msg.args)` 渲染。
 * 不可翻译的运行时数据（供应商返回的 e.message 等）一律放 [args]，不做 key 化。
 */
data class UiMessage(
    val key: StringResource,
    val args: List<Any> = emptyList(),
)
