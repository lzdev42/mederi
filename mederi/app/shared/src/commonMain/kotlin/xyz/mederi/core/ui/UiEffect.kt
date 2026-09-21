package xyz.mederi.core.ui

/**
 * 一次性 UI 效果命令（事件）通道消息。
 *
 * 与 [UiMessage] 同属 VM → UI 的通信手段，但语义不同：
 *
 * - **UiEffect = 一次性命令**（打开设置对话框、打开项目选择菜单等导航/打开类动作）。
 *   这类动作不是"持续展示的状态"，用布尔标志/递增计数器表达会在 VM 与 UI 之间
 *   泄漏一次性语义（谁置 true、谁置 false、何时重置都含糊）。迁移后由
 *   [WorkspaceViewModel.effects]（`Channel<UiEffect>`）派发，UI 层 collect 消费，
 *   一次性语义由 Channel 天然承载（发送即消费，不持久化）。
 * - **持续展示状态仍然保留在 VM state**（error 家族/ErrorBoard、imageStrippedNotice
 *   轻提示）：它们需要在 UI 上常驻展示直到被清除，不是"触发一次的导航命令"，不该走效果通道。
 *
 * 用户可见文案仍走 [UiMessage]（资源 key），效果通道只承载"打开什么"的命令本身。
 */
sealed interface UiEffect {
    /** 打开设置对话框（一次性导航命令） */
    data object OpenSettings : UiEffect

    /** 打开项目选择菜单（一次性命令，原 ChatInputCard projectMenuOpenRequest 计数器） */
    data object OpenProjectMenu : UiEffect
}