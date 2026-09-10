package xyz.mederi.store

import xyz.mederi.tools.diff.TurnDiff

/**
 * Diff 存储接口。
 *
 * 管理一次 turn 产生的文件变更，支持按 session / message 查询。
 */
interface DiffStore {
    /**
     * 保存一次 turn 的 diff。
     */
    suspend fun save(diff: TurnDiff)

    /**
     * 查询指定 session 的所有 diff（按创建时间升序）。
     */
    suspend fun list(sessionId: String): List<TurnDiff>

    /**
     * 查询指定 message 的 diff。messageId 为 null 时返回该 session 最近一次 diff。
     */
    suspend fun get(sessionId: String, messageId: String?): TurnDiff?

    /**
     * 删除指定 session 的所有 diff。
     */
    suspend fun delete(sessionId: String)
}
