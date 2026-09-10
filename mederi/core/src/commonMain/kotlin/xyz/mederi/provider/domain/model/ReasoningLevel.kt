package xyz.mederi.provider.domain.model

import kotlinx.serialization.Serializable

/**
 * 推理级别（可序列化版本，用于 JSON 配置）。
 *
 * NONE 是默认值，表示不启用推理。
 * LOW/MEDIUM/HIGH/MAX 是推理强度，不同供应商通过 [ReasoningParameter] 映射到各自的 API 值。
 */
@Serializable
enum class ReasoningLevel {
    NONE, LOW, MEDIUM, HIGH, MAX
}
