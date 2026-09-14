package xyz.mederi.skills

import xyz.mederi.skills.domain.SkillInfo

/**
 * Skill 管理（唯一真理源）。
 *
 * 职责边界：
 * - 扫描 / 发现：包装 Koog `discoverSkills()`（扫描根目录下的 SKILL.md，自动发现即安装）
 * - 根目录配置：skill 根目录持久化在 settings 表（key=`skills.root`，默认 `~/.mederi/skills`）
 * - 安装：下载 zip 包 → 解压 → 移入根目录（Koog 下次扫描自动发现）
 * - 卸载：删除 skill 所在目录
 *
 * 与管理/引擎的耦合为零：本接口只操作文件系统 + 调 Koog skills 模块，
 * 不感知 AIAgent / 提示词 / 会话。
 */
interface SkillManager {

    /** 扫描根目录，返回全部已发现 skill（name + description 等摘要）。 */
    suspend fun list(): List<SkillInfo>

    /** 当前 skill 根目录路径。 */
    suspend fun getRootDirectory(): String

    /** 设置 skill 根目录（持久化），并确保目录存在。 */
    suspend fun setRootDirectory(path: String)

    /**
     * 安装 skill：从 [url] 下载 zip 包 → 解压 → 移入根目录。
     * 已存在同名 skill 时先删除再装（install 幂等）。
     *
     * @return 安装后发现的 skill 信息。
     * @throws xyz.mederi.api.exception.MederiValidationException 压缩包内没有有效 SKILL.md / 下载失败。
     */
    suspend fun install(url: String): SkillInfo

    /** 卸载 skill：删除其在根目录下的整个目录。不存在抛 [xyz.mederi.api.exception.MederiNotFoundException]。 */
    suspend fun uninstall(name: String)
}
