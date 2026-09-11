package xyz.mederi.ui

import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Square
import compose.icons.feathericons.Zap
import xyz.mederi.core.contract.models.*
import xyz.mederi.core.ui.ChatListItem
import xyz.mederi.core.ui.WorkspaceViewModel
import xyz.mederi.core.ui.appstate.LocalAppState
import compose.icons.feathericons.Sidebar
import xyz.mederi.theme.LocalMederiColors
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import xyz.emuci.inkcompose.LocalSessionKey
import xyz.emuci.inkcompose.MarkdownView
import xyz.mederi.currentTimeMillis
import xyz.mederi.ui.ChatLayout
import xyz.mederi.ui.components.RightExtensionPanel
import xyz.mederi.ui.components.RightDock
import xyz.mederi.ui.components.StatusBar
import xyz.mederi.ui.components.ReasoningBlock
import xyz.mederi.ui.components.ThoughtAndActionsBlock
import xyz.mederi.ui.components.ToolPill
import xyz.mederi.ui.components.QuestionCard
import xyz.mederi.ui.components.PlanApprovalCard
import xyz.mederi.ui.components.ChatInputCard
import xyz.mederi.ui.components.TurnStatusBar
import xyz.mederi.ui.components.TurnStatus
import xyz.mederi.ui.components.UserPastedTextCard
import xyz.mederi.ui.components.UserMessageFooter
import androidx.compose.foundation.text.selection.DisableSelection
import xyz.mederi.util.PromptComposer
import coil3.compose.AsyncImage
import xyz.emuci.markdown.renderer.SelectionMenuAction

import compose.icons.feathericons.Menu
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.first

@Composable
fun Workspace(
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel,
    isCompact: Boolean = false,
    isLeftSidebarOpen: Boolean = true,
    onToggleLeftSidebar: () -> Unit = {},
    onOpenProjectPicker: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val colors = LocalMederiColors.current
    val appState = LocalAppState.current
    val isRightPanelOpen = viewModel.isRightPanelOpen

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceWorkspace)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val screenWidth = maxWidth
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                // 中央主工作区 (包含顶部 Header 与 聊天/消息区域)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // 中央区 Header (40dp 高度对齐全屏顶栏线条)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(colors.surfaceWorkspace)
                            .border(width = 1.dp, color = colors.divider)
                            .padding(horizontal = if (isCompact) 8.dp else 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 移动端：始终显示汉堡菜单按钮
                            if (isCompact) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onToggleLeftSidebar() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.Menu,
                                        contentDescription = "打开菜单",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                // 桌面端：当左侧边栏关闭时，在此呈现左侧边栏开关键
                                AnimatedVisibility(
                                    visible = !isLeftSidebarOpen,
                                    enter = fadeIn() + expandHorizontally(),
                                    exit = fadeOut() + shrinkHorizontally()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onToggleLeftSidebar() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = FeatherIcons.Sidebar,
                                                contentDescription = "打开左侧边栏",
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                }
                            }

                            // 顶部面包屑：项目名 / 对话名（派生 StateFlow，UI collectAsState 订阅）
                            val header by viewModel.headerTitle.collectAsState()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val projectName = header.projectName
                                if (projectName != null && !isCompact) {
                                    Text(
                                        text = projectName,
                                        color = colors.textSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "/",
                                        color = colors.textMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = header.conversationName,
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCompact) {
                                    run {
                                        // 唯一真理源：AppState.selectedAgentMode 派生流，collectAsState 订阅
                                        val mode by viewModel.selectedAgentMode.collectAsState()
                                        val isAutonomous = mode == AgentMode.AUTONOMOUS
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isAutonomous) colors.accentPrimary.copy(alpha = 0.15f) else colors.accentWarning.copy(alpha = 0.15f))
                                                .border(1.dp, if (isAutonomous) colors.accentPrimary.copy(alpha = 0.4f) else colors.accentWarning.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                                .clickable {
                                                    viewModel.selectAgentMode(if (isAutonomous) AgentMode.APPROVAL else AgentMode.AUTONOMOUS)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 3.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isAutonomous) FeatherIcons.Zap else FeatherIcons.Shield,
                                                    contentDescription = null,
                                                    tint = if (isAutonomous) colors.accentPrimary else colors.accentWarning,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = if (isAutonomous) "自主" else "审批",
                                                    color = if (isAutonomous) colors.accentPrimary else colors.accentWarning,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewModel.selectConversation(null) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = FeatherIcons.Plus,
                                            contentDescription = "新建会话",
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                // 扩展面板开关键
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isRightPanelOpen) colors.accentPrimary.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { viewModel.toggleRightPanel() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.Sidebar,
                                        contentDescription = "打开扩展窗口",
                                        tint = if (isRightPanelOpen) colors.accentPrimary else colors.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 主内容区（消息列表与输入框）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val contentMaxWidth = ChatLayout.contentMaxWidth
                        when {
                            viewModel.messages.isEmpty() && !viewModel.isWorking -> {
                                // 空状态：居中欢迎页
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            bottom = if (isCompact) 20.dp else 60.dp,
                                            start = if (isCompact) 12.dp else 24.dp,
                                            end = if (isCompact) 12.dp else 24.dp
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "mederi",
                                        color = colors.textPrimary,
                                        fontSize = if (isCompact) 24.sp else 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.5).sp
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    ChatInputCard(
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .widthIn(max = if (isCompact) Dp.Unspecified else 620.dp)
                                            .fillMaxWidth(),
                                        onOpenProjectPicker = onOpenProjectPicker
                                    )
                                }
                            }
                            else -> {
                                // 有消息
                                MessageList(
                                    viewModel = viewModel,
                                    contentMaxWidth = contentMaxWidth,
                                    isCompact = isCompact,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = if (isCompact) 8.dp else 24.dp)
                                        .padding(bottom = if (isCompact) 8.dp else 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ChatInputCard(
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .widthIn(max = contentMaxWidth)
                                            .fillMaxWidth(),
                                        onOpenProjectPicker = onOpenProjectPicker
                                    )
                                }
                            }
                        }
                    }
                }

                // 桌面端：独立功能面板与常驻 Dock 栏
                if (!isCompact) {
                    RightExtensionPanel(
                        isOpen = isRightPanelOpen,
                        onClose = { viewModel.closeDockPanel() },
                        viewModel = viewModel,
                        isCompact = false
                    )

                    RightDock(
                        activePanel = viewModel.activeDockPanel,
                        onSelectPanel = { panel -> viewModel.toggleDockPanel(panel) },
                        onOpenSettings = onOpenSettings
                    )
                }
            }

            // 移动端：右侧扩展面板右滑抽屉（Right Modal Drawer）+ 半透明背景遮罩
            if (isCompact) {
                // 半透明背景遮罩
                androidx.compose.animation.AnimatedVisibility(
                    visible = isRightPanelOpen,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.surfaceOverlay)
                            .clickable { viewModel.closeDockPanel() }
                    )
                }

                // 右侧滑出抽屉
                androidx.compose.animation.AnimatedVisibility(
                    visible = isRightPanelOpen,
                    enter = slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { it } + fadeIn(),
                    exit = slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    RightExtensionPanel(
                        isOpen = isRightPanelOpen,
                        onClose = { viewModel.closeDockPanel() },
                        viewModel = viewModel,
                        isCompact = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(minOf(360.dp, screenWidth * 0.85f))
                    )
                }
            }
        }

        val processStats by appState.processStats.collectAsState()

        // 底部吸底系统状态栏 (资源监控)
        StatusBar(
            stats = processStats,
            error = viewModel.error,
            onRetry = {}
        )
    }
}

@Composable
private fun MessageList(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    contentMaxWidth: Dp = ChatLayout.contentMaxWidth,
) {
    val colors = LocalMederiColors.current
    val lazyListState = rememberLazyListState()

    var stickToBottom by remember(viewModel.conversationId) { mutableStateOf(true) }

    // 首次自动滚底完成前禁用位置跟踪。否则列表初始布局在顶部，
    // 跟踪 effect 立刻把 stickToBottom 打成 false，与"打开会话自动滚底"互相打架
    // （内容变化 effect 因 key 变化被重启后早退，永远卡在顶部）。
    var bottomTrackingEnabled by remember(viewModel.conversationId) { mutableStateOf(false) }

    // 展平后的聊天列表 item 由 WorkspaceViewModel 计算
    val chatItems = viewModel.chatItems

    // 选区菜单 actions——在 item 外部 remember，避免每个 item 都重建
    val selectionMenuActions = remember(viewModel) {
        listOf(
            SelectionMenuAction("添加到对话框") { text ->
                viewModel.appendToInput(text)
            }
        )
    }

    // 位置跟踪：只在"滚动锚点变化"（用户滚动/程序吸附落地）时更新贴底意图。
    // 关键区分：内容增长（流式输出）不改变 firstVisible，不会误判为用户离开底部；
    // 而在"比视口高的最后一个 item"内部上拉时 firstVisible 会变——index 阈值判定在这里会失灵，
    // 必须用 !canScrollForward（内容真正的底）作为贴底判据。
    // 滚动进行中（拖拽/惯性/程序滚动）不定意图，落定后的那一帧再算。
    var lastScrollAnchor by remember(viewModel.conversationId) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.layoutInfo }
            .collect { _ ->
                if (!bottomTrackingEnabled) return@collect
                if (lazyListState.isScrollInProgress) return@collect
                val anchor = lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
                if (anchor != lastScrollAnchor) {
                    lastScrollAnchor = anchor
                    stickToBottom = !lazyListState.canScrollForward
                }
            }
    }

    // 跟随滚动（stick-to-bottom / tail -f 式）：贴底期间内容增长（流式文字增高、
    // 新消息 / TurnStatusBar / 动态卡片出现）自动吸附到内容真正的底部。
    // 观察 layoutInfo（每次布局都发射，避免 canForward 等派生值不变时 dedup 吞掉事件），
    // 条件全部在 collect 内现读。防打架：
    // 1) isScrollInProgress：用户拖拽/惯性中不拽回（程序滚动中间帧同样跳过，防自触发）
    // 2) stickToBottom 现读：同一帧布局里位置跟踪先落旗（声明在前），用户已上拉则不拽
    // 吸附成功后重申贴底意图，抵消"吸附落地瞬间内容又增长"的竞态误判。
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.layoutInfo }
            .collect {
                if (bottomTrackingEnabled && stickToBottom &&
                    !lazyListState.isScrollInProgress && lazyListState.canScrollForward
                ) {
                    lazyListState.snapToBottom()
                    stickToBottom = true
                }
            }
    }

    // 发送消息（乐观用户消息出现）时强制滚底：即使用户正翻在历史区，发送动作也回到最新。
    // 同样先等首次测量（新会话首条消息从欢迎页切入 MessageList 时是冷启动）
    val optimisticMessageId = viewModel.optimisticUserMessage?.id
    LaunchedEffect(optimisticMessageId) {
        if (optimisticMessageId != null) {
            stickToBottom = true
            snapshotFlow { lazyListState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            lazyListState.snapToBottom()
            bottomTrackingEnabled = true
        }
    }

    // 内容变化（会话打开加载完成/新消息/思考面板/动态卡片出现）时自动滚到列表真正底部。
    // 冷启动恢复会话时 chatItems 首帧就可能是全量，但 LazyColumn 尚未测量（totalItemsCount=0），
    // 此时 scrollToItem 会被 clamp 到 index 0 卡在顶部——先等首次测量完成再跳。
    val pendingQuestion = viewModel.pendingQuestion
    val pendingPlanApproval = viewModel.pendingPlanApproval
    LaunchedEffect(
        chatItems.size, chatItems.lastOrNull()?.key,
        pendingQuestion?.id, pendingPlanApproval?.id, stickToBottom
    ) {
        if (!stickToBottom) return@LaunchedEffect
        val hasDynamicItems = pendingQuestion != null || pendingPlanApproval != null
        if (chatItems.isNotEmpty() || hasDynamicItems) {
            snapshotFlow { lazyListState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            lazyListState.snapToBottom()
            stickToBottom = true
            // 首次滚底落地后再放开位置跟踪，之后的 stickToBottom 变化才代表用户的真实滚动意图
            bottomTrackingEnabled = true
        }
    }

    CompositionLocalProvider(LocalSessionKey provides viewModel.conversationId) {
        LazyColumn(
            state = lazyListState,
            modifier = modifier,
        contentPadding = if (isCompact) PaddingValues(horizontal = 8.dp, vertical = 12.dp) else PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ChatLayout.itemSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. 展平后的消息 items（每个文本块/思考面板独立；轮次起点与上一 item 拉开间距）
        itemsIndexed(chatItems, key = { _, item -> item.key }) { index, item ->
            Box(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth()
                    .padding(top = if (index > 0 && item.isTurnStart) ChatLayout.turnSpacing else 0.dp)
            ) {
                when (item) {
                    is ChatListItem.SummaryCard -> {
                        // 压缩总结卡片：居中、弱化样式，标记 AI 视图的分界点
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = contentMaxWidth * 0.85f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.surfaceCard)
                                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "压缩总结",
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(4.dp))
                                SelectionContainer {
                                    Text(
                                        text = item.text,
                                        color = colors.textSecondary,
                                        fontSize = 12.5.sp,
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        }
                    }

                    is ChatListItem.PlanApproval -> {
                        Box(
                            modifier = Modifier
                                .widthIn(max = contentMaxWidth)
                                .fillMaxWidth()
                                .padding(
                                    top = if (item.isTurnStart) ChatLayout.turnSpacing else 0.dp,
                                    bottom = ChatLayout.thoughtBottomSpacing
                                )
                        ) {
                            PlanApprovalCard(
                                request = item.request,
                                onApprove = { viewModel.approvePlan(item.request.id) },
                                onOpenInExtension = {
                                    val content = item.request.planContent
                                        ?: "# ${item.request.title}\n\n${item.request.summary}"
                                    viewModel.openPlanInExtension(item.request.id, item.request.title, content)
                                },
                                modifier = Modifier.widthIn(max = ChatLayout.actionCardMaxWidth)
                            )
                        }
                    }

                    is ChatListItem.ThoughtAndActions -> {
                        ThoughtAndActionsBlock(
                            reasoningParts = item.reasoningParts,
                            toolCalls = item.toolCalls,
                            isStreaming = item.isStreaming,
                            toolSummary = item.toolSummary,
                            hasFailedTool = item.hasFailedTool,
                            isRunning = item.isRunning,
                            headerSummary = item.headerSummary,
                            isReasoningActive = item.isReasoningActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = ChatLayout.thoughtBottomSpacing)
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
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.userBubbleBackground)
                                            .border(1.dp, colors.userBubbleBorder, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 14.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (item.images.isNotEmpty()) {
                                            item.images.forEachIndexed { imgIdx, imgUrl ->
                                                AsyncImage(
                                                    model = imgUrl,
                                                    contentDescription = "图片附件",
                                                    modifier = Modifier
                                                        .widthIn(max = 260.dp)
                                                        .heightIn(max = 180.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            viewModel.openImageInExtension("图片 #${imgIdx + 1}", imgUrl)
                                                        }
                                                )
                                            }
                                        }
                                        if (parsed.instruction.isNotBlank()) {
                                            Text(
                                                text = parsed.instruction,
                                                color = colors.textPrimary,
                                                fontSize = 13.5.sp,
                                                lineHeight = 22.sp
                                            )
                                        }
                                        if (parsed.pastedTexts.isNotEmpty()) {
                                            parsed.pastedTexts.forEach { pasted ->
                                                UserPastedTextCard(
                                                    attachment = pasted,
                                                    onOpenInExtension = {
                                                        viewModel.openTextInExtension(
                                                            title = "粘贴文本 #${pasted.index}",
                                                            content = pasted.text,
                                                            lineCount = pasted.lineCount,
                                                            charCount = pasted.charCount
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        DisableSelection {
                                            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                            UserMessageFooter(
                                                createdAt = item.createdAt,
                                                onRollback = {
                                                    viewModel.rollbackMessage(
                                                        conversationId = item.conversationId,
                                                        messageId = item.messageId,
                                                        messageText = item.text
                                                    )
                                                },
                                                onCopy = {
                                                    val textToCopy = if (parsed.pastedTexts.isNotEmpty()) item.text else parsed.instruction.ifBlank { item.text }
                                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                                                },
                                                modifier = Modifier.align(Alignment.End)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 助手消息：新轮次回复左侧增加极细视觉锚点
                                val assistantContent: @Composable (Modifier) -> Unit = { contentModifier ->
                                    Column(modifier = contentModifier) {
                                        if (item.text.isNotBlank()) {
                                            MarkdownView(
                                                content = item.text,
                                                sessionKey = item.conversationId,
                                                modifier = Modifier.fillMaxWidth(),
                                                isStreaming = item.isStreaming,
                                                selectionMenuActions = selectionMenuActions,
                                                enableScrollOverride = false,
                                            )
                                        }
                                        if (item.images.isNotEmpty()) {
                                            AssistantImagesView(
                                                images = item.images,
                                                viewModel = viewModel,
                                                colors = colors,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }                                    }
                                }

                                if (item.isTurnStart) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(colors.accentPrimary.copy(alpha = 0.45f))
                                        )
                                        assistantContent(Modifier.weight(1f))
                                    }
                                } else {
                                    assistantContent(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. 对话轮次状态栏（授权等待由卡片本身承载，turnStatus 已去重）
        val turnStatus = viewModel.turnStatus
        if (turnStatus != TurnStatus.Idle) {
            item {
                Box(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        // detail 优先真实错误（errorMessage），无错误但有流式警告（statusHint）也展示
                        TurnStatusBar(
                            status = turnStatus,
                            detail = viewModel.error ?: viewModel.statusHint,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 3. 动态选择题卡片（答案收集/翻页状态机在 WorkspaceViewModel）
        viewModel.pendingQuestion?.let { question ->
            item {
                Box(
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth()
                ) {
                    QuestionCard(
                        question = question,
                        currentIndex = viewModel.questionPage,
                        // 唯一真理源：viewModel.questionAnswers（卡片无状态，点击经 onAnswer 单向写回）
                        selectedAnswer = viewModel.questionAnswers[viewModel.questionPage] ?: "",
                        onAnswer = { _, ans -> viewModel.answerQuestion(viewModel.questionPage, ans) },
                        onNextPage = viewModel::nextQuestionPage,
                        onPrevPage = viewModel::prevQuestionPage,
                        onSubmit = viewModel::submitQuestion,
                        onCancel = { viewModel.rejectQuestion(question.id) },
                        modifier = Modifier.widthIn(max = ChatLayout.actionCardMaxWidth)
                    )
                }
            }
        }
    }
}
}

/**
 * 吸附到内容真正的底部。
 *
 * scrollToItem(lastIndex) 只会把"最后一个 item 的顶部"对齐视口顶——当最后一个 item
 * 比视口高（流式中的长回复）时根本看不到最新内容。给 scrollOffset 一个超量值，
 * 测量时会 clamp 到内容底：无论最后一个 item 多高都精确落底，落定后 canScrollForward=false。
 */
private suspend fun LazyListState.snapToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex >= 0) {
        scrollToItem(lastIndex, 1_000_000)
    }
}

@Composable
fun PillButton(
    label: String,
    hasDropdown: Boolean,
    isPrimary: Boolean
) {
    val colors = LocalMederiColors.current
    val bgColor = if (isPrimary) colors.accentPrimary else colors.buttonSecondary
    val textColor = if (isPrimary) colors.onAccentPrimary else colors.onButtonSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
        if (hasDropdown) {
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp).padding(start = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantImagesView(
    images: List<String>,
    viewModel: WorkspaceViewModel,
    colors: xyz.mederi.theme.MederiColors,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        images.forEachIndexed { imgIdx, imgUrl ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.openImageInExtension("生成的图片 #${imgIdx + 1}", imgUrl)
                    }
            ) {
                AsyncImage(
                    model = imgUrl,
                    contentDescription = "AI生成的图片 #${imgIdx + 1}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(min = 100.dp, max = 360.dp)
                        .heightIn(min = 80.dp, max = 260.dp)
                )
            }
        }
    }
}

