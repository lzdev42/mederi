package xyz.mederi.core.contract

import kotlinx.serialization.Serializable

/**
 * 沙盒控制 hooks（本机进程偏好，同 RemoteControlHooks 模式——不进 AiCore 主契约）。
 *
 * 仅嵌入式 core（MederiAiCore）实现；ServerAiCore/Mock 不实现，
 * UI 用 `as?` 安全转型，null = 远程/模拟端（沙盒由服务器环境决定，UI 显示只读说明）。
 */
interface SandboxHooks {

    /** 写穿全局白名单：设置页改后实时生效（下一 turn 的命令沙箱即用新值），无需重启 */
    fun setSandboxExtraPaths(paths: List<String>)

    /** 当前平台的沙箱状态（设置页状态卡展示）；null = 无法判定 */
    fun sandboxStatus(): SandboxStatusInfo? = null
}

/**
 * 沙箱状态快照（设置页展示用，字段与 core CommandSandbox.SandboxStatus 对齐）。
 */
@Serializable
data class SandboxStatusInfo(
    val backend: String,      // "Seatbelt" / "bubblewrap" / "none"
    val available: Boolean,   // 沙箱是否实际生效
    val shell: String,        // 探测到的 shell（bash/sh/cmd）
    val detail: String        // 人读说明（不可用原因、安装命令等）
)
