package xyz.mederi.tools.sandbox

import xyz.mederi.api.exception.MederiStateException
import java.util.concurrent.TimeUnit

/** 后端能力快照；available 表示受限命令能实际启动，不代表当前调用已获授权。 */
data class SandboxStatus(val backend: String, val available: Boolean, val shell: String, val detail: String)

/**
 * JVM 宿主命令沙盒。权限决定由 SandboxManager 作出，此类只执行选定的 OS 边界。
 * 不读取 UI 状态，不自行申请批准，不因后端失败而退回裸执行。
 */
class CommandSandbox private constructor(
    private val projectDirs: List<String>,
    private val extraWritablePaths: List<String>,
    private val protectedDirectories: List<String>,
    private val deniedPaths: List<String>,
    private val platformName: String,
    private val linuxSandboxOverride: LinuxCommandSandbox?,
    val verifiedWorkspaceRoot: xyz.mederi.workspace.VerifiedWorkspaceRoot? = null,
) {
    constructor(projectDirs: List<String>) :
        this(projectDirs, emptyList(), emptyList(), emptyList(), System.getProperty("os.name"), null, null)

    constructor(projectDirs: List<String>, extraWritablePaths: List<String>) :
        this(projectDirs, extraWritablePaths, emptyList(), emptyList(), System.getProperty("os.name"), null, null)

    constructor(projectDirs: List<String>, extraWritablePaths: List<String>, protectedDirectories: List<String>) :
        this(projectDirs, extraWritablePaths, protectedDirectories, emptyList(), System.getProperty("os.name"), null, null)

    constructor(projectDirs: List<String>, extraWritablePaths: List<String>, protectedDirectories: List<String>, deniedPaths: List<String>) :
        this(projectDirs, extraWritablePaths, protectedDirectories, deniedPaths, System.getProperty("os.name"), null, null)

    constructor(
        projectDirs: List<String>,
        extraWritablePaths: List<String>,
        protectedDirectories: List<String>,
        deniedPaths: List<String>,
        verifiedWorkspaceRoot: xyz.mederi.workspace.VerifiedWorkspaceRoot?
    ) : this(projectDirs, extraWritablePaths, protectedDirectories, deniedPaths, System.getProperty("os.name"), null, verifiedWorkspaceRoot)

    internal constructor(projectDirs: List<String>, linuxSandbox: LinuxCommandSandbox) :
        this(projectDirs, emptyList(), emptyList(), emptyList(), "Linux", linuxSandbox, null)

    internal constructor(projectDirs: List<String>, extraWritablePaths: List<String>, platformName: String, deniedPaths: List<String> = emptyList()) :
        this(projectDirs, extraWritablePaths, emptyList(), deniedPaths, platformName, null, null)

    private val isWindows = platformName.lowercase().contains("windows")
    private val isMac = platformName.lowercase().contains("mac")
    val shell: String by lazy {
        when {
            isWindows -> if (binaryAvailable("bash.exe", "--version")) "bash.exe" else "cmd"
            binaryAvailable("bash", "--version") -> "bash"
            else -> "sh"
        }
    }

    private val linuxSandbox by lazy {
        linuxSandboxOverride ?: LinuxCommandSandbox(projectDirs, extraWritablePaths,
            protectedDirectories = protectedDirectories, deniedPaths = deniedPaths,
            verifiedWorkspaceRoot = verifiedWorkspaceRoot)
    }

    private val macStatuses = java.util.concurrent.ConcurrentHashMap<Boolean, SandboxStatus>()
    private fun macStatus(fullAccess: Boolean = false): SandboxStatus = macStatuses.computeIfAbsent(fullAccess) {
        try {
            val process = ProcessBuilder("/usr/bin/sandbox-exec", "-p", seatbeltProfile(fullAccess, fullAccess), "/usr/bin/true")
                .redirectErrorStream(true).start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            val detail = if (completed) process.inputStream.bufferedReader().use { it.readText().take(4096).trim() } else "探测超时"
            if (completed && process.exitValue() == 0) {
                SandboxStatus("Seatbelt", true, shell, "macOS Seatbelt：工作区和临时目录可写，保护目录只读；默认禁止命令网络。")
            } else SandboxStatus("Seatbelt", false, shell, "Seatbelt 完整配置探测失败，命令已阻止：$detail")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            SandboxStatus("Seatbelt", false, shell, "Seatbelt 不可用，命令已阻止：${error.message}")
        }
    }

    fun status(): SandboxStatus = when {
        isWindows -> SandboxStatus("none", false, shell, if (deniedPaths.isEmpty())
            "Windows 命令沙盒尚未实现；需要批准模式阻止受限命令。可明确批准单次完全访问或选择完全访问模式。"
            else "Windows 无法强制执行路径黑名单；即使完全访问也阻止命令，避免绕过用户拒绝规则。")
        isMac -> macStatus()
        else -> linuxSandbox.status(shell)
    }

    data class WrappedCommand(val argv: List<String>, val warning: String?, val processGroupLeader: Boolean)

    /** unsandboxed 仅供调用方在明确全开或单次授权后传入；仍保留进程组回收。 */
    fun wrap(command: String, networkAccess: Boolean = false, unsandboxed: Boolean = false): WrappedCommand {
        val plain = listOf(shell, if (shell == "cmd") "/c" else "-c", command)
        // 显式黑名单是用户硬拒绝；完全访问也必须保留这层封套。
        val base = if (unsandboxed && deniedPaths.isEmpty()) plain else when {
            isWindows -> throw MederiStateException(status().detail)
            isMac -> {
                val status = macStatus(unsandboxed)
                if (!status.available) throw MederiStateException(status.detail)
                // 直接传入可信策略，不在任何 payload 可写的目录缓存策略文件。
                listOf("/usr/bin/sandbox-exec", "-p", seatbeltProfile(networkAccess || unsandboxed, unsandboxed)) + plain
            }
            else -> linuxSandbox.wrap(plain, networkAccess || unsandboxed, fullAccess = unsandboxed)
        }
        val leader = when {
            isWindows -> emptyList()
            isMac -> listOf("/usr/bin/perl", "-e", "setpgrp(0,0) or die ${'$'}!; exec @ARGV", "--")
            binaryAvailable("setsid", "--version") -> listOf("setsid")
            else -> throw MederiStateException("setsid 不可用，无法保证命令进程组回收；命令未执行。")
        }
        return WrappedCommand(leader + base, null, leader.isNotEmpty())
    }

    internal fun seatbeltProfile(networkAccess: Boolean = false, fullAccess: Boolean = false): String {
        val paths = SandboxPaths(
            projectDirs = projectDirs,
            extraWritablePaths = if (fullAccess) emptyList() else extraWritablePaths,
            protectedDirectories = if (fullAccess) emptyList() else protectedDirectories,
            deniedPaths = deniedPaths,
            verifiedWorkspaceRoot = verifiedWorkspaceRoot
        )
        return buildString {
            appendLine("(version 1)")
            if (fullAccess) appendLine("(allow default)") else {
                appendLine("(deny default)")
                appendLine("(allow process-exec*)")
                appendLine("(allow process-fork)")
                appendLine("(allow mach-lookup)")
                appendLine("(allow system-socket)")
                appendLine("(allow sysctl-read)")
                // java_home 探测 JDK 经 CFPreferences 需要该能力；不扩大文件系统写权限。
                appendLine("(allow sysctl-write)")
                appendLine("(allow ipc-posix*)")
                appendLine("(allow ipc-sysv*)")
                if (networkAccess) appendLine("(allow network*)")
                appendLine("(allow process-info*)")
                appendLine("(allow file-read*)")

                // 1. controlRoot 保护规则 (使用 require-all + require-not 允许当前 verifiedCheckoutRoot)
                paths.controlRoots.forEach { ctrl ->
                    if (verifiedWorkspaceRoot != null && verifiedWorkspaceRoot.checkoutRoot.startsWith(ctrl)) {
                        appendLine("(deny file-write* (require-all (subpath ${sbplString(ctrl.toString())}) (require-not (subpath ${sbplString(verifiedWorkspaceRoot.checkoutRoot.toString())}))))")
                    } else {
                        appendLine("(deny file-write* (subpath ${sbplString(ctrl.toString())}))")
                    }
                }

                // 2. writableRoots 允许规则
                paths.writableRoots.forEach { path ->
                    val predicate = if (java.nio.file.Files.isRegularFile(path)) "literal" else "subpath"
                    appendLine("(allow file-write* ($predicate ${sbplString(path.toString())}))")
                }
                listOf("/dev/null", "/dev/stdin", "/dev/stdout", "/dev/stderr").forEach {
                    appendLine("(allow file-write* (literal ${sbplString(it)}))")
                }

                // 3. 保护目录 (如 workspace 内的 .git / .agents 等)
                paths.protectedNamesPaths.forEach {
                    appendLine("(deny file-write* (subpath ${sbplString(it.toString())}))")
                }
            }
            // 4. 显式黑名单 (含重定向映射黑名单)
            paths.deniedCanonicalPaths.forEach {
                appendLine("(deny file-read* (subpath ${sbplString(it.toString())}))")
                appendLine("(deny file-write* (subpath ${sbplString(it.toString())}))")
            }
        }
    }

    companion object {
        internal fun sbplString(value: String): String {
            require(value.none { it.code < 32 || it.code == 127 }) { "Control characters are not allowed in sandbox paths" }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        private fun binaryAvailable(binary: String, argument: String): Boolean = try {
            val process = ProcessBuilder(binary, argument).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); false } else process.exitValue() == 0
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            false
        }

        fun environmentNote(sandbox: CommandSandbox?): String = buildString {
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            sandbox?.status()?.let { status ->
                appendLine("Shell: ${status.shell}")
                appendLine("Sandbox backend: ${status.backend}; available=${status.available} — ${status.detail}")
            }
            appendLine("Process control: list_processes / stop_process manage mederi-spawned processes only")
        }
    }
}
