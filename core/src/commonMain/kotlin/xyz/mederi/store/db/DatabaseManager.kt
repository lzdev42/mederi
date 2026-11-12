package xyz.mederi.store.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mederi.config.MederiIdentity
import xyz.mederi.db.data.MederiDataDatabase
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * data.db 的进程内存储管理器——所有 data.db 写操作的唯一串行入口。
 *
 * 设计定位（存储层重构）：
 * - **单写队列**：全部写操作经 [write] 在专用单线程上串行执行。进程内写竞争
 *   （SQLITE_BUSY）在构造上消失，而非靠忙等待或重试兜底。
 * - **busy_timeout**：跨进程/外部 sqlite3 的兜底——另一进程持写锁时等待而非立即失败。
 *   进程内不依赖它；"一个 data.db 一个写进程"仍是约定（见 docs/architecture/01-core.md）。
 * - **DB 是 checkpoint**：写穿透语义由上层 store 保证（commit 成功才确认交付）。
 *   本类不做 schema 初始化（Schema.create/迁移由装配层与测试显式执行——
 *   迁移测试需要先手工建遗留 schema 再构造本类）。
 * - **不变量**：[write] 的 block 内禁止任何 IO/网络/挂起（JDBC 同步调用除外）——
 *   写事务内零 IO，防止慢操作阻塞单写队列。block 失败即事务回滚，整体重放安全。
 *
 * config.db 不在本类管辖（低频写，保留独立 store）。
 */
class DatabaseManager(val driver: SqlDriver) {

    /** 唯一共享的 SQLDelight 数据库实例（此前每个 store 各建一个，收敛为一个）。 */
    val database: MederiDataDatabase = MederiDataDatabase(driver)

    private val writeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, MederiIdentity.PRODUCT_NAME + "-data-writer").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    init {
        // busy_timeout 经 PRAGMA 下发并读回校验（Properties 之外的双保险：
        // 测试自建 driver 不经过 Mederi.createDriver 时同样生效）。
        val applied = driver.executeQuery(
            null,
            "PRAGMA busy_timeout=$BUSY_TIMEOUT_MS",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0
        ).value
        if (applied != BUSY_TIMEOUT_MS) {
            throw IllegalStateException("PRAGMA busy_timeout apply failed: applied=$applied")
        }
    }

    /**
     * data.db 写操作的唯一入口：单写线程串行执行；SQLITE_BUSY（跨进程锁竞争）有界重试。
     *
     * block 必须是纯 JDBC 同步调用（事务体失败即回滚，整体重放安全），
     * 禁止内部 IO/网络/挂起。drainAndClose 之后的调用抛 [IllegalStateException]。
     */
    suspend fun <T> write(block: () -> T): T {
        inFlight.incrementAndGet()
        try {
            check(!closed.get()) { "DatabaseManager closed" }
            return withContext(writeDispatcher) { withBusyRetry(block) }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * 幂等关闭：拒绝新写 → 等待在飞写排干（限时）→ 关闭写线程。
     * 关闭后再调 [write] 抛 [IllegalStateException]。
     */
    suspend fun drainAndClose(timeoutMs: Long) {
        if (!closed.compareAndSet(false, true)) return
        withTimeoutOrNull(timeoutMs) {
            while (inFlight.get() > 0) delay(10)
        }
        writeDispatcher.close()
    }

    /** BUSY/locked 有界重试：最多 [MAX_BUSY_RETRIES] 次，退避随尝试序号线性增长。 */
    private fun <T> withBusyRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                val isBusy = message.contains("locked", ignoreCase = true) ||
                    message.contains("busy", ignoreCase = true)
                if (isBusy && attempt < MAX_BUSY_RETRIES) {
                    attempt++
                    Thread.sleep(BUSY_RETRY_BACKOFF_MS * attempt)
                } else {
                    throw e
                }
            }
        }
    }

    private companion object {
        const val BUSY_TIMEOUT_MS = 5000L
        const val MAX_BUSY_RETRIES = 5
        const val BUSY_RETRY_BACKOFF_MS = 20L
    }
}
