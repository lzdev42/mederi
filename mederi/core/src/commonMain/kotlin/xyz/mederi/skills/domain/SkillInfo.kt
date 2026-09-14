package xyz.mederi.skills.domain

import kotlinx.serialization.Serializable

/**
 * 已发现的 agent skill 信息。
 *
 * 与 Koog `ai.koog.skills.model.Skill` 对齐（由 discoverSkills 解析 SKILL.md frontmatter 产出）。
 * 对外暴露 UI 需要的摘要字段；location 为 SKILL.md 的绝对路径。
 */
@Serializable
data class SkillInfo(
    /** 唯一 skill 名（= 父目录名）。 */
    val name: String,
    /** 简短描述：做什么、何时用。 */
    val description: String,
    /** SKILL.md 的绝对路径。 */
    val location: String,
    /** 可选许可证名或引用。 */
    val license: String? = null,
    /** 可选环境兼容性要求。 */
    val compatibility: String? = null,
    /** 可选空格分隔的预批准工具列表。 */
    val allowedTools: String? = null,
)
