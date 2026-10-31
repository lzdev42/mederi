package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.coroutines.delay
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.currentTimeMillis
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.browser.BrowserAutomationUiState
import xyz.mederi.ui.browser.BrowserFlowStep
import xyz.mederi.ui.browser.BrowserStepStatus
import xyz.mederi.ui.components.atoms.WorkspaceActionButton
import xyz.mederi.ui.components.atoms.WorkspaceActionSurface
import xyz.mederi.ui.components.atoms.WorkspaceActionTone
import xyz.mederi.ui.components.atoms.WorkspaceIconButton
import xyz.mederi.ui.components.atoms.expansionSemantics

/** 浮动入口保留原显隐和展开动作，详情与Dock共享内容，不再把浮动容器塞进Dock。 */
@Composable
fun BrowserAutomationFloatingBar(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val state = viewModel.browserAutomationState
    val isExpanded = viewModel.isBrowserAutomationFloatingExpanded
    val colors = LocalMederiColors.current
    if (!state.isAutomating && state.steps.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().zIndex(20f),
        verticalArrangement = Arrangement.spacedBy(MederiSpacing.Tight),
    ) {
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(WorkspaceStyle.ExpandMillis)) + expandVertically(
                animationSpec = tween(WorkspaceStyle.ExpandMillis), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(WorkspaceStyle.ExpandMillis)) + shrinkVertically(
                animationSpec = tween(WorkspaceStyle.ExpandMillis), shrinkTowards = Alignment.Bottom),
        ) {
            BrowserAutomationPopover(
                state = state, colors = colors,
                onClose = viewModel::closeBrowserAutomationFloating,
                onStopTask = viewModel::stopBrowserAutomation,
                onPreviewScreenshot = viewModel::openBrowserScreenshotPreview,
            )
        }
        BrowserAutomationCapsule(
            state = state, isExpanded = isExpanded, colors = colors,
            onClick = viewModel::toggleBrowserAutomationFloating,
            onStopTask = viewModel::stopBrowserAutomation,
        )
    }
}

/** 浮动摘要沿用当前动作/进度/耗时，去扫光和额外旋转装饰。 */
@Composable
private fun BrowserAutomationCapsule(
    state: BrowserAutomationUiState,
    isExpanded: Boolean,
    colors: MederiColors,
    onClick: () -> Unit,
    onStopTask: () -> Unit,
) {
    val screenshotLabel = stringResource(Res.string.browser_flow_screenshot_default)
    val currentStepText = remember(state.steps, state.isAutomating, state.taskSummary, screenshotLabel) {
        val active = state.steps.filterIsInstance<BrowserFlowStep.Action>().lastOrNull { it.status == BrowserStepStatus.RUNNING }
        when (val step = active ?: state.steps.lastOrNull()) {
            is BrowserFlowStep.Action -> step.title + step.target?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            is BrowserFlowStep.Narration -> step.text
            is BrowserFlowStep.Memory -> step.text
            is BrowserFlowStep.Screenshot -> step.title ?: screenshotLabel
            null -> state.taskSummary
        }
    }
    val actions = state.steps.filterIsInstance<BrowserFlowStep.Action>()
    val completed = actions.count { it.status == BrowserStepStatus.COMPLETED }
    val elapsed = rememberBrowserAutomationElapsed(state)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(
        if (hovered || focused) colors.surfaceHover else colors.surfaceCard,
        tween(WorkspaceStyle.HoverMillis), label = "browserSummaryBackground",
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = WorkspaceStyle.ControlHeight)
            .clip(RoundedCornerShape(MederiRadius.Control)).background(background).hoverable(source)
            .expansionSemantics(isExpanded) { onClick() }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = MederiSpacing.SM, vertical = MederiSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM),
    ) {
        Icon(FeatherIcons.Globe, null,
            tint = if (state.isAutomating) colors.accentText else colors.textSecondary,
            modifier = Modifier.size(WorkspaceStyle.IconSize))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MederiSpacing.Micro)) {
            Text(currentStepText ?: stringResource(Res.string.browser_automation_floating_title),
                style = WorkspaceStyle.Body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(MederiSpacing.SM)) {
                if (actions.isNotEmpty()) Text("$completed/${actions.size}", style = WorkspaceStyle.Caption, color = colors.textMuted)
                if (elapsed != null) Text(elapsed, style = WorkspaceStyle.Caption, color = colors.textMuted)
            }
        }
        WorkspaceIconButton(
            icon = if (isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronUp,
            onClick = onClick,
            contentDescription = stringResource(if (isExpanded) Res.string.browser_automation_collapse else Res.string.browser_automation_expand),
            colors = colors,
        )
        if (state.isAutomating) WorkspaceActionButton(
            text = stringResource(Res.string.browser_automation_stop_task),
            icon = FeatherIcons.Square, onClick = onStopTask, contentColor = colors.dangerText, colors = colors,
        )
    }
}

@Composable
private fun BrowserAutomationPopover(
    state: BrowserAutomationUiState,
    colors: MederiColors,
    onClose: () -> Unit,
    onStopTask: () -> Unit,
    onPreviewScreenshot: (BrowserFlowStep.Screenshot) -> Unit,
) {
    WorkspaceActionSurface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
        tone = WorkspaceActionTone.Neutral,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.browser_automation_floating_title), style = WorkspaceStyle.Title,
                color = colors.textPrimary, modifier = Modifier.weight(1f))
            WorkspaceIconButton(FeatherIcons.ChevronDown, onClose,
                stringResource(Res.string.browser_automation_collapse), colors = colors)
        }
        BrowserAutomationPanelContent(state, colors, onStopTask, onPreviewScreenshot,
            modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

/** 计时仅从真实起止时间派生；未提供终点的静止状态不伪造结束时间。 */
@Composable
internal fun rememberBrowserAutomationElapsed(state: BrowserAutomationUiState): String? {
    var nowMillis by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(state.isAutomating, state.startedAtMillis) {
        if (state.isAutomating && state.startedAtMillis != null) {
            while (true) { nowMillis = currentTimeMillis(); delay(1000L) }
        }
    }
    val start = state.startedAtMillis ?: return null
    val end = if (state.isAutomating) nowMillis else state.completedAtMillis ?: return null
    val seconds = (end - start).coerceAtLeast(0L) / 1000L
    return "${(seconds / 60L).toString().padStart(2, '0')}:${(seconds % 60L).toString().padStart(2, '0')}"
}
