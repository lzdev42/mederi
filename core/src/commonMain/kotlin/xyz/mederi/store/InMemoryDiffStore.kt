package xyz.mederi.store

import xyz.mederi.tools.diff.TurnDiff

/**
 * 内存版 DiffStore，供测试和无持久化场景使用。
 */
class InMemoryDiffStore : DiffStore {

    private val diffs = mutableMapOf<String, MutableList<TurnDiff>>()

    override suspend fun save(diff: TurnDiff) {
        diffs.getOrPut(diff.sessionId) { mutableListOf() }.add(diff)
    }

    override suspend fun list(sessionId: String): List<TurnDiff> {
        return diffs[sessionId]?.sortedBy { it.createdAt } ?: emptyList()
    }

    override suspend fun get(sessionId: String, messageId: String?): TurnDiff? {
        val list = diffs[sessionId] ?: return null
        return if (messageId == null) {
            list.maxByOrNull { it.createdAt }
        } else {
            list.find { it.messageId == messageId }
        }
    }

    override suspend fun delete(sessionId: String) {
        diffs.remove(sessionId)
    }
}
