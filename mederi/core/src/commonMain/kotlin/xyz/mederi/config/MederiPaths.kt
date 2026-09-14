package xyz.mederi.config

import java.io.File

/**
 * Mederi 存储路径管理。
 *
 * 用户通过 [xyz.mederi.Mederi.create] 的 `configDir` 指定根目录。
 * 存储布局（双库架构）：
 *
 * ```
 * root/
 * ├── config.db        配置库（providers / api_keys / projects / mcp_servers / settings）
 * │                    极小、低频写、事务一致；一个文件 = 全部配置，可单独备份迁移
 * ├── data/
 * │   └── data.db      数据库（sessions / message_history / diffs）
 * │                    高频写、体积随使用增长；开发期可随时删库重建，配置无损
 * ├── skills/          skill 根目录（Koog 自动发现 SKILL.md 的扫描根）
 * │                    默认在此；用户可经 SkillApi.setRootDirectory 改到别处
 * └── preferences.json 设备级 UI 偏好（theme/上次打开），不进库（app:shared 层管理）
 * ```
 *
 * 不指定 configDir = 不持久化（纯内存模式）。
 *
 * @param rootPath 根目录的完整路径。
 */
class MederiPaths(rootPath: String) {

    /** 根目录 */
    val root: File = File(rootPath)

    /** 数据目录（data.db 所在） */
    val dataDir: File = File(root, "data")

    /** 配置库（config.db）：providers / api_keys / projects / mcp_servers / settings */
    val configDatabaseFile: File = File(root, "config.db")

    /** 数据库（data.db）：sessions / message_history / diffs */
    val dataDatabaseFile: File = File(dataDir, "data.db")

    /**
     * skill 根目录（默认）。
     *
     * Koog 的 `discoverSkills()` 扫描此目录下的 SKILL.md 完成自动发现；
     * 用户可经 SkillApi.setRootDirectory 改到别处（持久化在 settings 表，key=`skills.root`）。
     */
    val skillsDir: File = File(root, "skills")

    /**
     * 创建所有必要的目录。
     * 在 Mederi 初始化时调用，确保目录结构存在。
     */
    fun ensureDirectories() {
        root.mkdirs()
        dataDir.mkdirs()
        skillsDir.mkdirs()
    }
}
