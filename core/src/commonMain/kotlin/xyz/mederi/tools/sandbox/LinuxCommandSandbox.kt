package xyz.mederi.tools.sandbox

import xyz.mederi.api.exception.MederiStateException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linux mount namespace：根只读，白名单原路径读写，独立 PID namespace 隔离宿主 /proc。
 * JVM-only 宿主能力；与 macOS 的路径/设备规则分离，不能 bind /dev/std* 的 magic links。
 * 探测与执行复用同一份已准备的挂载参数；失败禁止退回裸命令。
 */
internal class LinuxCommandSandbox(
    private val projectDirs: List<String>,
    private val extraPaths: List<String>,
    @Suppress("UNUSED_PARAMETER") home: String = System.getProperty("user.home"),
    private val tempDirs: List<String> = listOfNotNull(
        "/tmp", System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }, System.getProperty("java.io.tmpdir")
    ),
    private val probe: (List<String>) -> ProbeResult = ::runProbe,
    private val protectedDirectories: List<String> = emptyList(),
    private val deniedPaths: List<String> = emptyList(),
    val verifiedWorkspaceRoot: xyz.mederi.workspace.VerifiedWorkspaceRoot? = null,
) {
    internal data class ProbeResult(val exitCode: Int?, val output: String)
    private data class Preparation(val prefix: List<String>?, val error: String?)

    private data class Policy(val networkAccess: Boolean, val fullAccess: Boolean)
    private val preparations = java.util.concurrent.ConcurrentHashMap<Policy, Preparation>()

    private fun preparation(networkAccess: Boolean, fullAccess: Boolean = false): Preparation {
        val policy = Policy(networkAccess || fullAccess, fullAccess)
        // 黑名单的目标类型/存在性按每次启动重查，不复用陈旧 mount snapshot。
        return if (deniedPaths.isNotEmpty()) prepare(policy) else preparations.computeIfAbsent(policy, ::prepare)
    }

    private fun prepare(policy: Policy): Preparation =
        try {
            val version = probe(listOf("bwrap", "--version"))
            if (version.exitCode != 0) {
                Preparation(null, "无法启动 bubblewrap：${version.output}。安装或检查 bwrap：" +
                    "sudo apt install bubblewrap / sudo dnf install bubblewrap / sudo pacman -S bubblewrap")
            } else {
                val prefix = prepareMounts(policy.networkAccess, policy.fullAccess)
                // 包含实际白名单、设备和 PID 隔离，不能用精简参数烟测代替。
                val result = probe(prefix + listOf("--", "/bin/true"))
                if (result.exitCode == 0) Preparation(prefix, null)
                else Preparation(null, "bubblewrap 完整配置探测失败（exit=${result.exitCode}）：${result.output}。" +
                    "请检查以上原始错误；可能涉及 namespace/AppArmor、挂载路径或权限，命令未执行。")
            }
        } catch (e: java.io.IOException) {
            Preparation(null, "Linux 沙盒路径或进程准备失败：${e.message}")
        } catch (e: IllegalArgumentException) {
            Preparation(null, "Linux 沙盒路径配置错误：${e.message}")
        }

    fun status(shell: String): SandboxStatus {
        val ready = preparation(false)
        return SandboxStatus(
            backend = if (ready.prefix != null) "bubblewrap" else "none",
            available = ready.prefix != null,
            shell = shell,
            detail = ready.error?.let { "$it Linux 命令已阻止，不会无沙盒运行。" }
                ?: "Linux bubblewrap：工作区和临时目录可写，保护目录只读；网络命名空间及 seccomp 默认阻止出站和宿主 socket 连接。"
        )
    }

    fun wrap(command: List<String>, networkAccess: Boolean = false, fullAccess: Boolean = false): List<String> {
        val prepared = preparation(networkAccess, fullAccess)
        val prefix = prepared.prefix ?: throw MederiStateException(prepared.error ?: "Linux command sandbox unavailable")
        return prefix + "--" + command
    }

    private fun prepareMounts(networkAccess: Boolean, fullAccess: Boolean): List<String> {
        val paths = SandboxPaths(
            projectDirs = projectDirs,
            extraWritablePaths = if (fullAccess) emptyList() else extraPaths,
            tempDirs = if (fullAccess) emptyList() else tempDirs,
            protectedDirectories = if (fullAccess) emptyList() else protectedDirectories,
            deniedPaths = deniedPaths,
            verifiedWorkspaceRoot = verifiedWorkspaceRoot
        )
        val denied = paths.deniedCanonicalPaths
        denied.forEach { path ->
            require(Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Linux denied path does not exist; command blocked without creating it: $path"
            }
            require(Files.isDirectory(path) || Files.isRegularFile(path)) {
                "Linux denied path is not a regular file or directory; command blocked: $path"
            }
        }
        // 显式拒绝或元数据保护内的额外根不能在准备阶段创建目录，也无需重新挂载为可写。
        val writableRoots = paths.writableRoots.filter(paths::allowsWrite)
        // 先完整验证，避免非法后续配置造成前面目录已在宿主创建的半成功。
        writableRoots.forEach { root ->
            var ancestor = root
            while (!Files.exists(ancestor)) ancestor = ancestor.parent
            require(Files.isDirectory(ancestor) || ancestor == root) { "Writable path has a non-directory parent: $root" }
        }
        writableRoots.forEach { root -> if (!Files.exists(root)) Files.createDirectories(root) }
        val bwrap = buildList {
            addAll(listOf("bwrap", "--unshare-pid", "--die-with-parent", "--cap-drop", "ALL",
                if (fullAccess) "--bind" else "--ro-bind", "/", "/"))
            if (!networkAccess) add("--unshare-net")

            // 1. controlRoot 只读 bind 在前
            paths.controlRoots.filter { Files.exists(it) }.forEach {
                addAll(listOf("--ro-bind", it.toString(), it.toString()))
            }

            // 2. 只有当前 verified workingDirectory / 必要 artifact 产物子路径精确可写 bind 在后
            writableRoots.forEach { addAll(listOf("--bind", it.toString(), it.toString())) }

            // 3. 工作副本内保护目录 ro-bind 再后
            paths.protectedNamesPaths.filter { Files.exists(it) }.forEach {
                addAll(listOf("--ro-bind", it.toString(), it.toString()))
            }

            // /tmp 直接 bind 宿主白名单，保留跨命令临时文件和构建 daemon socket。
            // /dev 由 bwrap 创建；不再重绑 /dev/stdin/out/err（它们可能指向 pipe/socket）。
            addAll(listOf("--dev", "/dev", "--proc", "/proc"))
            denied.forEach { path ->
                if (Files.isDirectory(path)) {
                    // 新 tmpfs inode 的000权限和只读挂载不触碰宿主目录权限。
                    addAll(listOf("--perms", "000", "--tmpfs", path.toString(), "--remount-ro", path.toString()))
                } else {
                    // 由bwrap创建匿名000 inode，再只读bind到目标；绝不能用--file覆盖宿主原文件。
                    addAll(listOf("--perms", "000", "--ro-bind-data", "MASK_FD", path.toString()))
                }
            }
            if (!networkAccess) addAll(listOf("--seccomp", "3"))
        }
        val launcher = if (denied.any { Files.isRegularFile(it) }) {
            val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { Files.isExecutable(java.nio.file.Path.of(it)) }
                ?: throw IllegalArgumentException("Linux file deny masks require Bash 4.1 or newer; command blocked")
            // -p 禁止BASH_ENV/导入函数等宿主环境影响可信FD准备；路径与命令仍只按argv传递。
            listOf(bash, "--noprofile", "--norc", "-p", "-c", MASK_FD_LAUNCHER, "mederi-deny-mask") + bwrap
        } else bwrap
        if (networkAccess) return launcher
        // BPF 通过新建匿名 pipe 传递，既无可篡改的缓存文件，也不占 payload 的 stdin。
        // 参数始终按 argv 传入；固定脚本从不解释命令、路径或策略数据为 shell 代码。
        return listOf("/bin/sh", "-c", SECCOMP_LAUNCHER, "mederi-seccomp", seccompProgram()) + launcher
    }

    companion object {
        internal const val MASK_FD_LAUNCHER = "set -e; arguments=(); needs_fd=0; options=1; for arg in \"\$@\"; do if ((needs_fd)); then exec {mask_fd}</dev/null; arguments+=(\"\$mask_fd\"); needs_fd=0; else arguments+=(\"\$arg\"); if ((options)); then if [[ \$arg == --ro-bind-data ]]; then needs_fd=1; elif [[ \$arg == -- ]]; then options=0; fi; fi; fi; done; exec \"\${arguments[@]}\""
        internal const val SECCOMP_LAUNCHER = "exec 4<&0; policy=\$1; shift; printf '%b' \"\$policy\" | (exec 3<&0; exec 0<&4; exec 4<&-; exec \"\$@\")"

        /** 禁止 connect/send 系列，也禁止 io_uring 绕过普通 socket 系统调用。仅支持经确认的 ABI。 */
        internal fun seccompProgram(architecture: String = System.getProperty("os.arch")): String {
            val (auditArch, denied) = when (architecture.lowercase()) {
                "amd64", "x86_64" -> 0xc000003e.toInt() to listOf(42, 44, 46, 307, 425)
                "aarch64", "arm64" -> 0xc00000b7.toInt() to listOf(203, 206, 211, 269, 425)
                else -> throw IllegalArgumentException("Unsupported Linux seccomp architecture: $architecture")
            }
            val bytes = ByteBuffer.allocate((7 + denied.size * 2) * 8).order(ByteOrder.nativeOrder())
            fun instruction(code: Int, jt: Int = 0, jf: Int = 0, value: Int) {
                bytes.putShort(code.toShort()).put(jt.toByte()).put(jf.toByte()).putInt(value)
            }
            instruction(0x20, value = 4) // BPF_LD | BPF_W | BPF_ABS: seccomp_data.arch
            instruction(0x15, jt = 1, value = auditArch)
            instruction(0x06, value = 0x80000000.toInt()) // mismatched/compat ABI: KILL_PROCESS
            instruction(0x20, value = 0) // syscall number
            instruction(0x45, jf = 1, value = 0x40000000) // x32 ABI uses the same audit arch; never permit that bypass.
            instruction(0x06, value = 0x80000000.toInt())
            denied.forEach { number ->
                instruction(0x15, jf = 1, value = number)
                instruction(0x06, value = 0x00050001) // ERRNO(EPERM)
            }
            instruction(0x06, value = 0x7fff0000) // ALLOW
            // POSIX printf %b recognises \0ooo, unlike Java's shell-independent argv.
            return bytes.array().joinToString("") { "\\0" + (it.toInt() and 255).toString(8).padStart(3, '0') }
        }

        /** 有时限、持续排空输出的探测，保留原始 stderr；不把任何失败武断归因于 AppArmor。 */
        private fun runProbe(argv: List<String>): ProbeResult {
            val process = try {
                ProcessBuilder(argv).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                return ProbeResult(null, e.message ?: e.javaClass.simpleName)
            }
            val output = FutureTask {
                process.inputStream.bufferedReader().use { reader ->
                    val captured = StringBuilder()
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (captured.length < 8192) captured.append(buffer, 0, minOf(count, 8192 - captured.length))
                    }
                    captured.toString().trim()
                }
            }
            Thread(output, "mederi-bwrap-probe").apply { isDaemon = true; start() }
            try {
                process.outputStream.close()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    val detail = runCatching { output.get(1, TimeUnit.SECONDS) }.getOrDefault("")
                    return ProbeResult(null, "探测超过 10 秒。$detail")
                }
                return ProbeResult(process.exitValue(), output.get(1, TimeUnit.SECONDS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MederiStateException("Linux 沙盒探测被中断，命令未执行", e)
            } catch (e: java.util.concurrent.ExecutionException) {
                return ProbeResult(null, "无法读取探测结果：${e.cause?.message}")
            } catch (e: java.util.concurrent.TimeoutException) {
                return ProbeResult(null, "探测输出未按时结束")
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                output.cancel(true)
            }
        }
    }
}
