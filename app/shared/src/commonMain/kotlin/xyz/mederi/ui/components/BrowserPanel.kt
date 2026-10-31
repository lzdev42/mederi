package xyz.mederi.ui.components

/**
 * 网页宿主能力面板，与浏览器自动化任务分开。
 * BROWSER路由仍接线此组件；只有宿主注入时渲染原生网页，否则显示能力说明。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.components.atoms.WorkspacePanelEmptyState

/**
 * 内置浏览器面板。
 *
 * 桌面端：JcefBrowserHost 注入到 [WorkspaceViewModel.uiBrowserHost]（tab 栏 + 导航栏 + KBPage 视图）；
 * 遥控端 / wasm / JCEF 未就绪时 host 为 null，显示"当前端不支持网页面板"兜底提示。
 */
@Composable
internal fun BrowserPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    val host = viewModel.uiBrowserHost

    if (host != null) {
        val currentSessionId = viewModel.conversationId.orEmpty()
        Box(modifier = modifier.fillMaxSize()) {
            host.BrowserContent(
                sessionId = currentSessionId,
                colors = colors,
            )
        }
    } else {
        BrowserUnsupportedPlaceholder(colors, modifier)
    }
}

/** 真实能力缺失提示；不假装在遥控端创建本地网页宿主。 */
@Composable
internal fun BrowserUnsupportedPlaceholder(colors: MederiColors, modifier: Modifier = Modifier) {
    WorkspacePanelEmptyState(
        icon = FeatherIcons.Globe,
        title = stringResource(Res.string.dock_browser_unsupported),
        hint = stringResource(Res.string.dock_browser_desktop_only),
        modifier = modifier.background(colors.surfaceWorkspace), colors = colors,
    )
}

/** 右侧面板标题唯一映射点（枚举不持有表现层文案，i18n 约定）。 */
@Composable
internal fun rightDockPanelTitle(panel: RightDockPanel): String = stringResource(
    when (panel) {
        RightDockPanel.DIFF -> Res.string.rightdock_diff
        RightDockPanel.ARTIFACTS -> Res.string.rightdock_artifacts
        RightDockPanel.TERMINAL -> Res.string.rightdock_terminal
        RightDockPanel.BROWSER -> Res.string.rightdock_browser
        RightDockPanel.BROWSER_AUTOMATION -> Res.string.rightdock_browser_automation
    }
)