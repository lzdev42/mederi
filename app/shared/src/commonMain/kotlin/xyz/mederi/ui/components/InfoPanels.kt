package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.dock_artifact_empty
import mederi.app.shared.generated.resources.dock_artifact_empty_hint
import mederi.app.shared.generated.resources.dock_compact_context
import mederi.app.shared.generated.resources.dock_context_max
import mederi.app.shared.generated.resources.dock_context_unset
import mederi.app.shared.generated.resources.overview_tokens_title
import mederi.app.shared.generated.resources.dock_cost_title
import mederi.app.shared.generated.resources.dock_diff_empty
import mederi.app.shared.generated.resources.dock_diff_empty_hint
import mederi.app.shared.generated.resources.dock_plan_empty
import mederi.app.shared.generated.resources.dock_plan_empty_hint
import mederi.app.shared.generated.resources.dock_refresh_changes
import mederi.app.shared.generated.resources.dock_requests_title
import mederi.app.shared.generated.resources.overview_approve_btn
import mederi.app.shared.generated.resources.overview_section_plan
import mederi.app.shared.generated.resources.overview_title
import mederi.app.shared.generated.resources.plan_read_full
import mederi.app.shared.generated.resources.plan_status_none
import mederi.app.shared.generated.resources.plan_status_pending
import mederi.app.shared.generated.resources.rawmsg_title
import mederi.app.shared.generated.resources.step_label
import mederi.app.shared.generated.resources.step_spec
import mederi.app.shared.generated.resources.worktrace_collapse
import mederi.app.shared.generated.resources.worktrace_expand
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.DiffView
import xyz.emuci.inkcompose.MarkdownView
import xyz.emuci.inkcompose.RenderStyle
import xyz.mederi.ui.ArtifactItem
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.PlanOverviewItem
import xyz.mederi.ui.PlanOverviewStatus
import xyz.mederi.ui.RawMessagesViewModel
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.theme.MederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.CardHeader
import xyz.mederi.ui.components.atoms.MederiCompactStrokeButton
import xyz.mederi.ui.components.atoms.MederiPrimaryDecisionButton
import xyz.mederi.ui.components.atoms.MederiStepStatusIcon
import xyz.mederi.ui.components.atoms.MederiSurfaceButton
import xyz.mederi.ui.components.atoms.MederiTabBadge
import xyz.mederi.ui.components.atoms.PanelCard
import xyz.mederi.ui.components.atoms.PanelEmptyState
import xyz.mederi.ui.components.atoms.StepStatus

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
                // 空态刷新动作：收敛为 MederiSurfaceButton（次级凸起变体，icon + 11sp 文本）
                MederiSurfaceButton(
                    text = stringResource(Res.string.dock_refresh_changes),
                    onClick = { viewModel.openDiff() },
                    icon = FeatherIcons.RefreshCw,
                )
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

/**
 * 概览面板 Section 标题行（原型 .overview-section-header）。
 * 11sp/500 textMuted + 0.05em 字距；右侧状态字可空（statusColor 缺省回落到 textMuted）。
 */
@Composable
private fun OverviewSectionHeader(
    label: String,
    statusText: String?,
    statusColor: Color?,
    colors: MederiColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.05f.em
        )
        if (statusText != null) {
            Text(
                text = statusText,
                color = statusColor ?: colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Token 用量卡（原型 .overview-tokens-card）：标题行（+ 压缩按钮）+ 主值/副文本 + 4dp 进度条。
 * 无 contextWindow 时不伪造占比：主值 "--"、副文本提示未设置、进度条不渲染。
 */
@Composable
private fun TokensOverviewCard(
    usedTokens: Long,
    maxTokens: Int,
    onCompact: () -> Unit,
    colors: MederiColors
) {
    val hasWindow = maxTokens > 0
    val progressRatio = if (hasWindow) (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f) else 0f
    val percentText = if (hasWindow) "${(progressRatio * 100).toInt()}%" else "--"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ① 标题 + Compact 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.overview_tokens_title),
                color = colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            MederiCompactStrokeButton(
                text = stringResource(Res.string.dock_compact_context),
                onClick = onCompact,
                icon = FeatherIcons.Minimize2
            )
        }

        // ② 主值（短格式如 42.8k） + 副文本（占比 · 上限 / 未设置提示）
        // 原型 .tokens-val-row 为 baseline 对齐：CMP 1.12 无 Alignment 伴生 baseline 常量，
        // 用子项 Modifier.alignByBaseline() 实现基线对齐
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (hasWindow) formatTokenShort(usedTokens) else "--",
                modifier = Modifier.alignByBaseline(),
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.02f).em,
                lineHeight = 24.sp
            )
            Text(
                text = if (hasWindow) {
                    "$percentText · ${stringResource(Res.string.dock_context_max, maxTokens)}"
                } else {
                    stringResource(Res.string.dock_context_unset)
                },
                modifier = Modifier.alignByBaseline(),
                color = colors.textSecondary,
                fontSize = 11.5.sp
            )
        }

        // ③ 进度条（无 contextWindow 时不渲染，避免误读为 0% 已用）
        if (hasWindow) {
            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accentPrimary,
                trackColor = colors.buttonSecondary
            )
        }
    }
}

/**
 * 用量数字小卡（原型 .usage-col-card）：标题 + 主值 + 可选副文本，weight(1f) 参与 2 列并排。
 */
@Composable
private fun UsageMetricCard(
    title: String,
    value: String,
    sub: String?,
    colors: MederiColors
) {
    // 注意：weight 不在本组件内声明——CMP 不向命名 composable 传播 @LayoutScopeMarker
    // scope receiver（RowScope/ColumnScope 只作用于字面 lambda），由调用方在 Row 内
    // 以 Box(Modifier.weight(1f)) 包裹（同旧用量双列调用点同构）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = colors.textSecondary,
            fontSize = 11.5.sp
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp
        )
        if (sub != null) {
            Text(
                text = sub,
                color = colors.textSecondary,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Token 短格式：≥1000 显示一位小数 k（42800 → 42.8k），否则原值。
 */
private fun formatTokenShort(tokens: Long): String {
    return if (tokens >= 1000) {
        val k = tokens / 1000
        val dec = (tokens % 1000) / 100
        "${k}.${dec}k"
    } else {
        tokens.toString()
    }
}

/** 参考费用展示（三位小数收敛）；与 MetricsCards.kt 内同名 private 函数互不冲突（文件私有）。 */
private fun formatCost(cost: Double): String {
    return if (cost < 0.001) "0.000" else (kotlin.math.round(cost * 1000) / 1000.0).toString()
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
    // null = 模型未配置 contextWindow：映射为 0，TokensOverviewCard 据此显示 "--" 而非伪造 0% 已用
    val maxTokensValue = maxTokens ?: 0

    val plans = viewModel.planOverviewList

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部标题（原型 .overview-title-heading）
        Text(
            text = stringResource(Res.string.overview_title),
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        // 会话状态区（原型分节头已按用户要求移除，仅保留卡片）
        TokensOverviewCard(
            usedTokens = usedTokens,
            maxTokens = maxTokensValue,
            onCompact = { viewModel.requestCompaction() },
            colors = colors
        )
        // 用量 2 列（weight 在 RowScope 字面 lambda 内声明，见 UsageMetricCard 注释）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                UsageMetricCard(
                    title = stringResource(Res.string.dock_requests_title),
                    value = requestCount.toString(),
                    sub = null,
                    colors = colors
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                UsageMetricCard(
                    title = stringResource(Res.string.dock_cost_title),
                    value = "$${formatCost(costUsd)}",
                    sub = null,
                    colors = colors
                )
            }
        }

        // 计划区（原型分节头已按用户要求移除，仅保留卡片）
        PlansOverviewCard(
            plans = plans,
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

        // 仅当 todoList 有内容时展示 TodoList 板块（管理卡组之后）
        if (todoList.isNotEmpty()) {
            TodoListCard(
                todoList = todoList,
                colors = colors
            )
        }

        // ===== 分节：原始消息 =====
        OverviewSectionHeader(
            label = stringResource(Res.string.rawmsg_title),
            statusText = null,
            statusColor = null,
            colors = colors
        )
        RawMessagesCard(
            viewModel = viewModel,
            rawVm = rawVm,
            colors = colors
        )
    }
}

/**
 * 概览面板 - 计划卡片（用户要求：所有计划——待批准/执行中/已完成/已作废——收进
 * 一张可折叠卡片；默认折叠，展开后才显示逐个 [PlanOverviewItemCard]）。
 */
@Composable
internal fun PlansOverviewCard(
    plans: List<PlanOverviewItem>,
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    var isExpanded by remember { mutableStateOf(false) }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 卡片 Header：整行可点击展开/折叠
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isExpanded = !isExpanded }
            ) {
                CardHeader(
                    icon = FeatherIcons.FileText,
                    title = stringResource(Res.string.overview_section_plan),
                    count = {
                        // 计划总数徽标（含全部状态）
                        MederiTabBadge(count = plans.size)
                    },
                    actions = {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                if (plans.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.plan_status_none),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        plans.forEach { plan ->
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
}

@Composable
private fun PlanOverviewItemCard(
    item: PlanOverviewItem,
    viewModel: WorkspaceViewModel,
    colors: MederiColors
) {
    // 步骤列表默认折叠到前 3 步，可展开全部
    var isSubtasksExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // plan-main-row：标题 + 待批准钮（soft iris）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                modifier = Modifier.weight(1f, fill = false),
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.status == PlanOverviewStatus.PendingApproval) {
                MederiPrimaryDecisionButton(
                    text = stringResource(Res.string.overview_approve_btn),
                    onClick = { viewModel.approvePlan(item.id) },
                    icon = FeatherIcons.Check
                )
            }
        }

        // plan-doc-link：阅读 plan.md 全文
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { viewModel.openPlanFile(item) }
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(Res.string.plan_read_full),
                color = colors.accentText,
                fontSize = 12.sp
            )
        }

        // 步骤图形化列表（已完成 = 绿勾划线 / 进行中 = accent 高亮 / 待处理 = 灰）
        if (item.subtasks.isNotEmpty()) {
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val visible = if (isSubtasksExpanded) item.subtasks else item.subtasks.take(3)
                for (st in visible) {
                    val stDone = st.status.equals("COMPLETED", ignoreCase = true)
                    val stRunning = st.status.equals("IN_PROGRESS", ignoreCase = true)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MederiStepStatusIcon(
                            status = when {
                                stDone -> StepStatus.Done
                                stRunning -> StepStatus.Active
                                else -> StepStatus.Todo
                            }
                        )
                        Text(
                            text = "${stringResource(Res.string.step_label, st.index + 1)}: ${st.name}",
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = if (stRunning) 13.sp else 12.5.sp,
                            fontWeight = if (stRunning) FontWeight.Medium else FontWeight.Normal,
                            color = when {
                                stDone -> colors.textMuted
                                stRunning -> colors.textPrimary
                                else -> colors.textSecondary
                            },
                            textDecoration = if (stDone) TextDecoration.LineThrough else TextDecoration.None,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 有关联 spec → [Spec ↗] 链接
                        if (!st.spec.isNullOrBlank()) {
                            Text(
                                text = stringResource(Res.string.step_spec),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        viewModel.openSpecInExtension(
                                            planId = item.id,
                                            planTitle = item.title,
                                            subtaskIndex = st.index,
                                            subtaskName = st.name,
                                            specContent = st.spec
                                        )
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                color = colors.accentText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // 步数 > 3：展开/收起全部
                if (item.subtasks.size > 3) {
                    Text(
                        text = stringResource(
                            if (isSubtasksExpanded) Res.string.worktrace_collapse else Res.string.worktrace_expand
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { isSubtasksExpanded = !isSubtasksExpanded }
                            .padding(vertical = 4.dp),
                        color = colors.textSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}