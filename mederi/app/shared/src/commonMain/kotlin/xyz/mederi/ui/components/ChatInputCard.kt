package xyz.mederi.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Image
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Square
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
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
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.util.formatContextWindow

fun formatReasoningLevelLabel(level: String): String = when (level.uppercase()) {
    "LOW" -> "低"
    "MEDIUM" -> "中"
    "HIGH" -> "高"
    "MAX" -> "最大"
    "NONE", "OFF" -> "关闭"
    else -> level
}

/** 模型能力小标签（Thinking / Image），桌面下拉与移动端抽屉共用。 */
@Composable
private fun ModelCapabilityTag(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
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
    var isMobileSheetOpen by remember { mutableStateOf(false) }

    val selectedModel by appState.selectedModel.collectAsState()
    // 图片能力唯一推导（VM 派生流）：按钮显隐/警告/门禁全同源，UI 禁止手写 supportsImages 判断
    val modelSupportsImages by viewModel.modelSupportsImages.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val projects by appState.projects.collectAsState()

    val pendingPastedTexts = viewModel.pendingPastedTexts
    val pendingImages = viewModel.pendingImages

    // 容量预算检查（业务规则在 WorkspaceViewModel，UI 只计算展示）
    val totalPastedChars = pendingPastedTexts.sumOf { it.charCount }
    val totalInputChars = textValue.text.length + totalPastedChars
    val modelContextTokens = selectedModel?.contextWindow ?: WorkspaceViewModel.DEFAULT_CONTEXT_WINDOW_TOKENS
    val maxSafeChars = viewModel.maxSafeInputChars(modelContextTokens)
    val isOverBudget = totalInputChars > maxSafeChars

    val isWaitingPlanApproval = viewModel.pendingPlanApproval != null
    val isStreaming = viewModel.isWorking && !isWaitingPlanApproval
    val errorMessage = viewModel.error
    val hasContent = textValue.text.trim().isNotEmpty() || pendingPastedTexts.isNotEmpty() || pendingImages.isNotEmpty()
    val canSend = hasContent && !isStreaming && !isOverBudget

    // 未挂会话且未选项目：发送必被拦，提前把要求摆到明面上（醒目引导条 + 高亮项目选择器）
    val needProjectGuide = viewModel.conversationId == null && selectedProjectId == null
    var projectMenuOpenRequest by remember { mutableStateOf(0) }

    // 状态变化日志
    val lastStreaming = remember { mutableStateOf(false) }
    if (lastStreaming.value != isStreaming) {
        DebugLog.event("UI", "ChatInputCard state: isStreaming=$isStreaming, canSend=$canSend")
        lastStreaming.value = isStreaming
    }

    // 发送 / 停止 动作（Enter 键与发送按钮共用）
    val submit: () -> Unit = {
        if (isStreaming) {
            viewModel.abort()
        } else if (canSend) {
            val msg = textValue.text.trim()
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
                    .clickable { projectMenuOpenRequest++ },
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
                    text = if (projects.isEmpty()) "还没有项目：点击创建一个项目后才能开始对话"
                           else "先选择一个项目才能开始对话，点击此处选择",
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

        // 错误提示条：平时不占空间，有错误时显示红色文字
        if (errorMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentDanger.copy(alpha = 0.15f))
                    .border(1.dp, colors.accentDanger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = errorMessage,
                        color = colors.accentDanger,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = "关闭",
                    tint = colors.accentDanger,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { viewModel.clearError() }
                )
            }
        }

        // 输入卡片
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp)
            ) {
                // 第一层：输入框顶部附件缩略图/卡片区（对标设计图，置于 TextField 正上方）
                if (pendingPastedTexts.isNotEmpty() || pendingImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingImages.forEachIndexed { i, img ->
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceInput)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.openImageInExtension("图片 #${i + 1}", img.base64DataUrl)
                                    }
                            ) {
                                InkImage(
                                    model = img.base64DataUrl,
                                    contentDescription = "待发送图片缩略图",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // 右上角浮动微型删除按钮
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable { viewModel.removeImage(img.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.X,
                                        contentDescription = "移除图片",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }

                        pendingPastedTexts.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .widthIn(min = 120.dp, max = 180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceInput)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.openTextInExtension(
                                            title = "粘贴文本 #${item.index}",
                                            content = item.text,
                                            lineCount = item.lineCount,
                                            charCount = item.charCount
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(end = 14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = FeatherIcons.File,
                                            contentDescription = null,
                                            tint = colors.accentSecondary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "文本 #${item.index}",
                                            color = colors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${item.lineCount}行 · ${item.charCount}字",
                                        color = colors.textMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }

                                // 右上角浮动微型删除按钮
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .clickable { viewModel.removePastedText(item.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.X,
                                        contentDescription = "移除文本",
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = colors.divider.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    // 图片门禁内联提示：挂了图片但当前模型不支持（如切换模型后），发送会被拦截
                    if (pendingImages.isNotEmpty() && !modelSupportsImages) {
                        Text(
                            text = "当前模型不支持图片输入，发送前请移除图片或切换模型",
                            color = colors.accentWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                if (isOverBudget) {
                    Text(
                        text = "内容总计约 $totalInputChars 字符，超出单次安全预算（上限 $maxSafeChars 字符），请裁剪后发送",
                        color = colors.accentDanger,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 第二层：输入文本区域（最小高度 44dp）
                TextField(
                    value = textValue,
                    onValueChange = { viewModel.updateInputDraft(it) },
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp
                    ),
                    placeholder = {
                        Text(
                            text = "输入消息...",
                            color = colors.textMuted,
                            fontSize = 13.5.sp,
                            lineHeight = 21.sp
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
                        .heightIn(min = 44.dp, max = 150.dp)
                        .onPreviewKeyEvent { keyEvent ->
                            // 在 KeyDown 阶段处理：此时修饰键状态可靠（KeyUp 时 macOS 的 isMetaPressed 不可靠）
                            if (keyEvent.type == KeyEventType.KeyDown) {
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

                Spacer(modifier = Modifier.height(10.dp))

                // 第二层：工具栏（移动端/桌面端自适应排版）
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isCompact = maxWidth < 540.dp

                    if (isCompact) {
                        // 移动端：第二层独立单行，分左右两组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：单次操作图标化组（40×40dp 触控区，横向可滚动）
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProjectSelectorMenu(
                                    onOpenProjectPicker = onOpenProjectPicker,
                                    onSelect = { viewModel.selectProject(it) },
                                    iconOnly = true,
                                    highlight = needProjectGuide,
                                    openRequest = projectMenuOpenRequest
                                )
                                IconToolButton(icon = FeatherIcons.Paperclip, onClick = onAttachPastedText, size = 40)
                                // 图片门禁：仅支持图片输入的模型显示附件按钮（粘贴路径由 tryAttachImage 拦截）
                                if (modelSupportsImages) {
                                    IconToolButton(icon = FeatherIcons.Image, onClick = onAttachImage, size = 40)
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // 右侧：模型选择药丸（点击唤起抽屉） + 独立高亮发送圆钮
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ChipSelectorPill(
                                    icon = FeatherIcons.Cpu,
                                    label = (selectedModel?.name ?: "选择模型").take(12),
                                    onClick = { isMobileSheetOpen = true },
                                    height = 40.dp
                                )
                                SendButton(
                                    size = 40.dp,
                                    isStreaming = isStreaming,
                                    canSend = canSend,
                                    onSubmit = submit
                                )
                            }
                        }
                    } else {
                        // 桌面端：单行完整排版
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：项目选择器 + 附件 + 执行策略
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProjectSelectorMenu(
                                    onOpenProjectPicker = onOpenProjectPicker,
                                    onSelect = { viewModel.selectProject(it) },
                                    highlight = needProjectGuide,
                                    openRequest = projectMenuOpenRequest
                                )
                                IconToolButton(icon = FeatherIcons.Paperclip, onClick = onAttachPastedText, size = 28)
                                // 图片门禁：仅支持图片输入的模型显示附件按钮（粘贴路径由 tryAttachImage 拦截）
                                if (modelSupportsImages) {
                                    IconToolButton(icon = FeatherIcons.Image, onClick = onAttachImage, size = 28)
                                }
                                AgentModeSelector(viewModel = viewModel)
                            }

                            // 右侧：模型 / 思考等级（条件显示 + 过渡动画） / 发送
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ModelSelectorMenu(viewModel = viewModel, compact = false)
                                AnimatedVisibility(
                                    visible = selectedModel?.supportsThinking == true && (selectedModel?.reasoningLevels?.isNotEmpty() == true),
                                    enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
                                    exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180))
                                ) {
                                    ThinkingLevelMenu(viewModel = viewModel)
                                }
                                SendButton(
                                    size = 32.dp,
                                    isStreaming = isStreaming,
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

// ==========================================
// 可复用子组件（每个只做一件事，菜单数据从 AppState / ViewModel 读取）
// ==========================================

/** 模型选择器（按供应商分组的下拉菜单）。业务数据来自 AppState，选择写入 ViewModel。 */
@Composable
private fun ModelSelectorMenu(viewModel: WorkspaceViewModel, compact: Boolean) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val models by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    if (models.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val providerNameMap = remember(providers) { providers.associate { it.id to it.name } }
    val groupedModels = remember(models) { models.groupBy { it.provider } }

    Box {
        ChipSelectorPill(
            label = selectedModel?.name ?: "选择模型",
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = if (compact) 260.dp else 280.dp, max = if (compact) 340.dp else 360.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            groupedModels.entries.forEachIndexed { groupIndex, (providerId, providerModels) ->
                val providerDisplayName = (providerNameMap[providerId] ?: providerId).uppercase()
                if (groupIndex > 0) {
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(FeatherIcons.Cpu, null, tint = colors.textMuted, modifier = Modifier.size(13.dp))
                    Text(providerDisplayName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.textMuted, modifier = Modifier.weight(1f))
                }
                providerModels.forEach { model ->
                    val isSelected = selectedModel?.id == model.id
                    val contextSizeStr = formatContextWindow(model.contextWindow)
                    DropdownMenuItem(
                        text = {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(model.name, fontSize = if (compact) 12.5.sp else 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = colors.textPrimary)
                                    if (model.supportsThinking) ModelCapabilityTag("Thinking", colors.thoughtAccent)
                                    if (model.supportsImages) ModelCapabilityTag("Image", colors.accentSecondary)
                                }
                                if (contextSizeStr != null) {
                                    Text(contextSizeStr, fontSize = if (compact) 10.5.sp else 11.sp, color = colors.textSecondary)
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        onClick = {
                            viewModel.selectModel(model)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** 思考/推理等级选择器。选项由 ReasoningMenu 推导（NONE=关闭 恒为首项，其余为有值档位 ∩ 模型勾选）。 */
@Composable
private fun ThinkingLevelMenu(viewModel: WorkspaceViewModel) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val selectedModel by appState.selectedModel.collectAsState()
    val levels = selectedModel?.reasoningLevels ?: emptyList()
    if (levels.isEmpty()) return

    // 唯一真理源：订阅 effectiveThinkingLevel（ReasoningMenu.resolve 推导的派生流）。
    // 模型切换/档位记忆更新自动重算；null（推导链未就绪，正常不会发生）时不渲染，不做兜底回退
    val currentLevel by viewModel.effectiveThinkingLevel.collectAsState()
    if (currentLevel == null) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Sliders,
            label = "推理: ${formatReasoningLevelLabel(currentLevel!!)}",
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 150.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            // NONE（关闭）为 levels 首项（ReasoningMenu 推导），统一渲染
            levels.forEach { lvl ->
                val isSelected = currentLevel.equals(lvl, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatReasoningLevelLabel(lvl),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.accentPrimary else if (lvl == "NONE") colors.textSecondary else colors.textPrimary
                            )
                            if (isSelected) {
                                Icon(
                                    FeatherIcons.Check,
                                    null,
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        viewModel.updateThinkingLevel(lvl)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 项目选择器（下拉菜单，含清空选择与打开新项目目录入口）。选择写入 AppState。 */
@Composable
private fun ProjectSelectorMenu(
    onOpenProjectPicker: () -> Unit,
    onSelect: (String?) -> Unit,
    iconOnly: Boolean = false,
    highlight: Boolean = false,
    openRequest: Int = 0
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val projects by appState.projects.collectAsState()
    val selectedProjectId by appState.selectedProjectId.collectAsState()
    val selectedProject = projects.find { it.id == selectedProjectId }

    var expanded by remember { mutableStateOf(false) }
    // 引导条点击：外部请求打开菜单（openRequest 递增即打开一次）
    LaunchedEffect(openRequest) {
        if (openRequest > 0) expanded = true
    }
    Box {
        if (iconOnly) {
            IconToolButton(
                icon = FeatherIcons.Folder,
                onClick = { expanded = true },
                size = 40
            )
        } else {
            ContextToolChip(
                icon = FeatherIcons.Folder,
                label = selectedProject?.name ?: "选择项目",
                onClick = { expanded = true },
                highlight = highlight
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceSidebar,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(6.dp))
        ) {
            if (selectedProjectId != null) {
                DropdownMenuItem(
                    text = { Text("未选择项目", fontSize = 11.5.sp, color = colors.textSecondary) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                if (projects.isNotEmpty()) HorizontalDivider(color = colors.divider)
            }
            projects.forEach { proj ->
                DropdownMenuItem(
                    text = { Text(proj.name, fontSize = 11.5.sp, color = colors.textPrimary) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    onClick = {
                        onSelect(proj.id)
                        expanded = false
                    }
                )
            }
            if (projects.isNotEmpty()) HorizontalDivider(color = colors.divider)
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(FeatherIcons.Plus, null, tint = colors.accentPrimary, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开项目目录...", fontSize = 11.5.sp, color = colors.accentPrimary, fontWeight = FontWeight.Medium)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                onClick = {
                    expanded = false
                    onOpenProjectPicker()
                }
            )
        }
    }
}

/** 执行策略分段选择器（自主 / 审批）。选中态订阅 AppState 派生流（唯一真理源）。 */
@Composable
private fun AgentModeSelector(viewModel: WorkspaceViewModel) {
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    SegmentedControl(
        items = AGENT_MODE_ITEMS,
        selectedKey = selectedAgentMode,
        onSelect = { mode -> viewModel.selectAgentMode(mode) },
        height = 28.dp
    )
}

/** 发送 / 停止按钮。 */
@Composable
private fun SendButton(
    size: Dp,
    isStreaming: Boolean,
    canSend: Boolean,
    onSubmit: () -> Unit
) {
    val colors = LocalMederiColors.current
    val bgColor = when {
        isStreaming -> colors.accentDanger
        canSend -> colors.accentPrimary
        else -> colors.buttonSecondary
    }
    val iconColor = when {
        isStreaming || canSend -> colors.onAccentPrimary
        else -> colors.textMuted
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, if (canSend) colors.accentPrimary else colors.surfaceCardBorder, CircleShape)
            .clickable(enabled = isStreaming || canSend, onClick = onSubmit),
        contentAlignment = Alignment.Center
    ) {
        if (isStreaming) {
            Icon(FeatherIcons.Square, "停止生成", tint = iconColor, modifier = Modifier.size(size * 0.37f))
        } else {
            Icon(FeatherIcons.ArrowUp, "发送", tint = iconColor, modifier = Modifier.size(size * 0.48f))
        }
    }
}

/** 执行策略分段选项（不可变常量，多处复用） */
private val AGENT_MODE_ITEMS = listOf(
    SegmentItem(AgentMode.AUTONOMOUS, "自主", FeatherIcons.Zap),
    SegmentItem(AgentMode.APPROVAL, "审批", FeatherIcons.Shield)
)

@Composable
private fun ChipSelectorPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    height: Dp = 28.dp,
    onClick: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceInput,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.surfaceCardBorder)
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 11.5.sp,
                lineHeight = 11.5.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

@Composable
private fun IconToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: Int = 28
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colors.buttonSecondary)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(if (size >= 36) 18.dp else (size * 0.5f).dp)
        )
    }
}

@Composable
private fun ContextToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    val colors = LocalMederiColors.current
    val contentColor = if (highlight) colors.accentWarning else colors.textSecondary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) colors.accentWarning.copy(alpha = 0.10f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (highlight) colors.accentWarning.copy(alpha = 0.5f) else colors.surfaceCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                color = contentColor,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/** 移动端底部抽屉（Modal Bottom Sheet）：选择模型、推理等级、执行策略 */
@Composable
private fun MobileModelBottomSheet(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val models by appState.availableModels.collectAsState()
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    val currentLevel by viewModel.effectiveThinkingLevel.collectAsState()
    val providerNameMap = remember(providers) { providers.associate { it.id to it.name } }
    val groupedModels = remember(models) { models.groupBy { it.provider } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceOverlay)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* 拦截点击，防止点击面板内部关闭抽屉 */ }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 顶部拖拽条
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "模型与模式设置",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = "关闭",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 执行策略（自主 / 审批）
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "执行策略",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        SegmentedControl(
                            items = AGENT_MODE_ITEMS,
                            selectedKey = selectedAgentMode,
                            onSelect = { mode -> viewModel.selectAgentMode(mode) },
                            height = 34.dp,
                            equalWeight = true
                        )
                    }

                    // 2. 推理等级选择（条件显示：仅当前模型支持推理且生效档位已推导出时展示）
                    if (selectedModel?.supportsThinking == true && selectedModel?.reasoningLevels?.isNotEmpty() == true && currentLevel != null) {
                        val levels = selectedModel!!.reasoningLevels
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Sliders,
                                    contentDescription = null,
                                    tint = colors.thoughtAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "推理等级 (Reasoning Level)",
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // NONE（关闭）为 levels 首项（ReasoningMenu 推导），统一渲染
                                levels.forEach { lvl ->
                                    val isSelected = currentLevel.equals(lvl, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.15f) else colors.surfaceInput)
                                            .border(
                                                1.dp,
                                                if (isSelected) colors.accentPrimary else colors.surfaceCardBorder,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { viewModel.updateThinkingLevel(lvl) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = formatReasoningLevelLabel(lvl),
                                            color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. 模型列表
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "选择模型",
                            color = colors.textSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )

                        groupedModels.entries.forEach { (providerId, providerModels) ->
                            val providerDisplayName = (providerNameMap[providerId] ?: providerId).uppercase()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceInput)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(FeatherIcons.Cpu, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                                    Text(
                                        providerDisplayName,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textMuted
                                    )
                                }
                                providerModels.forEach { model ->
                                    val isSelected = selectedModel?.id == model.id
                                    val contextSizeStr = formatContextWindow(model.contextWindow)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.selectModel(model)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                model.name,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) colors.accentPrimary else colors.textPrimary
                                            )
                                            if (model.supportsThinking) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(colors.thoughtAccent.copy(alpha = 0.15f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "Thinking",
                                                        color = colors.thoughtAccent,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                            if (model.supportsImages) {
                                                ModelCapabilityTag("Image", colors.accentSecondary)
                                            }
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (contextSizeStr != null) {
                                                Text(contextSizeStr, fontSize = 11.sp, color = colors.textMuted)
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    FeatherIcons.Check,
                                                    null,
                                                    tint = colors.accentPrimary,
                                                    modifier = Modifier.size(15.dp)
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
        }
    }
}
