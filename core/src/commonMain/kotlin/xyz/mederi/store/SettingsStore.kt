package xyz.mederi.store

/**
 * 通用 key-value 配置存储（纯持久化，无业务逻辑）。
 *
 * 存零散但需跨进程/跨重启保持的配置项（如 skill 根目录 `skills.root`）。
 * 实现可注入（[xyz.mederi.config.MederiConfig.settingsStore]）。
 * 内置实现：[xyz.mederi.store.sqlite.SqliteSettingsStore]（config.db）、
 * [InMemorySettingsStore]。
 */
interface SettingsStore {

    /** 读取配置项；不存在返回 null。 */
    suspend fun get(key: String): String?

    /** 写入配置项（upsert）。 */
    suspend fun set(key: String, value: String)

    /** 删除配置项；不存在时静默返回。 */
    suspend fun delete(key: String)
}
