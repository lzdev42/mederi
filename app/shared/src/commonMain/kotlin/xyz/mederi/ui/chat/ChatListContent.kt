package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.input_image_attachment
import mederi.app.shared.generated.resources.input_image_n
import mederi.app.shared.generated.resources.input_pasted_text_n
import org.jetbrains.compose.resources.stringResource
import xyz.emuci.inkcompose.InkImage
import xyz.emuci.inkcompose.LocalSessionKey
import xyz.emuci.markdown.renderer.SelectionMenuAction
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.WorkspaceViewModel
import xyz.mederi.ui.WorkspaceViewModel.DocumentTarget
import xyz.mederi.ui.components.AssistantMessageFooter
import xyz.mederi.ui.components.DocumentArtifactCard
import xyz.mederi.ui.components.EventMessageCard
import xyz.mederi.ui.components.PlanApprovalCard
import xyz.mederi.ui.components.QuestionCard
import xyz.mederi.ui.components.ReasoningBlock
import xyz.mederi.ui.components.SubagentCallsBlock
import xyz.mederi.ui.components.ToolCallsBlock
import xyz.mederi.ui.components.TurnDiffSummaryCard
import xyz.mederi.ui.components.UserMessageFooter
import xyz.mederi.ui.components.UserPastedTextCard
import xyz.mederi.ui.components.WorkTraceCard
import xyz.mederi.ui.components.atoms.MederiMarkdown
import xyz.mederi.util.PromptComposer

/**
 * 可复用的聊天列表渲染：LazyColumn + ChatListItem when 分发。
 *
 * 主对话（Workspace.MessageList）与子代理对话面板（SubagentConversationPanel）
 * 共用此组件。viewModel 内的 openLink/openDocument/approvePlan/answerQuestion 等
 * 路由对子代理面板同样有效（文档查看器等统一走主工作区）；唯一差异化的是用户
 * 消息底栏的回滚操作——子代理合成消息不可回滚，传 [showUserMessageActions] = false 隐藏。
 */
@Composable
fun ChatListContent(
    viewModel: WorkspaceViewModel,
    chatItems: List<ChatListItem>,
    lazyListState: LazyListState,
    selectionMenuActions: List<SelectionMenuAction>,
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp = ChatLayout.contentMaxWidth,
    isCompact: Boolean = false,
    showUserMessageActions: Boolean = true,
) {
    val colors = LocalMederiColors.current
    CompositionLocalProvider(LocalSessionKey provides viewModel.conversationId) {
        ChatScrollLayout(
            items = chatItems,
            state = lazyListState,
            modifier = modifier,
            contentPadding = if (isCompact) PaddingValues(horizontal = 8.dp, vertical = 12.dp) else PaddingValues(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 10.dp),
            itemKey = { it.key },
        ) { index, item ->
            Box(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth()
                    .padding(top = if (index > 0 && item.isTurnStart) ChatLayout.turnSpacing else 0.dp)
            ) {
                when (item) {
                    is ChatListItem.EventMessageCard -> {
                        EventMessageCard(
                            item = item,
                            onOpenReport = { title, content ->
                                viewModel.openDocument(
                                    DocumentTarget.Content(
                                        id = "report_${item.agentId}",
                                        title = title,
                                        content = content
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                    }

                    is ChatListItem.PlanApproval -> {
                        PlanApprovalCard(
                            request = item.request,
                            onApprove = { viewModel.approvePlan(item.request.id) },
                            onOpenInExtension = {
                                val content = item.request.planContent
                                    ?: "# ${item.request.title}\n\n${item.request.summary}"
                                viewModel.openDocument(
                                    DocumentTarget.Content(
                                        id = "plan_${item.request.id}",
                                        title = item.request.title,
                                        content = content
                                    )
                                )
                            },
                            modifier = Modifier
                                .widthIn(max = ChatLayout.actionCardMaxWidth)
                                .padding(bottom = ChatLayout.thoughtBottomSpacing)
                        )
                    }

                    is ChatListItem.QuestionCard -> {
                        QuestionCard(
                            question = item.request,
                            currentIndex = viewModel.questionPage,
                            selectedAnswers = viewModel.questionAnswers[viewModel.questionPage] ?: emptyList(),
                            onAnswer = { ans -> viewModel.answerQuestion(viewModel.questionPage, ans) },
                            onNextPage = viewModel::nextQuestionPage,
                            onPrevPage = viewModel::prevQuestionPage,
                            onSubmit = viewModel::submitQuestion,
                            onCancel = { viewModel.rejectQuestion(item.request.id) },
                            modifier = Modifier
                                .widthIn(max = ChatLayout.actionCardMaxWidth)
                                .padding(bottom = ChatLayout.thoughtBottomSpacing)
                        )
                    }

                    is ChatListItem.DocumentCard -> {
                        DocumentArtifactCard(
                            title = item.title,
                            lineCount = item.lineCount,
                            charCount = item.charCount,
                            createdAt = item.createdAt,
                            isStreaming = item.isStreaming,
                            isCompleted = item.isCompleted,
                            onOpenInExtension = {
                                viewModel.openDocument(
                                    DocumentTarget.Content(
                                        id = item.artifactId,
                                        title = item.title,
                                        content = item.content,
                                        lineCount = item.lineCount,
                                        charCount = item.charCount,
                                        isStreaming = item.isStreaming,
                                    )
                                )
                            },
                            modifier = Modifier
                                .widthIn(max = ChatLayout.actionCardMaxWidth)
                                .padding(bottom = 4.dp)
                        )
                    }

                    is ChatListItem.WorkTraceBlock -> {
                        WorkTraceCard(
                            workTrace = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = ChatLayout.thoughtBottomSpacing),
                            renderStepItem = { stepItem ->
                                when (stepItem) {
                                    is ChatListItem.Reasoning -> {
                                        ReasoningBlock(
                                            text = stepItem.text,
                                            isStreaming = stepItem.isStreaming,
                                            durationMs = stepItem.durationMs,
                                            isReasoningActive = stepItem.isReasoningActive,
                                            // WorkTraceCard 已整体限高 + 滚动 + 展开按钮，子项不再重复限制
                                            enforceMaxHeight = false,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                                        )
                                    }
                                    is ChatListItem.ToolCalls -> {
                                        ToolCallsBlock(
                                            toolCalls = stepItem.toolCalls,
                                            isStreaming = stepItem.isStreaming,
                                            isRunning = stepItem.isRunning,
                                            hasFailedTool = stepItem.hasFailedTool,
                                            toolSummary = stepItem.toolSummary,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                            onLinkClick = { url -> viewModel.openLink(url) },
                                        )
                                    }
                                    is ChatListItem.SubagentCalls -> {
                                        SubagentCallsBlock(
                                            subagents = stepItem.subagents,
                                            isStreaming = stepItem.isStreaming,
                                            isRunning = stepItem.isRunning,
                                            hasFailed = stepItem.hasFailed,
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                                        )
                                    }
                                    is ChatListItem.TextMessage -> {
                                        if (stepItem.text.isNotBlank()) {
                                            Text(
                                                text = stepItem.text.trim(),
                                                color = colors.textMuted,
                                                fontSize = 12.sp,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (stepItem.images.isNotEmpty()) {
                                            AssistantImagesView(
                                                images = stepItem.images,
                                                viewModel = viewModel,
                                                colors = colors,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                    is ChatListItem.DocumentCard -> {
                                        DocumentArtifactCard(
                                            title = stepItem.title,
                                            lineCount = stepItem.lineCount,
                                            charCount = stepItem.charCount,
                                            createdAt = stepItem.createdAt,
                                            isStreaming = stepItem.isStreaming,
                                            isCompleted = stepItem.isCompleted,
                                            onOpenInExtension = {
                                                viewModel.openDocument(DocumentTarget.Content(
                                                    id = stepItem.artifactId,
                                                    title = stepItem.title,
                                                    content = stepItem.content,
                                                    lineCount = stepItem.lineCount,
                                                    charCount = stepItem.charCount,
                                                    isStreaming = stepItem.isStreaming,
                                                ))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    // 交互卡和最终交付由顶层渲染，不放入过程折叠。
                                    is ChatListItem.EventMessageCard -> {}
                                    is ChatListItem.Footer -> {}
                                    is ChatListItem.PlanApproval -> {}
                                    is ChatListItem.QuestionCard -> {}
                                    is ChatListItem.TurnDiffCard -> {}
                                    is ChatListItem.WorkTraceBlock -> {}
                                }
                            }
                        )
                    }

                    is ChatListItem.Reasoning -> {
                        ReasoningBlock(
                            text = item.text,
                            isStreaming = item.isStreaming,
                            durationMs = item.durationMs,
                            isReasoningActive = item.isReasoningActive,
                            // 用 item.key 作为展开状态锚点，避免 LazyColumn 复用导致展开状态串位
                            contentKey = item.key,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = ChatLayout.thoughtBottomSpacing)
                        )
                    }

                    is ChatListItem.ToolCalls -> {
                        ToolCallsBlock(
                            toolCalls = item.toolCalls,
                            isStreaming = item.isStreaming,
                            isRunning = item.isRunning,
                            hasFailedTool = item.hasFailedTool,
                            toolSummary = item.toolSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = ChatLayout.thoughtBottomSpacing),
                            onLinkClick = { url -> viewModel.openLink(url) },
                        )
                    }

                    is ChatListItem.SubagentCalls -> {
                        // 派发是对用户有意义的阶段状态，保留入口，不藏进工作过程。
                        SubagentCallsBlock(
                            subagents = item.subagents,
                            isStreaming = item.isStreaming,
                            isRunning = item.isRunning,
                            hasFailed = item.hasFailed,
                            modifier = Modifier.fillMaxWidth().padding(bottom = ChatLayout.thoughtBottomSpacing),
                        )
                    }

                    is ChatListItem.TurnDiffCard -> {
                        TurnDiffSummaryCard(
                            summary = item.summary,
                            onReviewClick = {
                                viewModel.openDiff(item.messageId, null)
                            },
                            // 文件行点击 = 在 diff viewer 打开该文件的 diff（与审阅按钮同链路，定位到具体文件）
                            onFileClick = { filePath ->
                                viewModel.openDiff(item.messageId, filePath)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        )
                    }

                    is ChatListItem.Footer -> {
                        AssistantMessageFooter(
                            footer = item.footer,
                            lastMessageText = item.lastMessageText,
                            fullTurnText = item.fullTurnText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 4.dp)
                        )
                    }

                    is ChatListItem.TextMessage -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (item.isUser) Arrangement.End else Arrangement.Start
                        ) {
                            if (item.isUser) {
                                // 用户消息：提升饱和度气泡，右对齐
                                SelectionContainer {
                                    val parsed = remember(item.text) { PromptComposer.parse(item.text) }
                                    Column(
                                        modifier = Modifier
                                            .widthIn(max = if (isCompact) ChatLayout.userBubbleCompactMaxWidth else ChatLayout.userBubbleMaxWidth)
                                            .clip(RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                                            .background(colors.userBubbleBackground)
                                            .border(1.dp, colors.userBubbleBorder, RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (item.images.isNotEmpty()) {
                                            item.images.forEachIndexed { imgIdx, imgUrl ->
                                                val imageTitle = stringResource(Res.string.input_image_n, imgIdx + 1)
                                                InkImage(
                                                    model = imgUrl,
                                                    contentDescription = stringResource(Res.string.input_image_attachment),
                                                    modifier = Modifier
                                                        .widthIn(max = 260.dp)
                                                        .heightIn(max = 180.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            viewModel.openDocument(DocumentTarget.Image(imageTitle, imgUrl))
                                                        }
                                                )
                                            }
                                        }
                                        if (parsed.instruction.isNotBlank()) {
                                            Text(
                                                text = parsed.instruction,
                                                color = colors.textPrimary,
                                                fontSize = 13.5.sp,
                                                lineHeight = 21.6.sp
                                            )
                                        }
                                        if (parsed.pastedTexts.isNotEmpty()) {
                                            parsed.pastedTexts.forEach { pasted ->
                                                val pastedTitle = stringResource(Res.string.input_pasted_text_n, pasted.index)
                                                UserPastedTextCard(
                                                    attachment = pasted,
                                                    onOpenInExtension = {
                                                        viewModel.openDocument(
                                                            DocumentTarget.Content(
                                                                id = "pasted_${pasted.index}",
                                                                title = pastedTitle,
                                                                content = pasted.text,
                                                                lineCount = pasted.lineCount,
                                                                charCount = pasted.charCount
                                                            )
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        DisableSelection {
                                            val copyToClipboard = xyz.mederi.util.rememberClipboardCopy()
                                            UserMessageFooter(
                                                createdAt = item.createdAt,
                                                onRollback = if (showUserMessageActions) {
                                                    {
                                                        viewModel.rollbackMessage(
                                                            conversationId = item.conversationId,
                                                            messageId = item.messageId,
                                                            messageText = item.text
                                                        )
                                                    }
                                                } else null,
                                                onCopy = {
                                                    val textToCopy = if (parsed.pastedTexts.isNotEmpty()) item.text else parsed.instruction.ifBlank { item.text }
                                                    copyToClipboard(textToCopy)
                                                },
                                                modifier = Modifier.align(Alignment.End)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 助手消息：渲染交付正文
                                val assistantContent: @Composable (Modifier) -> Unit = { contentModifier ->
                                    Column(modifier = contentModifier) {
                                        if (item.text.isNotBlank()) {
                                            MederiMarkdown(
                                                content = item.text,
                                                sessionKey = item.conversationId,
                                                modifier = Modifier.fillMaxWidth(),
                                                isStreaming = item.isStreaming,
                                                selectionMenuActions = selectionMenuActions,
                                                enableScrollOverride = false,
                                                // 链接点击分流下沉到 VM：LinkTargetClassifier 纯函数分档（本地路径 vs 外部链接），
                                                // View 只发意图（viewModel.openLink）
                                                onLinkClick = { url -> viewModel.openLink(url) },
                                            )
                                        }
                                        if (item.images.isNotEmpty()) {
                                            AssistantImagesView(
                                                images = item.images,
                                                viewModel = viewModel,
                                                colors = colors,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        // 轮次底部 footer：模型名 · 审批/自主 · 推理档 · 时长 · 完成时间
                                        item.assistantFooter?.let { footer ->
                                            AssistantMessageFooter(
                                                footer = footer,
                                                lastMessageText = item.text,
                                                fullTurnText = item.text,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                assistantContent(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
