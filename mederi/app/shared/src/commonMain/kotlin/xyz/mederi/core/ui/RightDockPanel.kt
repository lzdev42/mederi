package xyz.mederi.core.ui

/**
 * 右侧独立功能活动栏枚举。
 *
 * 被 RightDock / RightExtensionPanel / BrowserPanel 共用，故从 ChatListItems 抽离到此独立文件。
 */
enum class RightDockPanel {
    OVERVIEW,
    DIFF,
    PLAN,
    ARTIFACTS,
    TERMINAL,
    BROWSER
}
