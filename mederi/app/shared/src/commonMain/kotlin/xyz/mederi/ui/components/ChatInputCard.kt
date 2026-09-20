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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.auto_approve_title
import mederi.app.shared.generated.resources.auto_approve_tooltip_main
import mederi.app.shared.generated.resources.auto_approve_tooltip_note
import mederi.app.shared.generated.resources.close
import mederi.app.shared.generated.resources.image_stripped_notice
import mederi.app.shared.generated.resources.input_default_key
import mederi.app.shared.generated.resources.input_default_tag
import mederi.app.shared.generated.resources.input_image_n
import mederi.app.shared.generated.resources.input_image_unsupported
import mederi.app.shared.generated.resources.input_model_mode_settings
import mederi.app.shared.generated.resources.input_no_project
import mederi.app.shared.generated.resources.input_open_project
import mederi.app.shared.generated.resources.input_over_budget
import mederi.app.shared.generated.resources.input_pending_image_thumbnail
import mederi.app.shared.generated.resources.input_pasted_text_n
import mederi.app.shared.generated.resources.input_placeholder
import mederi.app.shared.generated.resources.input_project_needed_create
import mederi.app.shared.generated.resources.input_project_needed_select
import mederi.app.shared.generated.resources.input_provider_default
import mederi.app.shared.generated.resources.input_reasoning_level
import mederi.app.shared.generated.resources.input_remove_image
import mederi.app.shared.generated.resources.input_remove_text
import mederi.app.shared.generated.resources.input_select_model
import mederi.app.shared.generated.resources.input_select_project
import mederi.app.shared.generated.resources.input_selected
import mederi.app.shared.generated.resources.input_send
import mederi.app.shared.generated.resources.input_stop
import mederi.app.shared.generated.resources.input_text_meta
import mederi.app.shared.generated.resources.input_text_n
import mederi.app.shared.generated.resources.input_thinking_label
import mederi.app.shared.generated.resources.reasoning_level_high
import mederi.app.shared.generated.resources.reasoning_level_low
import mederi.app.shared.generated.resources.reasoning_level_max
import mederi.app.shared.generated.resources.reasoning_level_medium
import mederi.app.shared.generated.resources.reasoning_level_none
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import compose.icons.feathericons.Key
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Square
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
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.ui.DebugLog
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import xyz.mederi.isDesktopPlatform
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.util.formatContextWindow

/** 推理档位显示名（唯一映射点，桌面下拉与移动端抽屉共用；未知档位回显原始值）。 */
@Composable
fun formatReasoningLevelLabel(level: String): String = when (level.uppercase()) {
    "LOW" -> stringResource(Res.string.reasoning_level_low)
    "MEDIUM" -> stringResource(Res.string.reasoning_level_medium)
    "HIGH" -> stringResource(Res.string.reasoning_level_high)
    "MAX" -> stringResource(Res.string.reasoning_level_max)
    "NONE", "OFF" -> stringResource(Res.string.reasoning_level_none)
    else -> level
}

/** 模型能力小标签（Thinking / Image），桌面下拉与移动端抽屉共用。具有防折行与精致描边。 */
@Composable
private fun ModelCapabilityTag(text: String, tint: Color) {
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
    // 一次性轻提示：发送时图片被剔除放行（值为模型名，null=不显示），非错误走 error
    val imageStrippedNotice = viewModel.imageStrippedNotice
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
                            val imageTitle = stringResource(Res.string.input_image_n, i + 1)
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceInput)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.openImageInExtension(imageTitle, img.base64DataUrl)
                                    }
                            ) {
                                InkImage(
                                    model = img.base64DataUrl,
                                    contentDescription = stringResource(Res.string.input_pending_image_thumbnail),
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
                                        contentDescription = stringResource(Res.string.input_remove_image),
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }

                        pendingPastedTexts.forEach { item ->
                            val pastedTitle = stringResource(Res.string.input_pasted_text_n, item.index)
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .widthIn(min = 120.dp, max = 180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceInput)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.openTextInExtension(
                                            title = pastedTitle,
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
                                            text = stringResource(Res.string.input_text_n, item.index),
                                            color = colors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = stringResource(Res.string.input_text_meta, item.lineCount, item.charCount),
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
                                        contentDescription = stringResource(Res.string.input_remove_text),
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
                            text = stringResource(Res.string.input_image_unsupported),
                            color = colors.accentWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
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
                            text = stringResource(Res.string.input_placeholder),
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
                                ApiKeySelectorMenu(viewModel = viewModel)
                                ChipSelectorPill(
                                    icon = FeatherIcons.Cpu,
                                    label = (selectedModel?.name ?: stringResource(Res.string.input_select_model)).take(12),
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
            label = selectedModel?.name ?: stringResource(Res.string.input_select_model),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(min = if (compact) 400.dp else 500.dp, max = if (compact) 480.dp else 560.dp)
                .heightIn(max = 480.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
        ) {
            groupedModels.entries.forEachIndexed { groupIndex, (providerId, providerModels) ->
                val providerDisplayName = (providerNameMap[providerId] ?: providerId).uppercase()
                if (groupIndex > 0) {
                    HorizontalDivider(color = colors.divider.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(FeatherIcons.Cpu, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                        Text(
                            text = providerDisplayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textMuted,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${providerModels.size}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textMuted.copy(alpha = 0.6f)
                    )
                }
                providerModels.forEach { model ->
                    val isSelected = selectedModel?.id == model.id
                    val contextSizeStr = formatContextWindow(model.contextWindow)
                    DropdownMenuItem(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent),
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(
                                        text = model.name,
                                        fontSize = if (compact) 12.sp else 12.5.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (model.supportsThinking) ModelCapabilityTag("Thinking", colors.thoughtAccent)
                                    if (model.supportsImages) ModelCapabilityTag("Image", colors.accentSecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (contextSizeStr != null) {
                                        Text(
                                            text = contextSizeStr,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isSelected) colors.textPrimary else colors.textSecondary
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = FeatherIcons.Check,
                                            contentDescription = stringResource(Res.string.input_selected),
                                            tint = colors.accentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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

/**
 * API Key 选择器（模型选择器左侧）。**派生自选中模型所在供应商**：读该 ProviderConfig 的 apiKeys，
 * 当前选中项 = AppState.selectedApiKeyIds[provider.id]（缺省 = 用默认 key）。
 * 选择写入 AppState（按供应商记忆，跨重启恢复）。
 */
@Composable
private fun ApiKeySelectorMenu(viewModel: WorkspaceViewModel) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val providers by appState.providers.collectAsState()
    val selectedModel by appState.selectedModel.collectAsState()
    val selectedApiKeys by appState.selectedApiKeyIds.collectAsState()

    // 关联：选中模型 → 其供应商 → 该供应商的 apiKeys。无供应商或无 key 时不显示选择器
    val provider = selectedModel?.let { m -> providers.find { it.id == m.provider } }
    val keys = provider?.apiKeys.orEmpty()
    if (provider == null || keys.isEmpty()) return

    val providerId = provider.id
    val selectedId = selectedApiKeys[providerId]
    val selectedKey = keys.find { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Key,
            label = selectedKey?.name ?: stringResource(Res.string.input_default_key),
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceCard,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
        ) {
            // 供应商默认 key（未选定）
            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.input_provider_default),
                            fontSize = 12.sp,
                            fontWeight = if (selectedId == null) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selectedId == null) colors.accentPrimary else colors.textPrimary
                        )
                        if (selectedId == null) {
                            Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp))
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                onClick = {
                    viewModel.selectApiKey(providerId, null)
                    expanded = false
                }
            )
            if (keys.isNotEmpty()) HorizontalDivider(color = colors.divider.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            keys.forEach { key ->
                val isSelected = selectedId == key.id
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f, fill = false)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = key.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (key.isDefault) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(colors.accentSecondary.copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(stringResource(Res.string.input_default_tag), fontSize = 9.sp, color = colors.accentSecondary, fontWeight = FontWeight.Medium, maxLines = 1)
                                        }
                                    }
                                }
                                Text(
                                    text = key.maskedValue,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(FeatherIcons.Check, null, tint = colors.accentPrimary, modifier = Modifier.size(13.dp))
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        viewModel.selectApiKey(providerId, key.id)
                        expanded = false
                    }
                )
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
    val currentLevel = viewModel.effectiveThinkingLevel.collectAsState().value ?: return
    if (currentLevel.isBlank()) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        ChipSelectorPill(
            icon = FeatherIcons.Sliders,
            label = stringResource(Res.string.input_thinking_label, formatReasoningLevelLabel(currentLevel)),
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
                label = selectedProject?.name ?: stringResource(Res.string.input_select_project),
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
                    text = { Text(stringResource(Res.string.input_no_project), fontSize = 11.5.sp, color = colors.textSecondary) },
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
                        Text(stringResource(Res.string.input_open_project), fontSize = 11.5.sp, color = colors.accentPrimary, fontWeight = FontWeight.Medium)
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

/** 自动审批开关与说明气泡。选中态订阅 AppState 派生流（唯一真理源）。 */
@Composable
private fun AgentModeSelector(viewModel: WorkspaceViewModel) {
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    val isAutoApprove = selectedAgentMode == AgentMode.AUTONOMOUS
    val colors = LocalMederiColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                viewModel.selectAgentMode(if (isAutoApprove) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(Res.string.auto_approve_title),
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
        HelpCircleTooltip(
            mainText = stringResource(Res.string.auto_approve_tooltip_main),
            noteText = stringResource(Res.string.auto_approve_tooltip_note),
            touchTargetSize = 20.dp,
            iconSize = 13.dp
        )
        AutoApproveSwitch(
            viewModel = viewModel,
            scale = 0.65f,
            switchWidth = 30.dp,
            switchHeight = 20.dp
        )
    }
}

/**
 * 自动审批开关（桌面输入条与移动端设置抽屉共用）。
 * 唯一真理源 = viewModel.selectedAgentMode，写入走 selectAgentMode。
 */
@Composable
private fun AutoApproveSwitch(
    viewModel: WorkspaceViewModel,
    scale: Float,
    switchWidth: Dp,
    switchHeight: Dp,
) {
    val selectedAgentMode by viewModel.selectedAgentMode.collectAsState()
    val colors = LocalMederiColors.current
    Switch(
        checked = selectedAgentMode == AgentMode.AUTONOMOUS,
        onCheckedChange = { checked ->
            viewModel.selectAgentMode(if (checked) AgentMode.AUTONOMOUS else AgentMode.APPROVAL)
        },
        modifier = Modifier
            .scale(scale)
            .size(width = switchWidth, height = switchHeight),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accentPrimary,
            uncheckedThumbColor = colors.textMuted,
            uncheckedTrackColor = colors.buttonSecondary
        )
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
            Icon(FeatherIcons.Square, stringResource(Res.string.input_stop), tint = iconColor, modifier = Modifier.size(size * 0.37f))
        } else {
            Icon(FeatherIcons.ArrowUp, stringResource(Res.string.input_send), tint = iconColor, modifier = Modifier.size(size * 0.48f))
        }
    }
}

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
                        text = stringResource(Res.string.input_model_mode_settings),
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(Res.string.close),
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
                    // 1. 自动审批开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.auto_approve_title),
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            HelpCircleTooltip(
                                mainText = stringResource(Res.string.auto_approve_tooltip_main),
                                noteText = stringResource(Res.string.auto_approve_tooltip_note),
                                touchTargetSize = 24.dp,
                                iconSize = 14.dp
                            )
                        }
                        AutoApproveSwitch(
                            viewModel = viewModel,
                            scale = 0.8f,
                            switchWidth = 36.dp,
                            switchHeight = 22.dp
                        )
                    }

                    // 2. 推理等级选择（条件显示：仅当前模型支持推理且生效档位已推导出时展示）
                    val sheetModel = selectedModel
                    val sheetCurrentLevel = currentLevel
                    if (sheetModel?.supportsThinking == true && sheetModel.reasoningLevels.isNotEmpty() && sheetCurrentLevel != null) {
                        val levels = sheetModel.reasoningLevels
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
                                    text = stringResource(Res.string.input_reasoning_level),
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
                                val isSelected = sheetCurrentLevel.equals(lvl, ignoreCase = true)
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
                            text = stringResource(Res.string.input_select_model),
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Icon(FeatherIcons.Cpu, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                                        Text(
                                            text = providerDisplayName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textMuted,
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${providerModels.size}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textMuted.copy(alpha = 0.6f)
                                    )
                                }
                                providerModels.forEach { model ->
                                    val isSelected = selectedModel?.id == model.id
                                    val contextSizeStr = formatContextWindow(model.contextWindow)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable {
                                                viewModel.selectModel(model)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            Text(
                                                text = model.name,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (isSelected) colors.accentPrimary else colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (model.supportsThinking) {
                                                ModelCapabilityTag("Thinking", colors.thoughtAccent)
                                            }
                                            if (model.supportsImages) {
                                                ModelCapabilityTag("Image", colors.accentSecondary)
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (contextSizeStr != null) {
                                                Text(
                                                    text = contextSizeStr,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (isSelected) colors.textPrimary else colors.textMuted
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = FeatherIcons.Check,
                                                    contentDescription = stringResource(Res.string.input_selected),
                                                    tint = colors.accentPrimary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(15.dp))
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
