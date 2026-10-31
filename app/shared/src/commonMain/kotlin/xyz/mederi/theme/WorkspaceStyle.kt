package xyz.mederi.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对话工作台已迁移组件的共享表现规格。
 * 覆盖消息、输入、概览、过程/交互卡、文件/阅读器控件及辅助面板；不全局改写设置页或 InkCompose 内容语义。
 * 规格真理源：docs/design/standard/01-tokens.md §5；交付范围见 workspace-*-delivery.md。颜色仍读取 MederiColors。
 */
object WorkspaceStyle {
    val PageTitle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val MessageBody = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val Title = Body.copy(fontWeight = FontWeight.Medium)
    val ControlHeight = 30.dp
    val NavigationTabMaxWidth = 240.dp
    val IconSize = 16.dp
    val IconTarget = 30.dp
    val InputRadius = MederiRadius.Bubble
    val ObjectRadius = MederiRadius.Card
    const val TooltipDelayMillis = 200L
    const val TooltipHideMillis = 150L
    const val HoverMillis = WorkspaceMotionPolicy.FeedbackMillis
    const val ExpandMillis = WorkspaceMotionPolicy.DisclosureMillis
    const val TaskPulseMillis = WorkspaceMotionPolicy.TaskCycleMillis
    const val CopyFeedbackMillis = 1500L
}
