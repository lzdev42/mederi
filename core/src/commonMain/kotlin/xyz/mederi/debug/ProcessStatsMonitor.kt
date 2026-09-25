package xyz.mederi.debug

import com.sun.management.OperatingSystemMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * 进程级真实资源监控（JVM）：CPU 使用率 + 内存（堆 / RSS）。
 *
 * 数据源：
 * - CPU：[OperatingSystemMXBean.getProcessCpuLoad]（com.sun.management，HotSpot 全平台支持），
 *   0.0~1.0，含 JVM 全部线程（GC / JIT / Compose 渲染 / Ktor / agent），即"整个进程"口径。
 *   该值是自上次调用以来的均值，连续快速调用会互相稀释——调用方以 >= 500ms 间隔轮询。
 * - 堆：MemoryMXBean（used / committed / max），JVM 视角，不含堆外。
 * - RSS：进程真实常驻内存（含堆外、metaspace、JCEF 等 native），按平台读取：
 *   - Linux：/proc/self/statm 第二列（resident pages × page size）；
 *   - macOS：无 /proc，用 `ps -o rss= -p <pid>`（几百 ms 一次的外部进程读数开销可忽略）；
 *   - Windows：两者皆无该语义 → null（UI 不显示该行，不造假值）。
 *
 * 轻量实现，无后台采样线程：每次 [snapshot] 同步取数，监控 UI 自己控制轮询节奏。
 */
object ProcessStatsMonitor {

    private val osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
    private val memoryBean = ManagementFactory.getMemoryMXBean()

    private val pid: Long by lazy {
        runCatching { ProcessHandle.current().pid() }.getOrElse { -1L }
    }

    // JDK 没有公开 page size API；Linux x86_64/arm64 皆为 4096。
    // 仅 Linux /proc 路径依赖此值，macOS 走 ps 输出（本身以 KB 计）不受影响。
    private const val DEFAULT_PAGE_SIZE_BYTES = 4096L

    /** 一次性进程资源快照；所有字段为瞬时读数，两次调用间隔由调用方保证（>= 500ms）。 */
    fun snapshot(): ProcessStats {
        val cpuLoad = runCatching { osBean.processCpuLoad }.getOrElse { Double.NaN }
        val heap = memoryBean.heapMemoryUsage

        return ProcessStats(
            cpuUsage = if (cpuLoad.isNaN() || cpuLoad < 0) null else cpuLoad,
            cpuCores = osBean.availableProcessors,
            heapUsedBytes = heap.used,
            heapCommittedBytes = heap.committed,
            heapMaxBytes = heap.max.takeIf { it > 0 },
            rssBytes = readRssBytes(),
            timestampMillis = System.currentTimeMillis()
        )
    }

    /** 进程 RSS（字节）；平台不支持或读取失败返回 null（如实上报，不造默认值）。 */
    private fun readRssBytes(): Long? {
        return runCatching {
            when {
                // Linux：/proc/self/statm 第二列 = resident pages
                File("/proc/self/statm").exists() -> {
                    val fields = File("/proc/self/statm").readText().trim().split(Regex("\\s+"))
                    fields.getOrNull(1)?.toLongOrNull()?.times(DEFAULT_PAGE_SIZE_BYTES)
                }
                // macOS / 其他 Unix：ps 输出 KB，3s 超时防挂起
                pid > 0 -> {
                    val proc = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start()
                    val ok = proc.waitFor(3, TimeUnit.SECONDS)
                    val output = if (ok) proc.inputStream.bufferedReader().readText().trim() else ""
                    proc.destroyForcibly()
                    output.toLongOrNull()?.times(1024)
                }
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * 进程资源快照（不可变值对象）。
 *
 * @property cpuUsage CPU 使用率 0.0~1.0（整个进程口径，已含全部线程）；取不到为 null
 * @property cpuCores 逻辑核数（UI 展示 "0.35 (8 cores)" 或折算核时用）
 * @property heapUsedBytes 堆已用字节
 * @property heapCommittedBytes 堆已提交字节（OS 已实际划给 JVM 的堆）
 * @property heapMaxBytes 堆上限字节；未固定上限时为 null
 * @property rssBytes 进程 RSS 字节（真实常驻内存，含 native/堆外）；取不到为 null
 * @property timestampMillis 快照采集时刻（epoch ms）
 */
data class ProcessStats(
    val cpuUsage: Double?,
    val cpuCores: Int,
    val heapUsedBytes: Long,
    val heapCommittedBytes: Long,
    val heapMaxBytes: Long?,
    val rssBytes: Long?,
    val timestampMillis: Long,
)
