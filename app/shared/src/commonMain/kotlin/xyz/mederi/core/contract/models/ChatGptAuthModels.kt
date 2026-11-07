package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/** 仅公开授权流程的显示状态；访问令牌、刷新令牌与 ID token 永不跨 AiCore 边界。 */
@Serializable
data class ChatGptAuthState(
    val providerId: String,
    val status: String,
    val authUrl: String? = null,
    val email: String? = null,
    val error: String? = null,
    val modelSyncError: String? = null,
)

/** 服务端实际返回的额度窗口。窗口缺失不能推断为 100% 剩余。 */
@Serializable
data class ChatGptUsageWindow(
    val windowDurationMins: Int,
    val usedPercent: Double,
    val resetsAt: Long? = null,
) {
    val remainingPercent: Double? get() = usedPercent.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { 100.0 - it }
}

@Serializable
data class ChatGptUsage(
    val windows: List<ChatGptUsageWindow> = emptyList(),
    val unavailableReason: String? = null,
    val manageUrl: String = CHATGPT_USAGE_URL,
)

const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"
const val CHATGPT_USAGE_API_UNAVAILABLE = "OFFICIAL_USAGE_API_UNAVAILABLE"
