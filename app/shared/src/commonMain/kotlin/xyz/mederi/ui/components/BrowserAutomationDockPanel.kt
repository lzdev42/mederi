package xyz.mederi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 右抽屉浏览器自动化流程面板。
 *
 * 复用 [BrowserAutomationFloatingBar] 的 capsule+popover 内容，但作为 dock 面板渲染
 * （非浮动层）。无自动化任务时组件自身返回空（shouldDisplay=false 时不渲染）。
 */
@Composable
fun BrowserAutomationDockPanel(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
) {
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        BrowserAutomationFloatingBar(viewModel = viewModel)
    }
}