package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 内置浏览器面板。
 *
 * 桌面端：JcefBrowserHost 注入到 [WorkspaceViewModel.uiBrowserHost]（tab 栏 + 导航栏 + KBPage 视图 + 右侧自动化操作流程视图）；
 * 遥控端 / wasm / JCEF 未就绪时 host 为 null，左侧显示"当前端不支持网页面板"兜底提示，右侧按需展示自动化操作流程视图。
 */
@Composable
internal fun BrowserPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    val host = viewModel.uiBrowserHost
    val automationState = viewModel.browserAutomationState

    if (host != null) {
        Box(modifier = modifier.fillMaxSize()) {
            host.BrowserContent(
                colors = colors,
                automationState = automationState,
                onToggleAutomationPanel = viewModel::toggleBrowserAutomationPanel,
                onCloseAutomationPanel = viewModel::closeBrowserAutomationPanel,
                onResizeAutomationPanel = viewModel::resizeBrowserAutomationPanel,
                onPreviewScreenshot = viewModel::openBrowserScreenshotPreview,
            )
        }
    } else {
        // 遥控端 / wasm / JCEF 未就绪：左侧兜底提示，右侧在自动化流程可见时并排展示流程视图
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(colors.surfaceWorkspace),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(colors.surfaceWorkspace),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.Globe,
                        contentDescription = stringResource(Res.string.rightdock_browser),
                        tint = colors.textMuted,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(Res.string.dock_browser_unsupported),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.dock_browser_desktop_only),
                        fontSize = 11.sp,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (automationState.isVisible) {
                BrowserAutomationFlowPanel(
                    state = automationState,
                    colors = colors,
                    onClose = viewModel::closeBrowserAutomationPanel,
                    onResize = viewModel::resizeBrowserAutomationPanel,
                    onPreviewScreenshot = { shot -> viewModel.openBrowserScreenshotPreview(shot) },
                )
            }
        }
    }

    // 点击流程内截图放大预览弹窗（Lightbox）
    automationState.previewScreenshot?.let { screenshot ->
        BrowserScreenshotLightbox(
            screenshot = screenshot,
            colors = colors,
            onDismiss = viewModel::closeBrowserScreenshotPreview,
        )
    }
}

/** 右侧面板标题唯一映射点（枚举不持有表现层文案，i18n 约定）。 */
@Composable
internal fun rightDockPanelTitle(panel: RightDockPanel): String = stringResource(
    when (panel) {
        RightDockPanel.OVERVIEW -> Res.string.rightdock_overview
        RightDockPanel.DIFF -> Res.string.rightdock_diff
        RightDockPanel.PLAN -> Res.string.rightdock_plan
        RightDockPanel.ARTIFACTS -> Res.string.rightdock_artifacts
        RightDockPanel.TERMINAL -> Res.string.rightdock_terminal
        RightDockPanel.BROWSER -> Res.string.rightdock_browser
    }
)