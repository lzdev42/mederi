package xyz.mederi.store.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.mederi.Mederi
import xyz.mederi.db.data.MederiDataDatabase
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ST5：drainAndClose 语义——排干在队写（限时）、drain 后拒绝新写、幂等。
 */
class DatabaseManagerDrainTest {

    @Test
    fun drainedThenRejected() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            MederiDataDatabase.Schema.create(driver)
            Mederi.migrateDataDb(driver)
            val db = DatabaseManager(driver)

            // 在队慢写（200ms）：占用单写队列
            var started = false
            var done = false
            val job = async(Dispatchers.Default) { db.write { started = true; Thread.sleep(200); done = true } }
            // 等慢写真正进入单写线程（in-flight）再 drain——否则竞态：写尚未启动 drain 已完成
            while (!started) kotlinx.coroutines.delay(10)

            // drain：等待在飞写排干（限时 2s）
            db.drainAndClose(2000)
            assertTrue(done, "drain 必须排干在队慢写")

            // drain 后拒绝新写
            assertFailsWith<IllegalStateException>("drain 后新写必须被拒绝") { db.write { } }

            // 幂等：二次 drain 不抛
            db.drainAndClose(2000)

            job.await()
            TestObservations.record("CLOSE_DRAIN_QUEUE", "drained_then_rejected")
        } finally {
            driver.close()
        }
    }
}
