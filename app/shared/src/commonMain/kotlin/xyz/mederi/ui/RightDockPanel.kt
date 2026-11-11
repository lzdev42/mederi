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
    /**
     * 文件管理器：项目目录树面板（2026-11，承担原"文件查看器"按钮位）。
     * 点文件经 openDocument(DocumentTarget.Path) 在文档面板（ARTIFACTS，无按钮）打开读代码。
     */
    FILES,
    /**
     * 文件阅读器：文档/报告/计划内容面板。无 dock 按钮（2026-11）——
     * 仅由 openDocument / 概览"文档"行 / 对话流文档卡触达。
     */
    ARTIFACTS,
    TERMINAL,
    BROWSER,
    /**
     * 浏览器自动化流程面板（任务步骤/推理/截图）。
     */
    BROWSER_AUTOMATION
}

