package xyz.mederi.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天界面布局常量。所有硬编码尺寸统一收敛在此，View 与 ViewModel 共同引用。
 */
object ChatLayout {
    /** 消息列 / 输入框内容最大宽度（WorkspaceViewModel.contentMaxWidth 引用） */
    val contentMaxWidth: Dp = 1000.dp

    /** 用户消息气泡最大宽度 */
    val userBubbleMaxWidth: Dp = 680.dp

    /** 用户消息气泡最大宽度（移动端） */
    val userBubbleCompactMaxWidth: Dp = 320.dp

    /** 高危授权 / 计划审批 / 问询卡片的最大宽度 */
    val actionCardMaxWidth: Dp = 640.dp

    /** 空状态欢迎页输入框最大宽度 */
    val emptyStateInputMaxWidth: Dp = 620.dp

    /** 左侧边栏宽度 */
    val sidebarWidth: Dp = 260.dp

    /** 右侧扩展面板宽度 */
    val rightPanelWidth: Dp = 360.dp

    /** 顶部 Header 高度（全屏顶栏线条对齐） */
    val headerHeight: Dp = 40.dp

    /** 消息条目之间的基础间距 */
    val itemSpacing: Dp = 4.dp

    /** 思考过程折叠条与下方消息正文之间的间距 */
    val thoughtBottomSpacing: Dp = 8.dp

    /** 对话轮次之间的间距（用户消息为轮次起点，额外加此间距） */
    val turnSpacing: Dp = 14.dp
}
