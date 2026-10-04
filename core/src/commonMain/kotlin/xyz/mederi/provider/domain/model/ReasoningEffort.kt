package xyz.mederi.provider.domain.model

/**
 * 推理强度 API 值常量。
 *
 * 各供应商通过 [ReasoningParameter] 将 [ReasoningLevel] 映射到这些 API 值。
 */
object ReasoningEffort {
    const val NONE = "none"
    const val MINIMAL = "minimal"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val XHIGH = "xhigh"
    const val MAX = "max"
}
