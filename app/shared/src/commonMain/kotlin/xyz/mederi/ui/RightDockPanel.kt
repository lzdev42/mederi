package xyz.mederi.ui

import kotlinx.serialization.Serializable

/**
 * 右侧独立功能活动栏枚举。
 *
 * 被 RightDock / RightExtensionPanel / BrowserPanel 共用，故从 ChatListItems 抽离到此独立文件。
 */
@Serializable
enum class RightDockPanel {
    DIFF,
    ARTIFACTS,
    TERMINAL,
    BROWSER
}

