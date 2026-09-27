package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.tool_detail_failed_output
import mederi.app.shared.generated.resources.tool_detail_output
import mederi.app.shared.generated.resources.tool_detail_report
import mederi.app.shared.generated.resources.tool_action_ask_many
import mederi.app.shared.generated.resources.tool_action_ask_one
import mederi.app.shared.generated.resources.tool_action_ask_running
import mederi.app.shared.generated.resources.tool_action_edit_many
import mederi.app.shared.generated.resources.tool_action_edit_one
import mederi.app.shared.generated.resources.tool_action_edit_running
import mederi.app.shared.generated.resources.tool_action_edit_running_target
import mederi.app.shared.generated.resources.tool_action_edit_target
import mederi.app.shared.generated.resources.tool_action_failed_suffix
import mederi.app.shared.generated.resources.tool_action_list_many
import mederi.app.shared.generated.resources.tool_action_list_one
import mederi.app.shared.generated.resources.tool_action_list_running
import mederi.app.shared.generated.resources.tool_action_list_running_target
import mederi.app.shared.generated.resources.tool_action_list_target
import mederi.app.shared.generated.resources.tool_action_mcp_many
import mederi.app.shared.generated.resources.tool_action_mcp_running_target
import mederi.app.shared.generated.resources.tool_action_mcp_target
import mederi.app.shared.generated.resources.tool_action_other_many
import mederi.app.shared.generated.resources.tool_action_other_running
import mederi.app.shared.generated.resources.tool_action_other_running_target
import mederi.app.shared.generated.resources.tool_action_other_target
import mederi.app.shared.generated.resources.tool_action_read_many
import mederi.app.shared.generated.resources.tool_action_read_one
import mederi.app.shared.generated.resources.tool_action_read_running
import mederi.app.shared.generated.resources.tool_action_read_running_target
import mederi.app.shared.generated.resources.tool_action_read_target
import mederi.app.shared.generated.resources.tool_action_run_many
import mederi.app.shared.generated.resources.tool_action_run_one
import mederi.app.shared.generated.resources.tool_action_run_running
import mederi.app.shared.generated.resources.tool_action_run_running_target
import mederi.app.shared.generated.resources.tool_action_run_target
import mederi.app.shared.generated.resources.tool_action_search_many
import mederi.app.shared.generated.resources.tool_action_search_one
import mederi.app.shared.generated.resources.tool_action_search_running
import mederi.app.shared.generated.resources.tool_action_search_running_target
import mederi.app.shared.generated.resources.tool_action_search_target
import mederi.app.shared.generated.resources.tool_action_subagent_many
import mederi.app.shared.generated.resources.tool_action_subagent_one
import mederi.app.shared.generated.resources.tool_action_subagent_running
import mederi.app.shared.generated.resources.tool_action_subagent_running_target
import mederi.app.shared.generated.resources.tool_action_subagent_target
import mederi.app.shared.generated.resources.tool_action_todo_many
import mederi.app.shared.generated.resources.tool_action_todo_one
import mederi.app.shared.generated.resources.tool_action_todo_running
import mederi.app.shared.generated.resources.tool_action_verify_many
import mederi.app.shared.generated.resources.tool_action_verify_one
import mederi.app.shared.generated.resources.tool_action_verify_running
import mederi.app.shared.generated.resources.tool_action_verify_running_target
import mederi.app.shared.generated.resources.tool_action_verify_target
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.ui.DebugLog
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.SubagentReportMarkdown
import xyz.mederi.ui.chat.ToolActionGroup
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.chat.groupToolCallsByAction
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.rememberMederiMarkdownTheme
import xyz.mederi.ui.components.atoms.ExpandChevron
import xyz.mederi.ui.components.atoms.ExpandableContent
import xyz.mederi.ui.components.atoms.ExpandableRow

/**
 * 工具调用时间线 (ToolCallsBlock)：按动作类别连续聚合为极简动作行。
 *
 * - 单条：`>_ Ran command <command>` / `Read file <file>`
 * - 多条：`>_ Ran 2 commands` / `Read 2 files`
 * - 展开：展示 `$ <command>` 命令行，`OUTPUT` 标题，及黑色终端风格执行输出框。
 */
@Composable
fun ToolCallsBlock(
    toolCalls: List<ToolCallUi>,
    isStreaming: Boolean = false,
    isRunning: Boolean = false,
    hasFailedTool: Boolean = false,
    toolSummary: String = "",
    modifier: Modifier = Modifier,
) {
    if (toolCalls.isEmpty()) return

    val groups = remember(toolCalls) { groupToolCallsByAction(toolCalls) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        groups.forEach { group ->
            key(group.calls.firstOrNull()?.id ?: group.kind.name) {
                ToolActionGroupRow(
                    group = group,
                    isStreaming = isStreaming,
                    isRunning = isRunning,
                )
            }
        }
    }
}

/**
 * 单个动作组的时间线行：折叠态单行图标+摘要，展开态展示命令原文及终端输出代码框。
 */

@Composable
fun ToolActionGroupRow(
    group: ToolActionGroup,
    isStreaming: Boolean,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isFailed = group.calls.any { it.isFailed }
    val isGroupRunning = (isRunning || isStreaming) && group.calls.any { it.state is ToolCallState.Running }
    val isSubagentPreparing = group.kind == ToolActionKind.SUBAGENT && isGroupRunning
    val canExpand = !isSubagentPreparing

    // 所有工具动作行严格默认不展开（即使执行失败也保持折叠，需要点击才展开）；用户点击后以用户状态为准；准备中子任务不可展开
    var userChoice by remember(group.calls.map { it.id }) { mutableStateOf(false) }
    val isExpanded = userChoice && canExpand

    val count = group.calls.size
    val firstCall = group.calls.first()
    val singleTarget = firstCall.target?.trim()?.takeIf { it.isNotBlank() }

    val defaultMutedColor = colors.textSecondary

    // 图标与颜色
    val (iconVector, iconTint) = when {
        isFailed -> Pair(FeatherIcons.AlertCircle, colors.accentDanger)
        group.kind == ToolActionKind.COMMAND -> Pair(TerminalPromptIcon, defaultMutedColor)
        group.kind == ToolActionKind.READ -> Pair(FeatherIcons.FileText, defaultMutedColor)
        group.kind == ToolActionKind.EDIT -> Pair(FeatherIcons.Edit3, colors.thoughtAccent)
        group.kind == ToolActionKind.SEARCH -> Pair(FeatherIcons.Search, defaultMutedColor)
        group.kind == ToolActionKind.LIST -> Pair(FeatherIcons.Folder, defaultMutedColor)
        group.kind == ToolActionKind.SUBAGENT -> Pair(FeatherIcons.Users, colors.thoughtAccent)
        group.kind == ToolActionKind.MCP -> Pair(FeatherIcons.Cpu, defaultMutedColor)
        group.kind == ToolActionKind.ASK -> Pair(FeatherIcons.HelpCircle, defaultMutedColor)
        group.kind == ToolActionKind.TODO -> Pair(FeatherIcons.CheckSquare, defaultMutedColor)
        group.kind == ToolActionKind.VERIFY -> Pair(FeatherIcons.CheckCircle, colors.thoughtAccent)
        else -> Pair(FeatherIcons.Zap, defaultMutedColor)
    }

    // 标题文本（全部走资源化，三类：执行中 / 单条 / 多条；带目标时用 _target/_running_target 格式化）
    val titleText = run {
        val base = when {
            isGroupRunning -> when (group.kind) {
                ToolActionKind.COMMAND -> singleTarget?.let { stringResource(Res.string.tool_action_run_running_target, it) }
                    ?: stringResource(Res.string.tool_action_run_running)
                ToolActionKind.READ -> singleTarget?.let { stringResource(Res.string.tool_action_read_running_target, it) }
                    ?: stringResource(Res.string.tool_action_read_running)
                ToolActionKind.EDIT -> singleTarget?.let { stringResource(Res.string.tool_action_edit_running_target, it) }
                    ?: stringResource(Res.string.tool_action_edit_running)
                ToolActionKind.SEARCH -> singleTarget?.let { stringResource(Res.string.tool_action_search_running_target, it) }
                    ?: stringResource(Res.string.tool_action_search_running)
                ToolActionKind.LIST -> singleTarget?.let { stringResource(Res.string.tool_action_list_running_target, it) }
                    ?: stringResource(Res.string.tool_action_list_running)
                ToolActionKind.SUBAGENT -> stringResource(Res.string.tool_action_subagent_running)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_running_target, singleTarget ?: firstCall.name)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_running)
                ToolActionKind.TODO -> stringResource(Res.string.tool_action_todo_running)
                ToolActionKind.VERIFY -> singleTarget?.let { stringResource(Res.string.tool_action_verify_running_target, it) }
                    ?: stringResource(Res.string.tool_action_verify_running)
                else -> singleTarget?.let { stringResource(Res.string.tool_action_other_running_target, it) }
                    ?: stringResource(Res.string.tool_action_other_running)
            }
            count == 1 -> when (group.kind) {
                ToolActionKind.COMMAND -> singleTarget?.let { stringResource(Res.string.tool_action_run_target, it) }
                    ?: stringResource(Res.string.tool_action_run_one)
                ToolActionKind.READ -> singleTarget?.let { stringResource(Res.string.tool_action_read_target, it) }
                    ?: stringResource(Res.string.tool_action_read_one)
                ToolActionKind.EDIT -> singleTarget?.let { stringResource(Res.string.tool_action_edit_target, it) }
                    ?: stringResource(Res.string.tool_action_edit_one)
                ToolActionKind.SEARCH -> singleTarget?.let { stringResource(Res.string.tool_action_search_target, it) }
                    ?: stringResource(Res.string.tool_action_search_one)
                ToolActionKind.LIST -> singleTarget?.let { stringResource(Res.string.tool_action_list_target, it) }
                    ?: stringResource(Res.string.tool_action_list_one)
                ToolActionKind.SUBAGENT -> singleTarget?.let { stringResource(Res.string.tool_action_subagent_target, it) }
                    ?: stringResource(Res.string.tool_action_subagent_one)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_target, singleTarget ?: firstCall.name)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_one)
                ToolActionKind.TODO -> stringResource(Res.string.tool_action_todo_one)
                ToolActionKind.VERIFY -> singleTarget?.let { stringResource(Res.string.tool_action_verify_target, it) }
                    ?: stringResource(Res.string.tool_action_verify_one)
                else -> stringResource(Res.string.tool_action_other_target, singleTarget ?: firstCall.name)
            }
            else -> when (group.kind) {
                ToolActionKind.COMMAND -> stringResource(Res.string.tool_action_run_many, count)
                ToolActionKind.READ -> stringResource(Res.string.tool_action_read_many, count)
                ToolActionKind.EDIT -> stringResource(Res.string.tool_action_edit_many, count)
                ToolActionKind.SEARCH -> stringResource(Res.string.tool_action_search_many, count)
                ToolActionKind.LIST -> stringResource(Res.string.tool_action_list_many, count)
                ToolActionKind.SUBAGENT -> stringResource(Res.string.tool_action_subagent_many, count)
                ToolActionKind.MCP -> stringResource(Res.string.tool_action_mcp_many, count)
                ToolActionKind.ASK -> stringResource(Res.string.tool_action_ask_many, count)
                ToolActionKind.TODO -> stringResource(Res.string.tool_action_todo_many, count)
                ToolActionKind.VERIFY -> stringResource(Res.string.tool_action_verify_many, count)
                else -> stringResource(Res.string.tool_action_other_many, count)
            }
        }

        if (isFailed) "$base ${stringResource(Res.string.tool_action_failed_suffix)}" else base
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 折叠微条（无卡片背景与硬边框，整行可点击，准备中子任务不可展开）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (canExpand) {
                        Modifier.clickable {
                            userChoice = !isExpanded
                            DebugLog.event("UI", "ToolActionGroupRow clicked: kind=${group.kind}, count=${group.calls.size}, isExpanded=$userChoice")
                        }
                    } else Modifier
                )
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isGroupRunning) {
                CircularProgressIndicator(
                    color = colors.statusWorking,
                    strokeWidth = 1.4.dp,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(13.dp)
                )
            }

            Text(
                text = titleText,
                color = if (isFailed) colors.accentDanger else defaultMutedColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (canExpand) {
                Spacer(modifier = Modifier.width(2.dp))
                ExpandChevron(expanded = isExpanded, tint = defaultMutedColor, size = 11.dp)
            }
        }

        // 展开后的具体执行明细（可展开时才渲染）
        if (canExpand) {
            ExpandableContent(expanded = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.calls.forEach { call ->
                    val isCallFailed = call.isFailed
                    val completedState = call.state as? ToolCallState.Completed
                    val isReportTool = call.name in SubagentReportMarkdown.REPORT_TOOL_NAMES
                    // 组合期 JSON 解码（SubagentToolResult 解析）用 remember 缓存，避免重组时重复解析
                    val reportMarkdown = remember(call.id, completedState) {
                        if (isReportTool && completedState != null) {
                            SubagentReportMarkdown.fromToolResult(call.name, completedState.output)
                        } else null
                    }
                    val output = when (val s = call.state) {
                        is ToolCallState.Completed -> s.output
                        is ToolCallState.Failed -> s.error
                        else -> null
                    }?.trim()

                    if (reportMarkdown != null) {
                        // 子代理任务报告 Markdown（默认不展开，点击 REPORT 展开）
                        ToolCallReportBlock(
                            callId = call.id,
                            reportMarkdown = reportMarkdown,
                            colors = colors,
                        )
                    } else if (group.kind == ToolActionKind.COMMAND) {
                        // 命令行展开：命令行展示 + 输出点击展开（默认不展开）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!call.target.isNullOrBlank()) {
                                Text(
                                    text = "$ ${call.target}",
                                    color = if (colors.isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                            if (!output.isNullOrBlank()) {
                                ToolCallOutputBlock(
                                    callId = call.id,
                                    output = output,
                                    isFailed = isCallFailed,
                                    colors = colors,
                                    maxHeight = 280.dp,
                                )
                            }
                        }
                    } else {
                        // 通用工具调用（读取/编辑/检索等）：目标显示 + 输出点击展开（默认不展开）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 仅多条聚合时在明细项中展示各自目标；单条调用时标题已展示目标，避免垂直重复
                            if (count > 1 && !call.target.isNullOrBlank()) {
                                Text(
                                    text = call.target,
                                    color = if (colors.isDark) Color(0xFFCBD5E1) else colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                            if (!output.isNullOrBlank()) {
                                ToolCallOutputBlock(
                                    callId = call.id,
                                    output = output,
                                    isFailed = isCallFailed,
                                    colors = colors,
                                    maxHeight = 240.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * 工具调用输出块：严格按要求默认不展开，用户点击 "OUTPUT" 或 "FAILED" 才展开，
 * 内部带有终端/代码输出框，支持滚动与长输出保护。
 */

@Composable
private fun ToolCallOutputBlock(
    callId: String,
    output: String,
    isFailed: Boolean,
    colors: xyz.mederi.theme.MederiColors,
    maxHeight: Dp = 280.dp,
    defaultExpanded: Boolean = true,
) {
    var isOutputExpanded by remember(callId) { mutableStateOf(defaultExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // 待配色迁移时对齐标准 badge：OUTPUT/FAILED 标签为 mono 终端风格且 FAILED 态需 danger 色，
        // 原子（MederiGhostButton/TabBadge）无等价表达，暂保留内联折叠条
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    isOutputExpanded = !isOutputExpanded
                    DebugLog.event("UI", "ToolCallOutputBlock clicked: callId=$callId, isExpanded=$isOutputExpanded, isFailed=$isFailed")
                }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(if (isFailed) Res.string.tool_detail_failed_output else Res.string.tool_detail_output),
                color = if (isFailed) colors.accentDanger else colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            ExpandChevron(
                expanded = isOutputExpanded,
                tint = if (isFailed) colors.accentDanger else colors.textSecondary,
                size = 10.dp,
            )
        }

        ExpandableContent(expanded = isOutputExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceCode)
                    .border(
                        1.dp,
                        if (isFailed) colors.accentDanger.copy(alpha = 0.4f) else colors.surfaceCardBorder,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = output,
                        color = if (isFailed) colors.accentDanger else colors.onSurfaceCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .containScroll()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * 子代理任务报告 Markdown 展开块：默认不展开，点击 "REPORT" 才展开展示富文本。
 */

@Composable
private fun ToolCallReportBlock(
    callId: String,
    reportMarkdown: String,
    colors: xyz.mederi.theme.MederiColors,
) {
    var isReportExpanded by remember(callId) { mutableStateOf(false) }

    ExpandableRow(
        expanded = isReportExpanded,
        onExpandedChange = { newVal ->
            isReportExpanded = newVal
            DebugLog.event("UI", "ToolCallReportBlock clicked: callId=$callId, isExpanded=$newVal")
        },
        modifier = Modifier.fillMaxWidth(),
        chevronTint = colors.thoughtAccent,
        header = {
            Text(
                text = stringResource(Res.string.tool_detail_report),
                color = colors.thoughtAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (colors.isDark) Color(0xFF161822) else Color(0xFFF8FAFC))
                .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                .padding(10.dp)
        ) {
            MarkdownView(
                content = reportMarkdown,
                modifier = Modifier.fillMaxWidth(),
                enableScrollOverride = false,
                markdownTheme = rememberMederiMarkdownTheme()
            )
        }
    }
}

/**
 * 结构化子 Agent 调用追踪微栏 (SubagentCallsBlock)
 * 独立折叠条：与普通工具调用解耦，显示派发的子 Agent 状态与任务，展开可查看执行详情。
 */

@Composable
fun SubagentCallsBlock(
    subagents: List<ToolCallUi>,
    isStreaming: Boolean = false,
    isRunning: Boolean = false,
    hasFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (subagents.isEmpty()) return
    ToolCallsBlock(
        toolCalls = subagents,
        isStreaming = isStreaming,
        isRunning = isRunning,
        hasFailedTool = hasFailed,
        modifier = modifier,
    )
}
