package xyz.mederi.store.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.domain.model.Message
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.store.sqlite.SqliteHistoryStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST6：冷读并发化（A 方案）的行为证明。
 *
 * ① 多协程并发冷读同一未命中会话：single-flight 生效、返回一致完整列表
 *    （解码次数不可观测——不为生产类注入测试钩子；一致性是必须断言）。
 * ② **并发 load + append 交错（核心）**：8 协程并发冷读同一会话、同时 50 次
 *    append 交错落库。行为断言：
 *    - 最终 load 包含全部消息（预设 + 全部写入），seq 连续（无重复、无丢失）；
 *    - 每次中间观测都必须是最终列表的连续前缀（任何时刻内存里的列表都是
 *      DB 真相的合法前缀——append 只在尾部增长、swap 原子换引用）；
 *    - 内存真理源 == DB 真相（重开实例重水合逐位比对）。
 *    正确性锚点是 cache.computeIfAbsent 不覆盖已有条目 + 单写线程串行
 *    （见 SqliteHistoryStore 类 KDoc 竞态分析），不是 single-flight。
 */
class SqliteHistoryStoreConcurrencyTest {

    private fun message(id: String) = Message(
        id = id,
        sessionId = "n/a",
        role = MessageRole.USER,
        parts = listOf(MessagePart.Text("body of $id")),
        createdAt = "2026-11-09T00:00:00Z"
    )

    /** 建库 + store，返回 (driver, store)——driver 由测试在 finally/切换时关闭。 */
    private fun newStore(dbFile: File, capacity: Int = 64): Pair<JdbcSqliteDriver, SqliteHistoryStore> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        // 本测试用 WAL：rollback-journal 下读者 SELECT 持 SHARED 锁阻塞写事务 commit
        // （结构上保证"读快照 < 写内容"的竞态被序列化掉）；WAL 才暴露真正的
        // 冷读并发竞态——本测试正是要证明 computeIfAbsent 锚点在该竞态下不丢消息。
        MederiDataDatabase.Schema.create(driver)
        Mederi.migrateDataDb(driver)
        val wal = driver.executeQuery(
            null, "PRAGMA journal_mode=WAL",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else "?") }, 0
        ).value
        kotlin.test.assertEquals("wal", wal, "concurrency test requires WAL journal mode")
        return driver to SqliteHistoryStore(DatabaseManager(driver), capacity = capacity)
    }

    @Test
    fun concurrentColdLoadsReturnConsistentList() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-s6-consistent", ".db")
        dbFile.deleteOnExit()
        val (seedDriver, seedStore) = newStore(dbFile)
        val sid = "sess_single_flight"
        repeat(300) { i -> seedStore.append(sid, message("m_$i")) }
        seedDriver.close()

        // 全新实例（缓存冷）→ 12 协程并发冷读同一会话
        val (driver, coldStore) = newStore(dbFile)
        try {
            val results = (0 until 12).map {
                async(Dispatchers.Default) { coldStore.load(sid) }
            }.awaitAll()

            results.forEach { r ->
                assertEquals(300, r.size, "每次 load 都返回完整 300 条")
                assertEquals(results[0], r, "并发 load 必须返回一致列表")
            }
            assertTrue(coldStore.isCached(sid), "冷读后会话驻留缓存")

            TestObservations.record("SQLH_STORE_CONC_CONSISTENT", "consistent_${results.size}x_${results[0].size}")
        } finally {
            driver.close()
        }
    }

    @Test
    fun concurrentLoadAndAppendInterleaveLosesNothing() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-s6-interleave", ".db")
        dbFile.deleteOnExit()
        val presets = 500
        val writes = 50

        // 种子 store：落库预设消息（冷读解码耗时长，加宽读/写交错窗口）
        val (seedDriver, seedStore) = newStore(dbFile)
        val sid = "sess_interleave"
        repeat(presets) { i -> seedStore.append(sid, message("pre_$i")) }
        seedDriver.close()

        // 冷 store：指向同一 DB 文件，缓存全冷——并发 load + append 交错
        val (driver, store) = newStore(dbFile)
        try {
            val appenders = (0 until writes).map { i ->
                async(Dispatchers.Default) { store.append(sid, message("w_$i")) }
            }
            val readers = (0 until 8).map {
                async(Dispatchers.Default) {
                    (0 until 5).map { store.load(sid) }
                }
            }
            val observed = readers.awaitAll().flatten()
            appenders.awaitAll()

            val finalList = store.load(sid)

            // 零丢失：全部消息在场（预设 + 写入），无重复
            assertEquals(presets + writes, finalList.size, "final 必须包含全部消息（无丢失）")
            val finalIds = finalList.map { it.id }
            assertEquals(presets + writes, finalIds.toSet().size, "无重复 id（seq 连续）")
            assertTrue(finalIds.containsAll((0 until presets).map { "pre_$it" }), "预设消息缺失")
            assertTrue(finalIds.containsAll((0 until writes).map { "w_$it" }), "写入消息缺失")

            // 每次中间观测都是最终列表的连续前缀（seq 连续：无空洞、无乱序）
            observed.forEach { o ->
                assertEquals(finalList.take(o.size), o, "观测列表必须是 final 的连续前缀")
            }

            // DB 真相：重开实例重水合，与内存逐条比对
            driver.close()
            val (_, reopenStore) = newStore(dbFile)
            assertEquals(finalList, reopenStore.load(sid), "内存真理源必须等于 DB 真相")

            TestObservations.record("SQLH_STORE_CONC_APPEND", "no_loss_final_${finalList.size}_obs_${observed.size}")
        } finally {
            driver.close()
        }
    }

    /**
     * 冷读 vs replace 竞态：writer 的 replace **不 hydrate**（删 + 插直接落库并 swap），
     * 在 WAL 下其 swap 必然早于读者的 put 完成——这是"读者 stale 读集可能晚于写者
     * swap 落盘"唯一可确定性构造的交错（rollback-journal 下读者 SELECT 的 SHARED 锁
     * 会阻塞写事务 commit 至读结束，结构上掩盖该竞态）。正确实现靠 computeIfAbsent
     * 不覆盖：读者返回写者已 swap 的 [fresh]，丢弃 stale [presets]。
     */
    @Test
    fun concurrentColdLoadRacingReplaceKeepsFreshWrite() = runBlocking {
        val dbFile = File.createTempFile("mederi-dbm-s6-replace", ".db")
        dbFile.deleteOnExit()
        // capacity=1：预置后 load("other") 即可把 A 逐出缓存（构造真冷读）
        val (seedDriver, seedStore) = newStore(dbFile, capacity = 1)
        val sid = "sess_replace_race"
        val presets = 2000
        repeat(presets) { i -> seedStore.append(sid, message("pre_$i")) }
        seedStore.load("other")
        assertFalse(seedStore.isCached(sid), "sid 必须已逐出缓存（冷读前提）")
        seedDriver.close()

        // 冷 store：同一个 DB 文件、缓存全冷 → 并发 load + replace
        val (driver, store) = newStore(dbFile, capacity = 64)
        try {
            // fresh 列表刻意小（10 条）：replace 落库 ~ms 级，远早于读者 2000 条冷读
            // 解码（数十 ms）——确定性构造"读者 stale 快照晚于写者 swap 落盘"的交错
            val fresh = (0 until 10).map { i -> message("fresh_$i") }
            val reader = async(Dispatchers.Default) { store.load(sid) }
            val writer = async(Dispatchers.Default) { store.replace(sid, fresh) }
            val readBack = reader.await()
            writer.await()

            val finalList = store.load(sid)
            assertEquals(fresh.size, finalList.size, "final 大小 = replace 后的 10")
            assertTrue(finalList.map { it.id }.containsAll(fresh.map { it.id }),
                "final 必须是 replace 的新内容，不得被冷读 stale 快照覆盖")
            assertEquals(fresh, finalList, "内存真理源必须等于 replace 结果")

            // 中间冷读观测：要么是旧预设（replace 前返回）、要么是新内容，绝不能混合/丢
            val readBackIds = readBack.map { it.id }.toSet()
            assertTrue(
                readBackIds.all { it != null && it.startsWith("pre_") } ||
                    readBackIds.all { it != null && it.startsWith("fresh_") },
                "冷读返回必须是单一阵营的完整列表，实际前缀=${readBackIds.firstOrNull()}"
            )

            // DB 真相（fresh 已落库）与内存一致
            driver.close()
            val (_, reopenStore) = newStore(dbFile)
            assertEquals(finalList, reopenStore.load(sid), "内存真理源必须等于 DB 真相")

            TestObservations.record("SQLH_STORE_CONC_REPLACE", "no_clobber_fresh_${finalList.size}")
        } finally {
            driver.close()
        }
    }
}