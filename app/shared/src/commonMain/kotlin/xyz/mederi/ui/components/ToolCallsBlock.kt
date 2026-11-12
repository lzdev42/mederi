package xyz.mederi.ui.components


import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import xyz.emuci.inkcompose.RenderStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.copy
import mederi.app.shared.generated.resources.tool_detail_report
import mederi.app.shared.generated.resources.tool_detail_pending
import mederi.app.shared.generated.resources.tool_detail_running
import mederi.app.shared.generated.resources.tool_detail_empty_output
import mederi.app.shared.generated.resources.tool_detail_empty_error
import mederi.app.shared.generated.resources.tool_action_ask_answered
import mederi.app.shared.generated.resources.tool_action_ask_answered_many
import mederi.app.shared.generated.resources.tool_action_ask_unanswered
import mederi.app.shared.generated.resources.tool_action_ask_declined
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
import mederi.app.shared.generated.resources.subagent_strip_dispatched_many
import mederi.app.shared.generated.resources.subagent_dispatch_guidance
import mederi.app.shared.generated.resources.subagent_strip_dispatched_one
import mederi.app.shared.generated.resources.subagent_strip_view_overview
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.DebugLog
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi
import xyz.mederi.ui.SubagentReportMarkdown
import xyz.mederi.ui.chat.ToolActionGroup
import xyz.mederi.ui.chat.ToolActionKind
import xyz.mederi.ui.chat.groupToolCallsByAction
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.components.atoms.ExpandableRow
import xyz.mederi.ui.components.atoms.MederiMarkdown
import xyz.mederi.ui.components.atoms.ProcessRow

/**
 * 工具调用时间线 (ToolCallsBlock)：按时序逐条展示极简动作行。
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
    onLinkClick: ((String) -> Unit)? = null,
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
                    onLinkClick = onLinkClick,
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
    onLinkClick: ((String) -> Unit)? = null,
) {
    val colors = LocalMederiColors.current
    val isFailed = group.calls.any { it.isFailed || it.state is ToolCallState.Failed }
    val isGroupRunning = (isRunning || isStreaming) && group.calls.any { it.state is ToolCallState.Running || it.state is ToolCallState.Pending }

    // 工具状态本身也是有效详情；运行中子代理同样可以查看输入与等待结果状态。
    var userChoice by remember(group.calls.map { it.id }) { mutableStateOf(false) }
    val isExpanded = userChoice

    val count = group.calls.size
    val firstCall = group.calls.first()
    val singleTarget = firstCall.target?.trim()?.takeIf { it.isNotBlank() }

    val defaultMutedColor = colors.textSecondary

    val isAskDeclined = group.kind == ToolActionKind.ASK && group.calls.any { call ->
        val output = when (val s = call.state) {
            is ToolCallState.Completed -> s.output
            is ToolCallState.Failed -> s.error
            else -> null
        }
        output?.contains("declined", ignoreCase = true) == true
    }

    val isAskPending = group.kind == ToolActionKind.ASK && group.calls.any { it.state is ToolCallState.Pending }

    // 图标与颜色
    val (iconVector, iconTint) = when {
        isFailed -> Pair(FeatherIcons.AlertCircle, colors.accentDanger)
        group.kind == ToolActionKind.COMMAND -> Pair(TerminalPromptIcon, defaultMutedColor)
        group.kind == ToolActionKind.READ -> Pair(FeatherIcons.FileText, defaultMutedColor)
        group.kind == ToolActionKind.EDIT -> Pair(FeatherIcons.Edit3, colors.textSecondary)
        group.kind == ToolActionKind.SEARCH -> Pair(FeatherIcons.Search, defaultMutedColor)
        group.kind == ToolActionKind.LIST -> Pair(FeatherIcons.Folder, defaultMutedColor)
        group.kind == ToolActionKind.SUBAGENT -> Pair(FeatherIcons.Users, colors.textSecondary)
        group.kind == ToolActionKind.MCP -> Pair(FeatherIcons.Cpu, defaultMutedColor)
        group.kind == ToolActionKind.ASK -> if (!isGroupRunning && !isAskDeclined) {
            Pair(FeatherIcons.HelpCircle, colors.textSecondary)
        } else {
            Pair(FeatherIcons.HelpCircle, defaultMutedColor)
        }
        group.kind == ToolActionKind.TODO -> Pair(FeatherIcons.CheckSquare, defaultMutedColor)
        group.kind == ToolActionKind.VERIFY -> Pair(FeatherIcons.CheckCircle, colors.textSecondary)
        else -> Pair(FeatherIcons.Zap, defaultMutedColor)
    }

    // 标题文本（全部走资源化，三类：执行中 / 单条 / 多条；带目标时用 _target/_running_target 格式化）
    val titleText = run {
        val base = when {
            isGroupRunning -> toolRunningLabel(group)
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
                ToolActionKind.ASK -> when {
                    isAskDeclined -> stringResource(Res.string.tool_action_ask_declined)
                    isAskPending -> stringResource(Res.string.tool_action_ask_unanswered)
                    else -> stringResource(Res.string.tool_action_ask_answered)
                }
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
                ToolActionKind.ASK -> when {
                    isAskDeclined -> stringResource(Res.string.tool_action_ask_declined)
                    isAskPending -> stringResource(Res.string.tool_action_ask_unanswered)
                    else -> stringResource(Res.string.tool_action_ask_answered_many, count)
                }
                ToolActionKind.TODO -> stringResource(Res.string.tool_action_todo_many, count)
                ToolActionKind.VERIFY -> stringResource(Res.string.tool_action_verify_many, count)
                else -> stringResource(Res.string.tool_action_other_many, count)
            }
        }

        if (isFailed) "$base ${stringResource(Res.string.tool_action_failed_suffix)}" else base
    }

    // 折叠微条：无卡片背景与硬边框，整行可点击查看详情与执行状态。
    // 外观壳（行高 / 圆角 / hover / 箭头 / 展开区导轨线）统一由 atoms/ProcessRow 提供，
    // 这里只负责「图标（分类 / 失败 / 运行中指示器）+ 文案 + 状态色 + 展开明细」。
    ProcessRow(
        expanded = isExpanded,
        onExpandedChange = { newExpanded ->
            userChoice = newExpanded
            DebugLog.event("UI", "ToolActionGroupRow clicked: kind=${group.kind}, count=${group.calls.size}, isExpanded=$userChoice")
        },
        modifier = modifier.fillMaxWidth(),
        icon = iconVector,
        iconTint = iconTint,
        label = titleText,
        labelColor = if (isFailed) colors.accentDanger else defaultMutedColor,
        labelRunning = isGroupRunning && !isFailed,
    ) {
        // 展开后的具体执行明细：无输出时也明确呈现真实执行状态。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            group.calls.forEach { call ->
                val isCallFailed = call.isFailed || call.state is ToolCallState.Failed
                val completedState = call.state as? ToolCallState.Completed
                val isReportTool = call.name in SubagentReportMarkdown.REPORT_TOOL_NAMES
                // 组合期 JSON 解码（SubagentToolResult 解析）用 remember 缓存，避免重组时重复解析
                val reportMarkdown = remember(call.id, completedState) {
                    if (isReportTool && completedState != null) {
                        SubagentReportMarkdown.fromToolResult(call.name, completedState.output)?.takeIf { it.isNotBlank() }
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
                        onLinkClick = onLinkClick,
                    )
                } else if (group.kind == ToolActionKind.ASK) {
                    // 问询明细展开：展示问题问了什么、用户回答了什么
                    ToolCallAskBlock(
                        call = call,
                        output = output,
                        isFailed = isCallFailed,
                        colors = colors,
                    )
                } else {
                    ToolCallExecutionDetails(call = call, isFailed = isCallFailed, colors = colors)
                }
            }
        }
    }
}

/**
 * 工具输入与结果。没有输出是明确的执行状态，不渲染空白展开区，也不伪造结果。
 * 参数逐项按原始数据展示，不拼成 JSON；缺少 target 时仍可查看已有输入。
 */
@Composable
private fun ToolCallExecutionDetails(
    call: ToolCallUi,
    isFailed: Boolean,
    colors: xyz.mederi.theme.MederiColors,
) {
    val input = when (val state = call.state) {
        is ToolCallState.Pending -> state.input
        is ToolCallState.Running -> state.input
        is ToolCallState.Completed -> state.input
        is ToolCallState.Failed -> state.input
    }
    val output = when (val state = call.state) {
        is ToolCallState.Completed -> state.output
        is ToolCallState.Failed -> state.error
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // target 若已在原始参数中出现则不重复；命令也使用原始参数展示。
        call.target?.takeIf { it.isNotBlank() && it !in input.values }?.let { target ->
            SelectionContainer {
                Text(target, color = colors.textSecondary, fontFamily = FontFamily.Monospace, style = WorkspaceStyle.Caption)
            }
        }
        input.forEach { (name, value) ->
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    Text(name, color = colors.textMuted, style = WorkspaceStyle.Caption)
                    Text(value, color = colors.textPrimary, fontFamily = FontFamily.Monospace, style = WorkspaceStyle.Caption)
                }
            }
        }
        if (!output.isNullOrBlank()) {
            ToolCallOutputBlock(
                callId = call.id,
                output = output,
                isFailed = isFailed,
                colors = colors,
            )
        } else {
            val status = when (call.state) {
                is ToolCallState.Pending -> Res.string.tool_detail_pending
                is ToolCallState.Running -> Res.string.tool_detail_running
                is ToolCallState.Completed -> Res.string.tool_detail_empty_output
                is ToolCallState.Failed -> Res.string.tool_detail_empty_error
            }
            Text(
                text = stringResource(status),
                color = if (isFailed) colors.accentDanger else colors.textMuted,
                style = WorkspaceStyle.Caption,
            )
        }
    }
}

/**
 * 工具调用输出块：直接呈现终端/代码输出框，支持滚动与长输出保护。
 */
@Composable
private fun ToolCallOutputBlock(
    callId: String,
    output: String,
    isFailed: Boolean,
    colors: xyz.mederi.theme.MederiColors,
    maxHeight: Dp = 280.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceCode)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        SelectionContainer {
            Text(
                text = output,
                color = if (isFailed) colors.accentDanger else colors.onSurfaceCode,
                fontFamily = FontFamily.Monospace,
                style = WorkspaceStyle.Caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .containScroll()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            )
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
    onLinkClick: ((String) -> Unit)? = null,
) {
    var isReportExpanded by remember(callId) { mutableStateOf(false) }

    ExpandableRow(
        expanded = isReportExpanded,
        onExpandedChange = { newVal ->
            isReportExpanded = newVal
            DebugLog.event("UI", "ToolCallReportBlock clicked: callId=$callId, isExpanded=$newVal")
        },
        modifier = Modifier.fillMaxWidth(),
        chevronTint = colors.textSecondary,
        header = {
            Text(
                text = stringResource(Res.string.tool_detail_report),
                color = colors.textSecondary,
                style = WorkspaceStyle.Title,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            MederiMarkdown(
                content = reportMarkdown,
                style = RenderStyle.Chat.copy(bodyStyle = WorkspaceStyle.Body),
                modifier = Modifier.fillMaxWidth(),
                enableScrollOverride = false,
                onLinkClick = onLinkClick,
            )
        }
    }
}

/**
 * 问询解析结果：记录单个问题的内容与用户回答。
 */
data class ParsedAskItem(
    val id: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val answer: String? = null,
    val isDeclined: Boolean = false,
)

/**
 * 从 ask_user 的入参 (input) 与工具出参 (output) 中解析结构化问答项。
 *
 * output 形态：
 * - `{"answers":[{"questionId":"q1","answers":[...]},...]}`（ask_user 的 AskUserResult 标准形态）
 * - 裸数组 `[{"questionId":...,"answers":[...]},...]`（同样按 questionId 归位）
 * - 非 JSON 纯文本（自定义工具的自由文本返回）→ 回退 generalAnswer
 *
 * 硬化（ask_user 已回答显示根因修复的 UI 侧配套）：
 * - 合法 JSON 但没提取出任何 answer 时，**不再**把原始 JSON 回退塞进问题（generalAnswer 保持 null）；
 * - Koog 引号包裹的遗留脏数据（`"{"answers":...}"`，JSONLiteral.toString() 包引号不转义）
 *   解析失败后同样不进 generalAnswer——整串原始 JSON 绝不显示为答案。
 */
fun parseAskItems(input: Map<String, String>, output: String?): List<ParsedAskItem> {
    val answersMap = mutableMapOf<String, String>()
    // 输出顺序的答案列表（questionId 缺失/不匹配时按序 1:1 回退用）
    val orderedAnswers = mutableListOf<String>()
    var generalAnswer: String? = null
    var isDeclined = false

    if (!output.isNullOrBlank()) {
        val trimmed = output.trim()
        if (trimmed.contains("declined", ignoreCase = true)) {
            isDeclined = true
        } else {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(trimmed)
                // 兼容 `{"answers":[...]}` 对象形态与裸数组形态
                val answersArray: JsonArray? = when (root) {
                    is JsonObject -> root["answers"] as? JsonArray
                    is JsonArray -> root
                    else -> null
                }
                answersArray?.forEach { elem ->
                    when (elem) {
                        is JsonObject -> {
                            val qId = (elem["questionId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            val ansList = (elem["answers"] as? JsonArray)?.mapNotNull {
                                (it as? JsonPrimitive)?.contentOrNull
                            }
                            if (ansList.isNullOrEmpty()) return@forEach
                            val answerText = ansList.joinToString(", ")
                            orderedAnswers.add(answerText)
                            if (qId.isNotBlank()) {
                                answersMap[qId] = answerText
                            }
                        }
                        is JsonPrimitive -> {
                            // 裸字符串数组 ["A", "B"]：无 questionId，只进顺序列表
                            val text = elem.contentOrNull
                            if (!text.isNullOrBlank()) orderedAnswers.add(text)
                        }
                        else -> Unit
                    }
                }
                // 合法 JSON 但未提取出任何 answer（空 answers 数组 / 无关 JSON）→ 不回退原始 JSON
            } catch (_: Exception) {
                // 回退仅保留给非 JSON 纯文本输出（如自定义工具返回的自由文本）；
                // Koog 引号包裹的 JSON 字符串（遗留脏数据）不是纯文本，不进 generalAnswer
                if (!isQuotedLegacyJson(trimmed)) {
                    generalAnswer = trimmed
                }
            }
        }
    }

    val questions = mutableListOf<ParsedAskItem>()
    val rawQuestions = input["questions"]
    if (!rawQuestions.isNullOrBlank()) {
        // 严格解析：只接受规范 JsonArray（每项含 prompt）。不再解包双层编码字符串、不再兼容
        // `question` 别名——旧会话脏数据走下方占位回退（纯显示，不崩）。
        try {
            val json = Json { ignoreUnknownKeys = true }
            val elem = json.parseToJsonElement(rawQuestions)
            if (elem is JsonArray) {
                elem.forEachIndexed { idx, item ->
                    if (item is JsonObject) {
                        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: "q_$idx"
                        val prompt = (item["prompt"] as? JsonPrimitive)?.contentOrNull ?: ""
                        val options = (item["options"] as? JsonArray)?.mapNotNull {
                            (it as? JsonPrimitive)?.contentOrNull
                        } ?: emptyList()
                        if (prompt.isNotBlank()) {
                            questions.add(ParsedAskItem(id = id, prompt = prompt, options = options))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    if (questions.isEmpty()) {
        val singlePrompt = input["prompt"] ?: input["question"] ?: input["query"]
        if (!singlePrompt.isNullOrBlank()) {
            questions.add(ParsedAskItem(id = "q_0", prompt = singlePrompt))
        }
    }

    if (questions.isEmpty()) {
        val fallbackPrompt = input.values.firstOrNull { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            ?: "提问"
        questions.add(ParsedAskItem(id = "q_0", prompt = fallbackPrompt))
    }

    // questionId 是否全部不匹配（答案里虽有 questionId，但没有一个对应当前问题的 id）
    val anyIdMatched = questions.any { answersMap.containsKey(it.id) }

    return questions.mapIndexed { idx, q ->
        val ans = when {
            isDeclined -> null
            answersMap.containsKey(q.id) -> answersMap[q.id]
            questions.size == 1 && answersMap.isNotEmpty() -> answersMap.values.first()
            questions.size == 1 && generalAnswer != null -> generalAnswer
            // 多问且 questionId 全部不匹配、但答案数与问题数一致 → 按序一一对应（最后回退）
            !anyIdMatched && orderedAnswers.size == questions.size -> orderedAnswers[idx]
            idx == 0 && generalAnswer != null -> generalAnswer
            else -> null
        }
        q.copy(answer = ans, isDeclined = isDeclined)
    }
}

/**
 * 判定 [text] 是否为 Koog 引号包裹的 JSON 字符串遗留脏数据：
 * 首尾各一个 `"`、内部以 `{` / `[` 开头——`JSONLiteral.toString()` 对字符串
 * 包引号且不转义内部引号的根因形态（如 `"{"answers":[...]}"`）。
 * 这类内容绝不是"自由文本答案"，不允许回退显示为答案。
 */
private fun isQuotedLegacyJson(text: String): Boolean {
    if (text.length < 4 || !text.startsWith("\"") || !text.endsWith("\"")) return false
    val inner = text.substring(1, text.length - 1).trimStart()
    return inner.startsWith("{") || inner.startsWith("[")
}

/**
 * 问询交互明细展开块：清晰呈现“问题问了什么，回答了什么”。
 */
@Composable
private fun ToolCallAskBlock(
    call: ToolCallUi,
    output: String?,
    isFailed: Boolean,
    colors: xyz.mederi.theme.MederiColors,
    modifier: Modifier = Modifier,
) {
    val input = when (val s = call.state) {
        is ToolCallState.Completed -> s.input
        is ToolCallState.Failed -> s.input
        is ToolCallState.Running -> s.input
        is ToolCallState.Pending -> s.input
    }
    val items = remember(call.id, input, output) {
        parseAskItems(input, output)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // 问题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = if (items.size > 1) "Q${index + 1}:" else "Q:",
                        color = colors.textSecondary,
                        style = WorkspaceStyle.Title,
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.prompt,
                            color = colors.textPrimary,
                            style = WorkspaceStyle.Body,
                        )
                    }
                }

                // 回答行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = if (items.size > 1) "A${index + 1}:" else "A:",
                        color = if (item.isDeclined) colors.accentDanger else colors.textSecondary,
                        style = WorkspaceStyle.Title,
                        fontFamily = FontFamily.Monospace,
                    )
                    val answerText = when {
                        isFailed && !output.isNullOrBlank() -> output
                        item.answer != null -> item.answer
                        item.isDeclined -> stringResource(Res.string.tool_action_ask_declined)
                        else -> stringResource(Res.string.tool_action_ask_running)
                    }
                    val answerColor = when {
                        isFailed -> colors.accentDanger
                        item.answer != null -> colors.textPrimary
                        item.isDeclined -> colors.textMuted
                        else -> colors.textMuted
                    }
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = answerText,
                            color = answerColor,
                            style = WorkspaceStyle.Body,
                            fontWeight = if (item.answer != null) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 成功派发后的独立通知，不依赖模型正文，也不随工作过程折叠。
 * 用留白、标题与细竖线区分层级，不叠加卡片底色、描边或持续运行动画。
 * 这是派发记录而非实时状态：之后的运行详情由概览承接。
 */
@Composable
fun SubagentCallsBlock(
    subagents: List<ToolCallUi>,
    isStreaming: Boolean = false,
    isRunning: Boolean = false,
    hasFailed: Boolean = false,
    onOpenOverview: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 组件本身也守住成功边界，避免其它调用入口提前显示成功提示。
    val dispatched = subagents.filter { call ->
        val completed = call.state as? ToolCallState.Completed
        return@filter !call.isFailed && completed != null &&
            completed.input["action"] in setOf("SPAWN", "SPAWN_RESEARCHER") &&
            !completed.output.startsWith("Error:")
    }
    if (dispatched.isEmpty()) return

    val colors = LocalMederiColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(2.dp).fillMaxHeight()
                .background(colors.accentPrimary.copy(alpha = 0.4f), RoundedCornerShape(1.dp))
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = colors.accentText,
                    modifier = Modifier.size(WorkspaceStyle.IconSize),
                )
                Text(
                    text = if (dispatched.size == 1) {
                        stringResource(Res.string.subagent_strip_dispatched_one)
                    } else {
                        stringResource(Res.string.subagent_strip_dispatched_many, dispatched.size)
                    },
                    color = colors.textPrimary,
                    style = WorkspaceStyle.Title,
                )
            }
            Text(
                text = stringResource(Res.string.subagent_dispatch_guidance),
                color = colors.textSecondary,
                style = WorkspaceStyle.Caption,
            )
            if (onOpenOverview != null) {
                Text(
                    text = stringResource(Res.string.subagent_strip_view_overview),
                    color = colors.accentText,
                    style = WorkspaceStyle.Caption,
                    modifier = Modifier.heightIn(min = WorkspaceStyle.ControlHeight)
                        .clickable(role = Role.Button, onClick = onOpenOverview).padding(vertical = 5.dp),
                )
            }
        }
    }
}


/** 工具行与工作过程活动摘要共用同一运行中文案。 */
@Composable
internal fun toolRunningLabel(group: ToolActionGroup): String {
    val firstCall = group.calls.first()
    val singleTarget = firstCall.target?.trim()?.takeIf { it.isNotBlank() }
    return when (group.kind) {
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
}
