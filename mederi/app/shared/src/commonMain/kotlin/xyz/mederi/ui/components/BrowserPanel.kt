package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_browser_desktop_only
import mederi.app.shared.generated.resources.dock_browser_unsupported
import mederi.app.shared.generated.resources.rightdock_artifacts
import mederi.app.shared.generated.resources.rightdock_browser
import mederi.app.shared.generated.resources.rightdock_diff
import mederi.app.shared.generated.resources.rightdock_overview
import mederi.app.shared.generated.resources.rightdock_plan
import mederi.app.shared.generated.resources.rightdock_terminal
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors

/**
 * 内置浏览器面板：desktop 注入 UiBrowserHost（tab = KBPage）渲染真内容；
 * 遥控端/wasm 未注入 → 显示"当前端不可用"占位（不尝试渲染 JCEF）。
 */
@Composable
internal fun BrowserPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val host = viewModel.uiBrowserHost
    if (host == null || !host.isAvailable) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.surfaceWorkspace),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Globe,
                    contentDescription = stringResource(Res.string.rightdock_browser),
                    tint = colors.textMuted,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(Res.string.dock_browser_unsupported),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.dock_browser_desktop_only),
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    host.BrowserContent(colors = colors)
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