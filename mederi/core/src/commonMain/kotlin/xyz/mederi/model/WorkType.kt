package xyz.mederi.domain.model

import kotlinx.serialization.Serializable

/**
 * 工作用途（非人格）。决定提示词引导 + 工具微调。
 *
 * - [WORK]：非程序员。处理文档、总结资料、协助创作。用户不一定是计算机专业人士。
 * - [CODE]：程序员。写代码、调试、工程实现。
 *
 * 两种用途都有全部工具，差异只在提示词引导。
 * 未来可能扩展更多类型（如代码语言细分），现在先留口子。
 */
@Serializable
enum class WorkType {
    WORK,
    CODE
}
