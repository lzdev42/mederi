package xyz.mederi.tools

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级文件写注册表：防止并发工具调用同时修改同一文件（丢更新/互相覆盖）。
 *
 * 背景：Koog 工具执行节点 parallel=true——同一条消息的多个工具调用、以及并行 spawn 的
 * 子代理（各自 turn 各自 FileSystemTools 实例）都会并发落盘。实例级锁无效，必须进程级。
 *
 * 语义 = try-lock + 拒绝（不等待）：write_file/edit_file 落盘前 tryAcquire，
 * 任一路径已被其他调用占用则工具直接返回错误，由 AI 下一轮自行重排——
 * 与"信任 AI 调度"哲学一致，代码不替它排队。
 *
 * 键 = canonical absolute path：相对路径 / "./" 前缀 / 符号链接写法归一到同一键。
 * 多路径（未来 apply_patch 类多文件工具）在 synchronized 块内整体检查+登记，
 * 避免"两个并发调用各自检查都通过然后都登记"的窗口。
 */
object FileWriteRegistry {

    /** 已被占用的文件键集合。 */
    private val lockedPaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * 尝试占用全部路径（原子：任一冲突则一个都不登记）。
     *
     * @return 冲突的文件（供错误信息展示）；null = 全部登记成功
     */
    fun tryAcquire(paths: List<File>): File? = acquireKeys(canonicalKeys(paths))

    /** 带稳定键快照的锁租约：路径在操作中被重命名或替换，也必须释放最初获取的键。 */
    internal sealed interface Acquisition {
        data class Acquired(val lease: Lease) : Acquisition
        data class Conflict(val file: File) : Acquisition
    }

    internal class Lease internal constructor(private val keys: List<String>) : AutoCloseable {
        private var closed = false
        override fun close() {
            synchronized(FileWriteRegistry) {
                // 重复 close 不能误释放随后另一个调用获取的同名锁。
                if (!closed) {
                    closed = true
                    keys.forEach { lockedPaths.remove(it) }
                }
            }
        }
    }

    internal fun tryAcquireLease(paths: List<File>): Acquisition {
        val keys = canonicalKeys(paths)
        val conflict = acquireKeys(keys)
        return if (conflict == null) Acquisition.Acquired(Lease(keys)) else Acquisition.Conflict(conflict)
    }

    private fun canonicalKeys(paths: List<File>): List<String> =
        paths.map { it.canonicalFile.absolutePath }.distinct().sorted()

    private fun acquireKeys(keys: List<String>): File? = synchronized(this) {
        val conflict = keys.firstOrNull { it in lockedPaths }
        if (conflict != null) return@synchronized File(conflict)
        keys.forEach { lockedPaths.add(it) }
        null
    }

    /** 释放路径（写完或出错后调用；幂等，未占用路径移除为 no-op）。 */
    fun release(paths: List<File>) {
        paths.forEach { lockedPaths.remove(it.canonicalFile.absolutePath) }
    }
}
