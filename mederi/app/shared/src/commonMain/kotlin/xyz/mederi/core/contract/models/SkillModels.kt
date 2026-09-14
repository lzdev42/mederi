package xyz.mederi.core.contract.models

import kotlinx.serialization.Serializable

/**
 * 跨平台 skill 条目（UI 列表展示用）。
 *
 * 由 core `SkillInfo` 映射；location 为 SKILL.md 绝对路径。
 */
@Serializable
data class SkillItem(
    val name: String,
    val description: String,
    val location: String = "",
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: String? = null,
)
