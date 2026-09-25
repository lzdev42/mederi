package xyz.mederi.core.contract.dto

import kotlinx.serialization.Serializable

// ==========================================
// Skill 管理请求/响应 DTO（遥控 REST 层）
// ==========================================

/** 设置 skill 根目录。 */
@Serializable
data class SetSkillsRootInput(val path: String)

/** 安装 skill：核心下载 zip → 解压 → 移入根目录。 */
@Serializable
data class InstallSkillInput(val url: String)

/** getSkillsRoot 的响应包装（String 直出 JSON 序列化会带引号，包一层类型安全）。 */
@Serializable
data class SkillsRootResponse(val path: String)
