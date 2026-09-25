package xyz.mederi.provider.domain.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * 供应商 API 密钥（不序列化到 JSON）。
 *
 * 一个供应商可以有多个 API 密钥，其中一个为默认密钥。
 * 密钥值在返回给客户端时应脱敏处理（仅显示前缀和后缀）。
 *
 * 注意：此类仅存储脱敏后的密钥信息，不存储明文。
 * 明文密钥仅在创建/更新时传入，由基础设施层安全存储。
 *
 * @param id 系统生成的密钥唯一 ID。
 * @param name 密钥名称（如 "Primary"、"Backup"），便于用户识别。
 * @param maskedValue 脱敏后的密钥值（如 "sk-...7890"）。
 * @param isDefault 是否为默认密钥。一个供应商应只有一个默认密钥。
 */
@Serializable
data class ProviderApiKey(
    val id: String,
    val name: String,
    @Contextual
    val maskedValue: String,
    val isDefault: Boolean
) {
    companion object {
        /**
         * 对 API 密钥进行脱敏处理。
         *
         * 保留前3个字符和后4个字符，中间用 "..." 替代。
         * 例如: "sk-abcdef1234567890" -> "sk-...7890"
         *
         * 如果密钥长度不足8位，返回 "***"。
         *
         * @param apiKey 明文 API 密钥。
         * @return 脱敏后的密钥字符串。
         */
        fun mask(apiKey: String): String {
            if (apiKey.length <= 7) return "***"
            return "${apiKey.take(3)}...${apiKey.takeLast(4)}"
        }
    }
}
