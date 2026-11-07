package xyz.mederi.tools

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级编译协调器：同一项目同一时刻只允许一个编译命令执行，后来的自动排队等待。
 *
 * 背景：并行 spawn 的执行器子代理各自跑 bash 编译（`./gradlew …`）时，A 先完成触发全模块
 * 编译，B 的文件还没写完 → 假性编译失败（时序撞车，非真实 bug）。[FileWriteRegistry] 只管
 * 文件写并发，bash 是外部进程不在其管辖。本协调器在 bash 工具层（`compilation=true` 时）加
 * 项目级互斥：后来的编译命令挂起等待正在进行的编译完成（或超时/取消释放锁）后再执行，
 * AI 不参与协调逻辑——工具透明排队。
 *
 * 语义 = 排队等待（与 [FileWriteRegistry] 的 try-lock + 拒绝不同）：编译是重操作，拒绝后
 * 下轮重试不如直接排队；等待挂起协程不占线程（suspend [Mutex]）。
 *
 * 锁释放保障：[acquire] 返回 [Lease]，调用方 `use { }` 在 finally 调 [Lease.close]——
 * 即使编译超时进程被杀、协程被取消（STOP_AGENT / turn abort），finally 都会执行并释放锁；
 * 编译自身的 `bash` `timeout_seconds` 兜底，不会永挂。
 *
 * 键 = 项目主目录 canonical absolute path（与 [FileWriteRegistry] 同模式），
 * 不同项目不互斥（不同代码库编译不冲突）。
 *
 * 不可重入：[Mutex] 非重入，bash 工具不会在同一协程内重入 acquire，无死锁风险。
 */
object CompilationCoordinator {

    /** per-project-key 的互斥锁。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 编译锁租约：[AutoCloseable]，[close] 释放底层 [Mutex]。
     * 幂等（重复 close 只释放一次），防 finally 与异常路径重复 unlock。
     */
    class Lease internal constructor(private val mutex: Mutex) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (!closed) {
                closed = true
                mutex.unlock()
            }
        }
    }

    /**
     * 获取项目编译锁（排队等待；返回 [Lease]，用完 close 释放）。
     *
     * [Mutex.lock] 是 suspend：锁被占用时挂起当前协程，不占线程，直到持有者释放。
     */
    suspend fun acquire(projectKey: String): Lease {
        val mutex = locks.computeIfAbsent(projectKey) { Mutex() }
        mutex.lock()
        return Lease(mutex)
    }
}
