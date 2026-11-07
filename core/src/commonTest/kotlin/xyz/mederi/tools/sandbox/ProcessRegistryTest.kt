package xyz.mederi.tools.sandbox

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ProcessRegistry 纯逻辑 + 宿主侧进程组回收（POSIX）测试。
 *
 * 覆盖：
 * - 注册表基本簿记（register/get/list/remove，pid<=0 忽略）
 * - list() 惰性剔除已死的进程组（有界内存）
 * - killGroup 对真实进程组整组回收（perl setpgrp 机制，不依赖沙箱）
 */
class ProcessRegistryTest {

    @Test
    fun registerIgnoresInvalidPid() {
        ProcessRegistry.clear()
        ProcessRegistry.register(0, null, "cmd", "/tmp")
        ProcessRegistry.register(-1, null, "cmd", "/tmp")
        assertTrue(ProcessRegistry.list().isEmpty())
    }

    @Test
    fun registerAndGet() {
        ProcessRegistry.clear()
        ProcessRegistry.register(1234, 1234, "python3 -m http.server", "/tmp")
        val entry = ProcessRegistry.get(1234)
        assertEquals(1234, entry?.pid)
        assertEquals(1234, entry?.pgid)
        assertEquals("python3 -m http.server", entry?.command)
        assertNull(ProcessRegistry.get(9999))
        ProcessRegistry.remove(1234)
        assertNull(ProcessRegistry.get(1234))
    }

    /** POSIX：spawn 一个独立进程组，注册 → 存活 → 整组强杀 → 消失。 */
    @Test
    fun killGroupTerminatesGroup() {
        if (isWindows) return // 本测试验证 posix 进程组机制
        ProcessRegistry.clear()
        val leader = ProcessBuilder(
            "/usr/bin/perl", "-e", """setpgrp(0,0) or die ${'$'}!; exec @ARGV""", "--", "sleep", "60"
        ).start()
        val pid = leader.pid()
        try {
            ProcessRegistry.register(pid, pid, "sleep 60", File("/tmp").absolutePath)
            val entry = checkNotNull(ProcessRegistry.get(pid))
            // start() 返回时 Perl 可能还没执行 setpgrp。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (leader.isAlive && !entry.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(entry.isAlive(), "leader group should be alive")

            ProcessRegistry.killGroup(pid, pid, force = true)
            assertTrue(leader.waitFor(5, TimeUnit.SECONDS), "SIGKILL should terminate the leader")
            assertFalse(ProcessRegistry.isAlive(pid, pid), "group should be dead after SIGKILL")
            assertTrue(ProcessRegistry.list().isEmpty(), "dead group should be pruned from list()")
        } finally {
            if (leader.isAlive) leader.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            ProcessRegistry.remove(pid)
        }
    }

    /** POSIX：非进程组（无 pgid）退化为单进程 kill。 */
    @Test
    fun killGroupFallsBackToSinglePid() {
        if (isWindows) return
        ProcessRegistry.clear()
        val p = ProcessBuilder("sleep", "60").start()
        val pid = p.pid()
        ProcessRegistry.register(pid, null, "sleep 60", File("/tmp").absolutePath)
        assertTrue(ProcessRegistry.get(pid)?.isAlive() == true)
        ProcessRegistry.killGroup(pid, null, force = true)
        p.waitFor()
        Thread.sleep(200)
        assertFalse(ProcessRegistry.isAlive(pid, null))
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")
}
