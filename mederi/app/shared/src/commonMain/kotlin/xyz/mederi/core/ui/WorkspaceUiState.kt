package xyz.mederi.core.ui

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.ChatMessage
import xyz.mederi.core.contract.models.FileDiff

/**
 * WorkspaceViewModel 状态收敛分组（纯内部存储结构）。
 *
 * UI 消费端仍读 viewModel.error / viewModel.diffItems 等原有属性（名称与类型不变），
 * 本文件只提供 VM 内部单点持有的分组状态，避免顶层散落多个 mutableStateOf。
 */

/**
 * 错误族状态（单点存储错误 5 字段，WorkspaceViewModel 内部经 private errorState 持有）。
 *
 * @property message 当前会话的头条错误简报（null = 无错误）
 * @property diagnostic 完整诊断报告纯文本（来自 ConversationSnapshot.errorDiagnostic）
 * @property id 错误 ID（来自 ConversationSnapshot.errorId，JVM 端可经 ErrorCollector.get 取回 ErrorRecord）
 * @property isStreamInterrupted 是否为断流（流式连接提前中断，true 时 ErrorBoard 显示"继续"按钮）
 * @property isDetailOpen 是否正在展示详细错误报告弹窗
 */
data class ErrorState(
    val message: UiMessage? = null,
    val diagnostic: String? = null,
    val id: String? = null,
    val isStreamInterrupted: Boolean = false,
    val isDetailOpen: Boolean = false,
)

/**
 * diff 面板状态（单点存储 diff 3 字段；activeDockPanel 同时服务全部 dock 面板，保留独立状态）。
 *
 * @property items 当前会话的文件 diff 列表
 * @property selectedPath 当前选中的 diff 文件路径
 * @property showPanel diff 面板是否展开
 */
data class DiffUiState(
    val items: List<FileDiff> = emptyList(),
    val selectedPath: String? = null,
    val showPanel: Boolean = false,
)