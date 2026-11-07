package xyz.mederi.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CompilationCoordinator] 并发互斥测试。
 *
 * 验证三点：
 * - 同 project key 的 acquire 串行化（后来的排队等待，critical section 不重叠）；
 * - 不同 project key 不互斥（各自并行）；
 * - Lease.close 幂等，释放后可重新获取。
 */
class CompilationCoordinatorTest {

    @Test
    fun `same project key serializes concurrent acquire`() = runBlocking {
        val key = "/test-proj-${System.nanoTime()}"
        val order = mutableListOf<Int>()
        val c = CompilationCoordinator

        val j1 = launch {
            c.acquire(key).use {
                order.add(1) // enter critical section
                delay(100)   // hold the lock
                order.add(2) // still inside, before release
            }
        }
        val j2 = launch {
            c.acquire(key).use {
                order.add(3) // must run only after j1 releases
            }
        }
        j1.join(); j2.join()
        // j2 的 critical section 必须在 j1 释放后才开始 → [1, 2, 3]
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `different project keys do not block each other`() = runBlocking {
        val order = mutableListOf<Int>()
        val c = CompilationCoordinator

        val j1 = launch {
            c.acquire("/proj-a-${System.nanoTime()}").use {
                order.add(1)
                delay(100)
                order.add(2)
            }
        }
        val j2 = launch {
            c.acquire("/proj-b-${System.nanoTime()}").use {
                order.add(3) // different key → no wait, runs while j1 holds its lock
            }
        }
        j1.join(); j2.join()
        // j2 不等 j1 → 3 在 2 之前
        assertEquals(listOf(1, 3, 2), order)
    }

    @Test
    fun `lease close is idempotent and releases the lock`() = runBlocking {
        val key = "/test-lease-${System.nanoTime()}"
        val lease = CompilationCoordinator.acquire(key)
        lease.close()
        lease.close() // 幂等，不抛异常
        // 释放后能立即重新获取（证明锁已释放）
        CompilationCoordinator.acquire(key).use {}
    }
}
