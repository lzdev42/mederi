package xyz.mederi.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.image_stripped_notice
import mederi.app.shared.generated.resources.input_image_n
import mederi.app.shared.generated.resources.input_image_unsupported
import mederi.app.shared.generated.resources.input_over_budget
import mederi.app.shared.generated.resources.input_pending_image_thumbnail
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.input_placeholder
import mederi.app.shared.generated.resources.input_project_needed_create
import mederi.app.shared.generated.resources.input_project_needed_select
import mederi.app.shared.generated.resources.input_remove_image
import mederi.app.shared.generated.resources.input_remove_text
import mederi.app.shared.generated.resources.input_select_model
import mederi.app.shared.generated.resources.input_text_meta
import mederi.app.shared.generated.resources.input_text_n
import mederi.app.shared.generated.resources.queue_banner_title
import mederi.app.shared.generated.resources.queue_send_now
import mederi.app.shared.generated.resources.queue_remove
import mederi.app.shared.generated.resources.queue_steer_tooltip
import mederi.app.shared.generated.resources.reasoning_level_high
import mederi.app.shared.generated.resources.remote_clipboard_image_unavailable
import mederi.app.shared.generated.resources.remote_clipboard_text_unavailable
import mederi.app.shared.generated.resources.reasoning_level_low
import mederi.app.shared.generated.resources.reasoning_level_max
import mederi.app.shared.generated.resources.reasoning_level_medium
import mederi.app.shared.generated.resources.reasoning_level_minimal
import mederi.app.shared.generated.resources.reasoning_level_none
import mederi.app.shared.generated.resources.reasoning_level_xhigh
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.ui.components.command.SlashCommandTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Image
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.X
import xyz.emuci.inkcompose.InkImage
import xyz.mederi.util.PlatformClipboard
import xyz.mederi.util.PromptComposer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import xyz.mederi.core.contract.models.QueuedMessage
import xyz.mederi.core.contract.models.SkillItem
import xyz.mederi.ui.components.command.SlashCommandItem
import xyz.mederi.ui.components.command.SlashCommandMenu
import xyz.mederi.ui.components.command.SlashCommandRegistry
import xyz.mederi.ui.DebugLog
import xyz.mederi.ui.UiEffect
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.RemoteCapabilityNotice
import xyz.mederi.ui.appstate.LocalAppState
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity


/** 推理档位显示名（唯一映射点，桌面下拉与移动端抽屉共用；未知档位回显原始值）。 */
@Composable
fun formatReasoningLevelLabel(level: String): String = when (level.uppercase()) {
    "MINIMAL" -> stringResource(Res.string.reasoning_level_minimal)
    "LOW" -> stringResource(Res.string.reasoning_level_low)
    "MEDIUM" -> stringResource(Res.string.reasoning_level_medium)
    "HIGH" -> stringResource(Res.string.reasoning_level_high)
    "XHIGH" -> stringResource(Res.string.reasoning_level_xhigh)
    "MAX" -> stringResource(Res.string.reasoning_level_max)
    "NONE", "OFF" -> stringResource(Res.string.reasoning_level_none)
    else -> level
}

/** 模型能力小标签（Thinking / Image），桌面下拉与移动端抽屉共用。具有防折行与精致描边。 */
@Composable
internal fun ModelCapabilityTag(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(0.5.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun ChatInputCard(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    onOpenProjectPicker: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    // 草稿唯一真理源 = WorkspaceViewModel.inputDraft（会话级状态）：
    // 欢迎页/消息列表两个调用点共享，分支切换不丢字；选区菜单追加直接写 VM
    val textValue = viewModel.inputDraft
    // IME 桥接（wasmJs）：透明 <textarea> 覆盖输入框触发 iOS 软键盘，需跟踪输入框在窗口中的矩形。
    // positionInWindow 返回 Compose px（= CSS px * density），wasmJs actual 侧除 density 得 CSS px。
    var imeRect by remember { mutableStateOf<Rect?>(null) }
    val imeDensity = LocalDensity.current.density
    // wasmJs IME 桥接：textarea 持 DOM 焦点唤起键盘，但 Compose TextField 未拿 Compose 焦点
    // → Canvas 不画光标。用 FocusRequester 把 Compose 焦点同步给 TextField，使 Canvas 渲染
    // 自身光标（cursorColor=accentPrimary，深/浅主题自适应），与 Canvas 文字天然对齐（选区同步自 textarea）。
    val imeFocusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var imeFocus by remember { mutableStateOf(false) }
    LaunchedEffect(imeFocus) {
        runCatching {
            if (imeFocus) imeFocusRequester.requestFocus() else focusManager.clearFocus()
        }
    }
    var isMobileSheetOpen by remember { mutableStateOf(false) }
    // 聚焦态（原型 focus-within）：TextField 聚焦 → 卡片自身 border 与外圈 ring 切 accentFocus
    var focused by remember { mutableStateOf(false) }

    val selectedModel by appState.selectedModel.collectAsState()
    // 图片能力唯一推导（VM 派生流）：按钮显隐/警告/门禁全同源，UI 禁止手写 supportsImages 判断
    val modelSupportsImages by viewModel.modelSupportsImages.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val projects by appState.projects.collectAsState()
    // /skill 命令数据源：已安装 skill 列表（AppState.skillStore 唯一真理源）
    val skills by appState.skillStore.skills.collectAsState()

    // —— 快捷命令与联想输入系统（2026-09）：
    // 1. 输入 "/" 或输入单词首字母（如 "c"）激活联想菜单（类似输入法联想）；
    // 2. 按 Enter/Tab 自动补全完整命令；
    // 3. 按 Backspace 原子化删除整条命令 Token，避免逐字退格。
    var slashMenuDismissed by remember { mutableStateOf(false) }
    var slashSelectedIndex by remember { mutableStateOf(0) }

    // 全量命令条目（内置命令 + 动态安装的 Skills 插件）
    val allSlashCommands = remember(skills) {
        SlashCommandRegistry.getAllCommands(skills)
    }

    // 提取当前光标处的命令检索上下文（支持文本开头、中间或末尾）
    val cursorPosition = textValue.selection.start
    val activeQuery = remember(textValue.text, cursorPosition) {
        SlashCommandRegistry.findCommandQueryAtCursor(textValue.text, cursorPosition)
    }

    val slashQuery = activeQuery?.query ?: ""
    val isSlashMode = activeQuery?.isSlashMode ?: false
    val hasActiveQuery = activeQuery != null

    // 过滤候选列表：未输入 "/" 时启用 prefixOnly，模拟输入法前缀联想，避免普通英文词汇误触
    val filteredSlashCommands = remember(allSlashCommands, slashQuery, hasActiveQuery, isSlashMode) {
        if (hasActiveQuery) {
            SlashCommandRegistry.filter(allSlashCommands, slashQuery, prefixOnly = !isSlashMode)
        } else {
            emptyList()
        }
    }

    // 菜单显隐控制：只有在匹配到候选且未被 Esc 显式关闭时展示
    val slashMenuOpen = hasActiveQuery && filteredSlashCommands.isNotEmpty() && !slashMenuDismissed

    LaunchedEffect(filteredSlashCommands.size) {
        if (slashSelectedIndex >= filteredSlashCommands.size) {
            slashSelectedIndex = 0
        }
    }

    var lastInputText by remember { mutableStateOf(textValue.text) }
    val onSlashCommandTextChange: (TextFieldValue) -> Unit = { newValue ->
        if (newValue.text != lastInputText) {
            slashMenuDismissed = false
            lastInputText = newValue.text
        }
        viewModel.updateInputDraft(newValue)
    }

    val onSelectCommand: (SlashCommandItem) -> Unit = { item ->
        slashMenuDismissed = true
        if (item.customAction != null) {
            item.customAction.invoke()
        } else {
            val insert = item.insertText
            val queryRange = activeQuery?.range
            val newText = if (queryRange != null) {
                textValue.text.replaceRange(queryRange, insert)
            } else {
                insert
            }
            val newCursor = (queryRange?.first ?: 0) + insert.length
            viewModel.updateInputDraft(TextFieldValue(newText, TextRange(newCursor)))
        }
        DebugLog.data("UI", "slash command auto-completed", "id=${item.id}, insertText='${item.insertText}'")
    }

    val pendingPastedTexts = viewModel.pendingPastedTexts
    val pendingImages = viewModel.pendingImages

    // 容量预算检查（业务规则在 WorkspaceViewModel，UI 只计算展示）
    val totalPastedChars = pendingPastedTexts.sumOf { it.charCount }
    val totalInputChars = textValue.text.length + totalPastedChars
    val modelContextTokens = selectedModel?.contextWindow ?: WorkspaceViewModel.DEFAULT_CONTEXT_WINDOW_TOKENS
    val maxSafeChars = viewModel.maxSafeInputChars(modelContextTokens)
    val isOverBudget = totalInputChars > maxSafeChars

    val isWaitingPlanApproval = viewModel.pendingPlanApproval != null
    // 压缩进行中：输入框禁用、发送按钮禁用（isStreaming 排除 isCompacting，
    // 使按钮显示发送图标而非停止图标——压缩中不应允许 abort）
    val isCompacting = viewModel.isCompacting
    val isStreaming = viewModel.isWorking && !isWaitingPlanApproval && !isCompacting
    val errorMessage = viewModel.error?.let { stringResource(it.key, *it.args.toTypedArray()) }
    // 一次性轻提示：发送时图片被剔除放行（值为模型名，null=不显示），非错误走 error
    val imageStrippedNotice = viewModel.imageStrippedNotice
    val remoteCapabilityNotice = viewModel.remoteCapabilityNotice
    val hasContent = textValue.text.trim().isNotEmpty() || pendingPastedTexts.isNotEmpty() || pendingImages.isNotEmpty()
    val canSend = hasContent && !isStreaming && !isOverBudget && !isCompacting

    // 未挂会话且未选项目：发送必被拦，提前把要求摆到明面上（醒目引导条 + 高亮项目选择器）
    val needProjectGuide = viewModel.conversationId == null && selectedProjectId == null
    // 项目菜单打开计数（一次性命令"打开项目菜单"的本地落位）：打开请求经 VM effects
    // Channel 派发，这里 collect 后递增计数（原 projectMenuOpenRequest 计数器语义），
    // 触发 ProjectSelectorMenu 展开。两个 ChatInputCard 实例共享同一 VM/Channel，
    // receiveAsFlow 是单消费者——多实例同时可见时存在广播竞争的理论风险，但实际 UI 中
    // 同一时刻一般只有一个输入框可见，风险极低（原行为是各自 remember 独立计数）。
    var projectMenuOpenCount by remember { mutableStateOf(0) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is UiEffect.OpenProjectMenu) projectMenuOpenCount++
        }
    }

    // 状态变化日志
    val lastStreaming = remember { mutableStateOf(false) }
    if (lastStreaming.value != isStreaming) {
        DebugLog.event("UI", "ChatInputCard state: isStreaming=$isStreaming, canSend=$canSend")
        lastStreaming.value = isStreaming
    }

    // 发送 / 停止 / 排队 动作（Enter 键与发送按钮共用）
    val submit: () -> Unit = {
        if (isStreaming) {
            if (hasContent) {
                val rawMsg = textValue.text.trim()
                val msg = rawMsg.replace("\\/", "/")
                DebugLog.data("UI", "ChatInputCard enqueue msg", "'$msg', pasted=${pendingPastedTexts.size}, images=${pendingImages.size}")
                viewModel.enqueueCurrentInput(msg)
            } else {
                viewModel.abort()
            }
        } else if (canSend) {
            val rawMsg = textValue.text.trim()
            // 解转义 \/ -> /，支持用户通过 \/skill 明确输入字面量字符串而不触发冲突
            val msg = rawMsg.replace("\\/", "/")
            DebugLog.data("UI", "ChatInputCard submit msg", "'$msg', pasted=${pendingPastedTexts.size}, images=${pendingImages.size}")
            viewModel.clearInputDraft()
            viewModel.send(msg)
        }
    }

    // 剪贴板图片统一入口（附件按钮与 Ctrl/Cmd+V 拦截共用）：读取→附加→日志
    // 门禁在 WorkspaceViewModel.tryAttachImage（模型不支持图片时拒绝入列并给可见反馈）
    val attachClipboardImageIfPresent: (String) -> Boolean = { logEvent ->
        val img = PlatformClipboard.getImage()
        if (img != null) {
            viewModel.tryAttachImage(
                name = "image_${xyz.mederi.currentTimeMillis()}.png",
                mimeType = img.mimeType,
                bytes = img.bytes,
                width = img.width,
                height = img.height
            )
            DebugLog.event("UI", "$logEvent, size=${img.bytes.size}")
        } else if (!isDesktopPlatform) {
            // 遥控端（iOS/Android/wasmJs）无剪贴板读权限：不静默，给一次性轻提示
            viewModel.showRemoteCapabilityNotice(RemoteCapabilityNotice.IMAGE_CLIPBOARD)
        }
        img != null
    }

    val onAttachImage: () -> Unit = {
        attachClipboardImageIfPresent("attached image from clipboard")
    }

    val onAttachPastedText: () -> Unit = {
        val clipboardText = PlatformClipboard.getText()
        if (clipboardText != null && clipboardText.isNotBlank()) {
            viewModel.addPastedText(clipboardText)
            DebugLog.event("UI", "attached text from clipboard, len=${clipboardText.length}")
        } else if (!isDesktopPlatform) {
            // 遥控端（iOS/Android/wasmJs）无剪贴板读权限：不静默，给一次性轻提示
            viewModel.showRemoteCapabilityNotice(RemoteCapabilityNotice.TEXT_CLIPBOARD)
        }
    }

    if (isMobileSheetOpen) {
        MobileModelBottomSheet(
            viewModel = viewModel,
            onDismiss = { isMobileSheetOpen = false }
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 需要先选项目：发送前给出醒目提示条（替代"点发送才报错"的滞后反馈）
        if (needProjectGuide) {
            val guideColor = colors.accentWarning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(guideColor.copy(alpha = 0.12f))
                    .border(1.dp, guideColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { viewModel.openProjectMenu() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Folder,
                    contentDescription = null,
                    tint = guideColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (projects.isEmpty()) stringResource(Res.string.input_project_needed_create)
                           else stringResource(Res.string.input_project_needed_select),
                    color = guideColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = guideColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // 错误显示板（ErrorBoard）：错误/警告唯一出口，平时不占空间，点击展开详细报告
        // 断流（isStreamInterrupted）时显示"继续"按钮——重发 Continue 续写半截回复
        ErrorBoard(
            errorMessage = errorMessage,
            onShowDetail = viewModel::showErrorDetail,
            onDismiss = viewModel::clearError,
            showContinue = viewModel.isStreamInterrupted,
            onContinue = viewModel::continueAfterInterruption,
            modifier = Modifier.fillMaxWidth(),
        )

        // 对话框顶部排队信息展示：点击展开查看排队详情，可点击立即发送（引导模式）
        val queuedMessages = viewModel.currentQueuedMessages
        if (queuedMessages.isNotEmpty()) {
            QueuedMessagesBanner(
                queuedMessages = queuedMessages,
                onSteer = viewModel::steerQueuedMessage,
                onRemove = viewModel::removeQueuedMessage,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 快捷命令浮层（分体式独立卡片：位于输入框卡片上方，保持间隔）
        AnimatedVisibility(
            visible = slashMenuOpen,
            enter = expandVertically(tween(160)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(120)) + fadeOut(tween(120))
        ) {
            SlashCommandMenu(
                items = filteredSlashCommands,
                selectedIndex = slashSelectedIndex,
                onSelect = onSelectCommand,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 输入卡片（聚焦 ring：常驻 1dp 透明外环预留避免聚焦抖动；聚焦时外环 accentFocus + 卡片自身 border 同步切换）
        Box(
            modifier = Modifier
                .border(
                    1.dp,
                    if (focused) colors.accentFocus else Color.Transparent,
                    RoundedCornerShape(MederiRadius.Card)
                )
                .padding(1.dp)
        ) {
            Card(
                shape = RoundedCornerShape(MederiRadius.Card),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (focused) colors.accentFocus else colors.surfaceCardBorder
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
            ) {
                // 第一层：输入框顶部附件缩略图/卡片区（对标设计图，置于 TextField 正上方）
                if (pendingPastedTexts.isNotEmpty() || pendingImages.isNotEmpty()) {
                    ChatInputAttachments(
                        pendingImages = pendingImages,
                        pendingPastedTexts = pendingPastedTexts,
                        modelSupportsImages = modelSupportsImages,
                        colors = colors,
                        onRemoveImage = { viewModel.removeImage(it) },
                        onRemovePastedText = { viewModel.removePastedText(it) },
                        onOpenImage = { title, img -> viewModel.openImageInExtension(title, img.base64DataUrl) },
                        onOpenPastedText = { title, item ->
                            viewModel.openTextInExtension(
                                title = title,
                                content = item.text,
                                lineCount = item.lineCount,
                                charCount = item.charCount
                            )
                        }
                    )
                }

                if (isOverBudget) {
                    Text(
                        text = stringResource(Res.string.input_over_budget, totalInputChars, maxSafeChars),
                        color = colors.accentDanger,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 图片已剔除轻提示（非错误样式）：发送时模型不支持图片、图片被剔除放行后显示；
                // 下次输入/发送或点右上角 × 后消失（VM imageStrippedNotice 一次性语义）
                imageStrippedNotice?.let { modelName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentSecondary.copy(alpha = 0.15f))
                            .padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.image_stripped_notice, modelName),
                            color = colors.accentSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(Res.string.close),
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.dismissImageStrippedNotice() }
                                .padding(2.dp)
                        )
                    }
                }

                // 遥控端剪贴板不可用轻提示（非错误样式）：点附件按钮/粘贴文本时剪贴板无权限；
                // 下次输入或点右上角 × 后消失（VM remoteCapabilityNotice 一次性语义）
                remoteCapabilityNotice?.let { notice ->
                    val noticeText = when (notice) {
                        RemoteCapabilityNotice.IMAGE_CLIPBOARD ->
                            stringResource(Res.string.remote_clipboard_image_unavailable)
                        RemoteCapabilityNotice.TEXT_CLIPBOARD ->
                            stringResource(Res.string.remote_clipboard_text_unavailable)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentSecondary.copy(alpha = 0.15f))
                            .padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = noticeText,
                            color = colors.accentSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(Res.string.close),
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.dismissRemoteCapabilityNotice() }
                                .padding(2.dp)
                        )
                    }
                }

                // 第二层：输入文本区域（最小高度 44dp）
                TextField(
                    value = textValue,
                    onValueChange = onSlashCommandTextChange,
                    enabled = !isCompacting,
                    visualTransformation = remember(colors) {
                        SlashCommandTransformation(
                            highlightColor = colors.accentPrimary,
                            hintColor = colors.textMuted
                        )
                    },
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp
                    ),
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.input_placeholder),
                            color = colors.textSecondary,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accentPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 160.dp)
                        .focusRequester(imeFocusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onGloballyPositioned { coords: LayoutCoordinates ->
                            val pos = coords.positionInWindow()
                            imeRect = Rect(
                                pos.x,
                                pos.y,
                                pos.x + coords.size.width.toFloat(),
                                pos.y + coords.size.height.toFloat()
                            )
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            // 在 KeyDown 阶段处理：此时修饰键状态可靠（KeyUp 时 macOS 的 isMetaPressed 不可靠）
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                // 1. 快捷命令联想弹窗打开时的键盘导航与自动完整补全
                                if (slashMenuOpen && filteredSlashCommands.isNotEmpty()) {
                                    when (keyEvent.key) {
                                        Key.DirectionDown -> {
                                            slashSelectedIndex = (slashSelectedIndex + 1) % filteredSlashCommands.size
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.DirectionUp -> {
                                            slashSelectedIndex = if (slashSelectedIndex <= 0) filteredSlashCommands.size - 1 else slashSelectedIndex - 1
                                            return@onPreviewKeyEvent true
                                        }
                                        Key.Enter, Key.Tab -> {
                                            val target = filteredSlashCommands.getOrNull(slashSelectedIndex)
                                            if (target != null) {
                                                DebugLog.data("UI", "key confirm autocomplete", "target=${target.id}, query=$slashQuery")
                                                onSelectCommand(target)
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        Key.Escape -> {
                                            slashMenuDismissed = true
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                }

                                // 2. 退格键（Backspace）原子化删除整个命令 Token（支持开头与末尾），而非逐个字符删除
                                if (keyEvent.key == Key.Backspace && textValue.selection.collapsed) {
                                    val cursor = textValue.selection.start
                                    val tokenRange = SlashCommandRegistry.findCommandTokenAtCursor(textValue.text, cursor, allSlashCommands)
                                    if (tokenRange != null) {
                                        val newText = textValue.text.removeRange(tokenRange.start, tokenRange.end)
                                        val newCursor = tokenRange.start
                                        DebugLog.data("UI", "Atomic command token deleted", "deletedToken='${tokenRange.token}', cursor=$cursor, newText='$newText'")
                                        viewModel.updateInputDraft(TextFieldValue(newText, TextRange(newCursor)))
                                        slashMenuDismissed = true
                                        return@onPreviewKeyEvent true
                                    }
                                }

                                if (keyEvent.key == Key.Enter) {
                                    when {
                                        keyEvent.isShiftPressed || keyEvent.isCtrlPressed || keyEvent.isMetaPressed -> {
                                            // Shift/Ctrl/Cmd+Enter：在光标处插入换行，并把光标移到新行行首
                                            val sel = textValue.selection
                                            val newText = textValue.text.replaceRange(sel.min, sel.max, "\n")
                                            viewModel.updateInputDraft(TextFieldValue(newText, TextRange(sel.min + 1)))
                                            true
                                        }
                                        isDesktopPlatform -> {
                                            if (canSend) {
                                                DebugLog.event("UI", "keyboard Enter: SEND")
                                                submit()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                } else if (keyEvent.key == Key.V && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)) {
                                    // 剪贴板拦截：先检查是否有图片
                                    if (attachClipboardImageIfPresent("pasted image intercepted")) {
                                        true
                                    } else {
                                        val clipboardText = PlatformClipboard.getText()
                                        if (clipboardText != null && PromptComposer.isLargeText(clipboardText)) {
                                            viewModel.addPastedText(clipboardText)
                                            DebugLog.event("UI", "pasted large text intercepted, len=${clipboardText.length}")
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        }
                )

                // wasmJs IME 桥接（其他平台 no-op）：透明 textarea 跟随输入框矩形，回灌输入/反向同步
                WasmImeBridge(
                    textValue = textValue,
                    enabled = !isCompacting,
                    onValueChange = onSlashCommandTextChange,
                    onFocusChange = { imeFocus = it },
                    rectPx = imeRect,
                    density = imeDensity,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 第二层：工具栏（自适应换行排版）
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isCompact = maxWidth < 640.dp
                    DebugLog.data(
                        "UI", "ChatInputCard toolbar layout",
                        "maxWidth=$maxWidth, layoutMode=${if (isCompact) "STACKED_FLOW" else "SINGLE_LINE"}, model=${selectedModel?.name}"
                    )

                    if (isCompact) {
                        // 挤压模式：提高输入框高度，换行排列控件，控件尺寸固定且不缺失任何功能项
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 第一行：上下文控制组（项目选择、附件、图片、自动审批）
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ProjectSelectorMenu(
                                    onOpenProjectPicker = onOpenProjectPicker,
                                    onSelect = { viewModel.selectProject(it) },
                                    highlight = needProjectGuide,
                                    openRequest = projectMenuOpenCount
                                )
                                IconToolButton(icon = FeatherIcons.Paperclip, onClick = onAttachPastedText, size = 28)
                                if (modelSupportsImages) {
                                    IconToolButton(icon = FeatherIcons.Image, onClick = onAttachImage, size = 28)
                                }
                                AgentModeSelector(viewModel = viewModel)
                            }

                            // 第二行：模型配置与主操作组（API Key、模型、思考等级、发送/停止按钮）
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ApiKeySelectorMenu(viewModel = viewModel)
                                ModelSelectorMenu(viewModel = viewModel, compact = false)
                                AnimatedVisibility(
                                    visible = selectedModel?.supportsThinking == true && (selectedModel?.reasoningLevels?.isNotEmpty() == true),
                                    enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                                    exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180))
                                ) {
                                    ThinkingLevelMenu(viewModel = viewModel)
                                }
                                SendButton(
                                    size = 28.dp,
                                    isStreaming = isStreaming,
                                    hasContent = hasContent,
                                    canSend = canSend,
                                    onSubmit = submit
                                )
                            }
                        }
                    } else {
                        // 宽屏模式：单行两端对齐排版
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：项目选择器 + 附件 + 图片 + 执行策略
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProjectSelectorMenu(
                                    onOpenProjectPicker = onOpenProjectPicker,
                                    onSelect = { viewModel.selectProject(it) },
                                    highlight = needProjectGuide,
                                    openRequest = projectMenuOpenCount
                                )
                                IconToolButton(icon = FeatherIcons.Paperclip, onClick = onAttachPastedText, size = 28)
                                if (modelSupportsImages) {
                                    IconToolButton(icon = FeatherIcons.Image, onClick = onAttachImage, size = 28)
                                }
                                AgentModeSelector(viewModel = viewModel)
                            }

                            // 右侧：模型 / 思考等级 / 发送
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ApiKeySelectorMenu(viewModel = viewModel)
                                ModelSelectorMenu(viewModel = viewModel, compact = false)
                                AnimatedVisibility(
                                    visible = selectedModel?.supportsThinking == true && (selectedModel?.reasoningLevels?.isNotEmpty() == true),
                                    enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                                    exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180))
                                ) {
                                    ThinkingLevelMenu(viewModel = viewModel)
                                }
                                SendButton(
                                    size = 28.dp,
                                    isStreaming = isStreaming,
                                    hasContent = hasContent,
                                    canSend = canSend,
                                    onSubmit = submit
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
 * 对话框上方排队消息横条：
 * 支持展开查看排队详情，提供"立即发送"（触发引导模式注入执行）和"移出队列"按钮。
 */
@Composable
internal fun QueuedMessagesBanner(
    queuedMessages: List<QueuedMessage>,
    onSteer: (QueuedMessage) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val colors = LocalMederiColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Banner 头部：排队条数与展开/收起切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Clock,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(Res.string.queue_banner_title, queuedMessages.size),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
        }

        // 展开列表
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                queuedMessages.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceCardBorder.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${index + 1}.",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            if (item.text.isNotBlank()) {
                                Text(
                                    text = item.text,
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.images.isNotEmpty() || item.pastedTexts.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (item.images.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = FeatherIcons.Image,
                                                contentDescription = null,
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Text(
                                                text = "${item.images.size}",
                                                color = colors.textSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    if (item.pastedTexts.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = FeatherIcons.Paperclip,
                                                contentDescription = null,
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Text(
                                                text = "${item.pastedTexts.size}",
                                                color = colors.textSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 立即发送（引导模式按钮）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.accentPrimary.copy(alpha = 0.16f))
                                .border(1.dp, colors.accentPrimary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .clickable { onSteer(item) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.ArrowUp,
                                contentDescription = stringResource(Res.string.queue_steer_tooltip),
                                tint = colors.accentPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = stringResource(Res.string.queue_send_now),
                                color = colors.accentPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 移出队列按钮
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(Res.string.queue_remove),
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .clickable { onRemove(item.id) }
                        )
                    }
                }
            }
        }
    }
}
