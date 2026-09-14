package xyz.mederi.api

import xyz.mederi.skills.domain.SkillInfo

/**
 * Skill 管理 API（内核侧）。
 *
 * UI 只做薄触发：列表 / 设根目录 / 安装（下载+解压）/ 卸载，
 * 全部文件系统操作收敛在 core（遥控端经 REST 桥也可用）。
 *
 * 已存在资源（skill 重装）幂等覆盖；不存在抛
 * [xyz.mederi.api.exception.MederiNotFoundException]。
 */
interface SkillApi {

    /** 扫描 skill 根目录，返回全部已发现 skill。 */
    suspend fun list(): List<SkillInfo>

    /** 当前 skill 根目录路径。 */
    suspend fun getRootDirectory(): String

    /** 设置 skill 根目录（持久化 + 确保目录存在）。 */
    suspend fun setRootDirectory(path: String)

    /** 从 [url] 下载 zip 安装 skill（自动解压 + 移入根目录）。 */
    suspend fun install(url: String): SkillInfo

    /** 卸载 skill（删除其目录）。 */
    suspend fun uninstall(name: String)
}
