package xyz.mederi.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 聊天界面布局常量。所有硬编码尺寸统一收敛在此，View 与 ViewModel 共同引用。
 */
object ChatLayout {
    /** 消息列 / 输入框内容最大宽度（WorkspaceViewModel.contentMaxWidth 引用） */
    val contentMaxWidth: Dp = 820.dp

    /** 用户消息气泡最大宽度 */
    val userBubbleMaxWidth: Dp = 680.dp

    /** 用户消息气泡最大宽度（移动端） */
    val userBubbleCompactMaxWidth: Dp = 320.dp

    /** 高危授权 / 计划审批 / 问询卡片的最大宽度 */
    val actionCardMaxWidth: Dp = 560.dp

    /** 对话视图最小宽度（手机宽度下限）：右侧扩展面板 + Dock 不得把对话区压缩到小于此宽度 */
    val conversationMinWidth: Dp = 360.dp

    /** 右侧常驻 Dock 栏宽度（RightDock） */
    val rightDockWidth: Dp = 46.dp

    /** 消息条目之间的基础间距 */
    val itemSpacing: Dp = 4.dp

    /** 思考过程折叠条与下方消息正文之间的间距 */
    val thoughtBottomSpacing: Dp = 4.dp

    /** 对话轮次之间的间距（用户消息为轮次起点，额外加此间距） */
    val turnSpacing: Dp = 14.dp
}
