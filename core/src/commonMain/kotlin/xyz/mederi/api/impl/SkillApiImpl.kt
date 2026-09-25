package xyz.mederi.api.impl

import xyz.mederi.api.SkillApi
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.skills.SkillManager
import xyz.mederi.skills.domain.SkillInfo

/**
 * SkillApi 实现（薄转调 + 异常转换）。
 */
class SkillApiImpl(private val manager: SkillManager) : SkillApi {

    override suspend fun list(): List<SkillInfo> = mederiCall { manager.list() }

    override suspend fun getRootDirectory(): String = mederiCall { manager.getRootDirectory() }

    override suspend fun setRootDirectory(path: String) {
        mederiCall { manager.setRootDirectory(path) }
    }

    override suspend fun install(url: String): SkillInfo = mederiCall { manager.install(url) }

    override suspend fun uninstall(name: String) {
        mederiCall { manager.uninstall(name) }
    }
}
