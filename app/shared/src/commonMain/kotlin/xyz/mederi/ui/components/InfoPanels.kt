package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_plan_empty
import mederi.app.shared.generated.resources.dock_plan_empty_hint
import mederi.app.shared.generated.resources.dock_refresh_changes
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.DiffView
import xyz.emuci.inkcompose.MarkdownView
import xyz.emuci.inkcompose.RenderStyle
import xyz.mederi.ui.ArtifactItem
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.PlanOverviewItem
import xyz.mederi.ui.PlanOverviewStatus
import xyz.mederi.ui.components.atoms.CardHeader
import xyz.mederi.ui.components.atoms.PanelCard
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            MarkdownView(
                content = planContent,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = ChatLayout.contentMaxWidth)
                    .fillMaxWidth(),
                enableScrollOverride = true,
                markdownTheme = rememberMederiMarkdownTheme(style = RenderStyle.Github)
            )
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

        // 实施计划列表卡片（当前会话制定过的所有计划、状态、批准动作与关联 Spec）
        PlansOverviewCard(
            plans = viewModel.planOverviewList,
            viewModel = viewModel,
            colors = colors
        )

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

/**
 * 概览面板 - 实施计划列表卡片
 * 展示当前会话制定过的全部计划（待批准、执行中、已完成、已作废），
 * 待批准支持直接点击批准执行，支持直接打开阅读 plan.md 全文与各步骤的 Spec。
 */
@Composable
internal fun PlansOverviewCard(
    plans: List<PlanOverviewItem>,
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(
                icon = FeatherIcons.FileText,
                title = "实施计划 (Plans)",
                count = {
                    Text(
                        text = "${plans.size} 个计划",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            )

            if (plans.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceWorkspace)
                        .padding(vertical = 16.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前会话尚未制定计划，AI 产出计划时将在此沉淀留痕",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (plan in plans) {
                        PlanOverviewItemCard(
                            item = plan,
                            viewModel = viewModel,
                            colors = colors
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanOverviewItemCard(
    item: PlanOverviewItem,
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    val statusBg: Color
    val statusBorder: Color
    val statusDotColor: Color
    val statusText: String

    when (item.status) {
        PlanOverviewStatus.PendingApproval -> {
            statusBg = Color(0xFFF59E0B).copy(alpha = 0.15f)
            statusBorder = Color(0xFFF59E0B).copy(alpha = 0.4f)
            statusDotColor = Color(0xFFF59E0B)
            statusText = "待批准"
        }
        PlanOverviewStatus.InProgress -> {
            statusBg = Color(0xFF0284C7).copy(alpha = 0.15f)
            statusBorder = Color(0xFF0284C7).copy(alpha = 0.4f)
            statusDotColor = Color(0xFF38BDF8)
            statusText = "执行中"
        }
        PlanOverviewStatus.Completed -> {
            statusBg = Color(0xFF10B981).copy(alpha = 0.15f)
            statusBorder = Color(0xFF10B981).copy(alpha = 0.4f)
            statusDotColor = Color(0xFF34D399)
            statusText = "已完成"
        }
        PlanOverviewStatus.Voided -> {
            statusBg = colors.surfaceCardBorder.copy(alpha = 0.3f)
            statusBorder = colors.surfaceCardBorder
            statusDotColor = colors.textMuted
            statusText = "已作废"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceWorkspace)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部：状态 Badge + 计划标题 + 批准按钮（若待批准）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // 状态胶囊
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusBg)
                        .border(1.dp, statusBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Text(
                        text = statusText,
                        color = statusDotColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = item.title,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 待批准时展示「批准执行 ⌘↵」黄色微型操作按钮
            if (item.status == PlanOverviewStatus.PendingApproval) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFEAB308))
                        .clickable { viewModel.approvePlan(item.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "批准执行 ⌘↵",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 摘要说明（如果有）
        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }

        // 快捷操作条：直接阅读 plan.md 全文
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(4.dp))
                    .clickable { viewModel.openPlanFile(item) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.ExternalLink,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "阅读 plan.md 全文",
                    color = colors.accentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 子任务清单
        if (item.subtasks.isNotEmpty()) {
            HorizontalDivider(
                color = colors.surfaceCardBorder.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (st in item.subtasks) {
                    val stDone = st.status.equals("COMPLETED", ignoreCase = true)
                    val stRunning = st.status.equals("IN_PROGRESS", ignoreCase = true)
                    val dotColor = when {
                        stDone -> Color(0xFF10B981)
                        stRunning -> Color(0xFF0284C7)
                        else -> colors.textMuted
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceCard.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            // 步骤 1, 步骤 2 胶囊
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.surfaceCardBorder.copy(alpha = 0.4f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "步骤 ${st.index + 1}",
                                    color = colors.textSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = st.name,
                                color = if (stDone) colors.textMuted else colors.textPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 如果有关联 spec，展示 [Spec ↗] 按钮
                        if (!st.spec.isNullOrBlank()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.accentPrimary.copy(alpha = 0.12f))
                                    .border(0.5.dp, colors.accentPrimary.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                                    .clickable {
                                        viewModel.openSpecInExtension(
                                            planId = item.id,
                                            planTitle = item.title,
                                            subtaskIndex = st.index,
                                            subtaskName = st.name,
                                            specContent = st.spec
                                        )
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Spec ↗",
                                    color = colors.accentPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}