package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_plan_approve
import mederi.app.shared.generated.resources.dock_plan_empty
import mederi.app.shared.generated.resources.dock_plan_empty_hint
import mederi.app.shared.generated.resources.dock_plan_modify
import mederi.app.shared.generated.resources.dock_plan_pending
import mederi.app.shared.generated.resources.dock_refresh_changes
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.DiffView
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.core.ui.ArtifactItem
import xyz.mederi.core.ui.RawMessagesViewModel
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.PanelEmptyState

@Composable
internal fun DiffPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val diffs = viewModel.diffItems
    val selectedFilePath = viewModel.selectedDiffFilePath ?: diffs.firstOrNull()?.filePath

    if (diffs.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.GitCommit,
            title = stringResource(Res.string.dock_diff_empty),
            hint = stringResource(Res.string.dock_diff_empty_hint),
            action = {
                Button(
                    onClick = { viewModel.openDiff() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.buttonSecondary,
                        contentColor = colors.textPrimary
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.dock_refresh_changes), fontSize = 11.sp)
                }
            }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceCard)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                diffs.forEach { diff ->
                    val isSelected = diff.filePath == selectedFilePath
                    val fileName = diff.filePath.substringAfterLast("/")
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) colors.divider else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { viewModel.selectDiffFile(diff.filePath) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = fileName,
                            color = if (isSelected) colors.textPrimary else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (diff.additions > 0) {
                            Text("+${diff.additions}", color = colors.accentSuccess, fontSize = 10.sp)
                        }
                        if (diff.deletions > 0) {
                            Text("-${diff.deletions}", color = colors.accentDanger, fontSize = 10.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)

            val currentDiff = diffs.find { it.filePath == selectedFilePath } ?: diffs.first()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                DiffView(
                    oldText = currentDiff.before,
                    newText = currentDiff.after,
                    filePath = currentDiff.filePath,
                    modifier = Modifier.fillMaxSize(),
                    showHeader = false,
                )
            }
        }
    }
}

@Composable
internal fun PlanPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    // 读 VM 派生属性（pendingPlanApproval），不直探内部 snapshot 表示
    val pending = viewModel.pendingPlanApproval
    val currentPlan = viewModel.currentPlan
    val planContent = currentPlan?.content
        ?: pending?.planContent
        ?: (if (pending != null) "# ${pending.title}\n\n${pending.summary}" else null)

    if (planContent.isNullOrBlank()) {
        PanelEmptyState(
            icon = FeatherIcons.FileText,
            title = stringResource(Res.string.dock_plan_empty),
            hint = stringResource(Res.string.dock_plan_empty_hint)
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pending != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentWarning.copy(alpha = 0.12f))
                        .border(1.dp, colors.accentWarning.copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.dock_plan_pending),
                        color = colors.accentWarning,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { viewModel.approvePlan(pending.id, approved = false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.buttonSecondary,
                                contentColor = colors.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(stringResource(Res.string.dock_plan_modify), fontSize = 10.5.sp)
                        }
                        Button(
                            onClick = { viewModel.approvePlan(pending.id, approved = true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.onAccentPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(stringResource(Res.string.dock_plan_approve), fontSize = 10.5.sp)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                MarkdownView(
                    content = planContent,
                    modifier = Modifier.fillMaxSize(),
                    enableScrollOverride = true,
                    markdownTheme = rememberMederiMarkdownTheme()
                )
            }
        }
    }
}

@Composable
internal fun ArtifactsPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val items = viewModel.artifactItems
    val activeId = viewModel.activeArtifactId

    if (items.isEmpty()) {
        PanelEmptyState(
            icon = FeatherIcons.File,
            title = stringResource(Res.string.dock_artifact_empty),
            hint = stringResource(Res.string.dock_artifact_empty_hint)
        )
    } else {
        val activeItem = items.find { it.id == activeId } ?: items.last()
        Column(modifier = Modifier.fillMaxSize()) {
            if (items.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceCard)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { item ->
                        val isSelected = item.id == activeItem.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) colors.surfaceWorkspace else Color.Transparent)
                                .clickable { viewModel.openArtifact(item.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = item.title,
                                color = if (isSelected) colors.accentPrimary else colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.divider)
            }

            when (activeItem) {
                is ArtifactItem.Image -> {
                    ImageViewerTabContent(
                        title = activeItem.title,
                        imageUrl = activeItem.imageUrl,
                        colors = colors
                    )
                }
                is ArtifactItem.Text -> {
                    val liveItem = viewModel.getLiveArtifactItem(activeItem.id) ?: activeItem
                    TextReaderTabContent(
                        title = liveItem.title,
                        content = liveItem.content,
                        lineCount = liveItem.lineCount,
                        charCount = liveItem.charCount,
                        isStreaming = liveItem.isStreaming,
                        colors = colors
                    )
                }
            }
        }
    }
}


@Composable
internal fun OverviewTabContent(
    panelWidthDp: Float,
    viewModel: WorkspaceViewModel,
    rawVm: RawMessagesViewModel,
    colors: MederiColors
) {
    val scrollState = rememberScrollState()

    // 上下文占用：用真实 prompt tokens（最近一次请求 API 报告值，与自动压缩触发同源），
    // 而非累计消耗（累计值会把每轮增长的前缀重复计算，长会话会虚高到超过 100%）
    val usedTokens = viewModel.contextUsedTokens
    // contextWindow 是 selectedModel 派生 StateFlow：collectAsState 订阅后，
    // 模型切换触发重组，同源读取的 referenceCostUsd 也随之刷新
    val maxTokens by viewModel.contextWindow.collectAsState()
    val requestCount = viewModel.userMessageCount
    // 参考价估算（models.dev 目录价 × token 用量），非真实账单
    val costUsd = viewModel.referenceCostUsd
    val todoList = viewModel.todos

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
    // 响应式判断：只有当宽度 >= 600dp 时才切换双列 Dashboard，窄屏时紧凑单列
    val isWide = panelWidthDp >= 600f
    // null = 模型未配置 contextWindow：映射为 0，ContextMetricsCard 据此显示 "--" 而非伪造 0% 已用
    val maxTokensValue = maxTokens ?: 0

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ContextMetricsCard(
                    usedTokens = usedTokens,
                    maxTokens = maxTokensValue,
                    requestCount = requestCount,
                    costUsd = costUsd,
                    onCompact = { viewModel.requestCompaction() },
                    colors = colors
                )
            }

            // 仅当 todoList 有内容时展示 TodoList 板块
            if (todoList.isNotEmpty()) {
                Box(modifier = Modifier.weight(1f)) {
                    TodoListCard(
                        todoList = todoList,
                        colors = colors
                    )
                }
            }
        }
    } else {
        // 紧凑单列布局
        ContextMetricsCard(
            usedTokens = usedTokens,
            maxTokens = maxTokensValue,
            requestCount = requestCount,
            costUsd = costUsd,
            onCompact = { viewModel.requestCompaction() },
            colors = colors
        )

            // 仅当 todoList 有内容时展示 TodoList 板块
            if (todoList.isNotEmpty()) {
                TodoListCard(
                    todoList = todoList,
                    colors = colors
                )
            }
        }

        // 子 Agent 任务管理卡片（当前会话，实施状态监测与详情大弹窗）
        SubAgentManagementCard(
            subagents = viewModel.subagents,
            viewModel = viewModel,
            colors = colors
        )

        // MCP 服务管理卡片（与后续市场共享唯一真理源 mcpStore）
        McpManagementCard(
            mcpStore = viewModel.mcpStore,
            colors = colors
        )

        // Skill 技能管理卡片（与后续市场共享唯一真理源 skillStore）
        SkillManagementCard(
            skillStore = viewModel.skillStore,
            colors = colors
        )

        // 原始消息列表（概览下方展示）
        RawMessagesCard(
            viewModel = viewModel,
            rawVm = rawVm,
            colors = colors
        )
    }
}