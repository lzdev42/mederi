package xyz.mederi.core.contract

import kotlinx.serialization.Serializable

/**
 * 执行宿主的沙箱后端能力快照；通过 AiCore 访问，桌面与遥控端同源。
 */
@Serializable
data class SandboxStatusInfo(
    val backend: String,      // "Seatbelt" / "bubblewrap" / "none"
    val available: Boolean,   // 沙箱是否实际生效
    val shell: String,        // 探测到的 shell（bash/sh/cmd）
    val detail: String        // 人读说明（不可用原因、安装命令等）
)
