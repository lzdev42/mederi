package xyz.mederi.tools.sandbox

import xyz.mederi.api.exception.MederiStateException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Linux mount namespace：根只读，白名单原路径读写，独立 PID namespace 隔离宿主 /proc。
 * JVM-only 宿主能力；与 macOS 的路径/设备规则分离，不能 bind /dev/std* 的 magic links。
 * 探测与执行复用同一份已准备的挂载参数；失败禁止退回裸命令。
 */
internal class LinuxCommandSandbox(
    private val projectDirs: List<String>,
    private val extraPaths: List<String>,
    private val home: String = System.getProperty("user.home"),
    private val tempDirs: List<String> = listOfNotNull(
        "/tmp", System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }, System.getProperty("java.io.tmpdir")
    ),
    private val probe: (List<String>) -> ProbeResult = ::runProbe,
) {
    internal data class ProbeResult(val exitCode: Int?, val output: String)
    private data class Preparation(val prefix: List<String>?, val error: String?)

    private val preparation: Preparation by lazy {
        try {
            val version = probe(listOf("bwrap", "--version"))
            if (version.exitCode != 0) {
                Preparation(null, "无法启动 bubblewrap：${version.output}。安装或检查 bwrap：" +
                    "sudo apt install bubblewrap / sudo dnf install bubblewrap / sudo pacman -S bubblewrap")
            } else {
                val prefix = prepareMounts()
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
    }

    fun status(shell: String): SandboxStatus {
        val ready = preparation
        return SandboxStatus(
            backend = if (ready.prefix != null) "bubblewrap" else "none",
            available = ready.prefix != null,
            shell = shell,
            detail = ready.error?.let { "$it Linux 命令已阻止，不会无沙盒运行。" }
                ?: "Linux bubblewrap：白名单可写、其余文件系统只读；隔离宿主进程，网络保留。"
        )
    }

    fun wrap(command: List<String>): List<String> {
        val prefix = preparation.prefix ?: throw MederiStateException(status("sh").detail)
        return prefix + "--" + command
    }

    private fun prepareMounts(): List<String> {
        val caches = listOf(".gradle", ".m2", ".konan", ".npm", ".bun", ".pnpm-store",
            ".cargo", "go", ".local", ".cache").map { "$home/$it" }
        val roots = (projectDirs + extraPaths + tempDirs + caches).map { path ->
            require(path.isNotBlank() && File(path).isAbsolute) { "白名单必须是绝对路径：$path" }
            File(path).canonicalFile
        }.distinctBy { it.path }.sortedBy { it.toPath().nameCount }

        // 禁止白名单盖过 /proc、/dev、/sys，或把整个根挂回可写。
        roots.forEach { root ->
            val path = root.toPath()
            require(path.parent != null && listOf("/proc", "/dev", "/sys").none {
                path.startsWith(it) || java.nio.file.Path.of(it).startsWith(path)
            }) { "白名单不能覆盖根目录或内核/设备路径：$root" }
        }
        val mounts = mutableListOf<File>()
        roots.forEach { root ->
            if (mounts.none { it.isDirectory && root.toPath().startsWith(it.toPath()) }) {
                // 在宿主按白名单路径创建必要目录，绝不把其父目录挂为可写。
                // 已存在的普通文件白名单仍按文件 bind；不存在的路径按目录处理。
                if (!root.exists()) Files.createDirectories(root.toPath())
                mounts.add(root)
            }
        }
        return buildList {
            addAll(listOf("bwrap", "--unshare-pid", "--die-with-parent", "--cap-drop", "ALL",
                "--ro-bind", "/", "/"))
            mounts.forEach { addAll(listOf("--bind", it.path, it.path)) }
            // /tmp 直接 bind 宿主白名单，保留跨命令临时文件和构建 daemon socket。
            // /dev 由 bwrap 创建；不再重绑 /dev/stdin/out/err（它们可能指向 pipe/socket）。
            addAll(listOf("--dev", "/dev", "--proc", "/proc"))
        }
    }

    companion object {
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
