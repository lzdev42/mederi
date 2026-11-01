package xyz.mederi.tools.sandbox

import java.io.File
import java.security.MessageDigest

/**
 * 命令沙箱状态（注入系统提示词与设置页展示用）。
 */
data class SandboxStatus(
    val backend: String,          // "Seatbelt" / "bubblewrap" / "none"
    val available: Boolean,       // 沙箱是否实际生效
    val shell: String,            // "bash" / "sh" / "cmd"
    val detail: String            // 人读说明（不可用原因、安装命令等）
)

/**
 * bash 的 OS 级写沙箱 + shell 探测链。
 *
 * 设计（docs/sandbox-plan.md）：
 * - **永远开，无开关**：不感知的安全不需要配置；合法扩大写范围的正确姿势是
 *   把目录加入项目或设置页全局白名单，而不是关沙箱。
 * - **读全盘放行，写锁白名单**：白名单 = 项目目录 + .mederi/ + /tmp 与 $TMPDIR +
 *   内置构建缓存（~/.gradle ~/.m2 ~/.cache ~/.konan ~/Library/Caches ~/Library/Java）+
 *   /dev 设备文件 + 全局白名单（SandboxConfig.extraWritablePaths）。
 * - 平台机制：macOS Seatbelt（sandbox-exec，系统自带零依赖）；
 *   Linux bubblewrap（只检测不代装，不可用时阻止命令）；Windows 无原语，降级警告。
 * - **shell 探测链**：macOS/Linux bash→sh（AI 生成 bash 语法，dash 执行会踩坑）；
 *   Windows bash.exe(Git Bash)→cmd /c。探测结果随环境块注入系统提示词。
 *
 * 每 turn 新建实例：完整 bwrap 配置每 turn 探测一次，用户安装后无需重启。
 * JVM-only：ProcessBuilder + 平台二进制调用，无 KMP 替代品（core 当前仅 jvm 目标）。
 *
 * @param projectDirs 会话所属项目的目录列表（first 为主目录）
 */
class CommandSandbox private constructor(
    private val projectDirs: List<String>,
    private val platformName: String,
    private val linuxSandboxOverride: LinuxCommandSandbox?,
) {
    constructor(projectDirs: List<String>) : this(projectDirs, System.getProperty("os.name"), null)

    /** 在任意宿主验证 Linux 失败路径，不改 JVM 全局 os.name，也不影响其他并行测试。 */
    internal constructor(projectDirs: List<String>, linuxSandbox: LinuxCommandSandbox) :
        this(projectDirs, "Linux", linuxSandbox)

    /** 探测到的 shell 可执行名（bash/sh/cmd），构造时确定，每 turn 复用 */
    val shell: String by lazy { detectShell() }

    /** Windows 下 cmd 模式标记 */
    private val isWindows = platformName.lowercase().contains("windows")

    private val isMac = platformName.lowercase().contains("mac")

    private val linuxSandbox by lazy {
        linuxSandboxOverride ?: LinuxCommandSandbox(projectDirs, SandboxConfig.extraWritablePaths)
    }

    /** bash 是否可用（探测链第一优先），lazy 探测一次 */
    private val bashAvailable: Boolean by lazy {
        val candidate = if (isWindows) "bash.exe" else "bash"
        runCatching {
            ProcessBuilder(candidate, "--version").start().waitFor() == 0
        }.getOrDefault(false)
    }

    private fun detectShell(): String = when {
        isWindows -> if (bashAvailable) "bash.exe" else "cmd"
        bashAvailable -> "bash"
        else -> "sh"
    }

    /**
     * 沙箱状态（设置页展示 + 系统提示词注入）。
     */
    fun status(): SandboxStatus = when {
        isWindows -> SandboxStatus(
            backend = "none", available = false, shell = shell,
            detail = "Windows 暂不支持命令沙箱，命令以用户权限运行。文件工具仍锁定项目目录。"
        )
        isMac -> SandboxStatus(
            backend = "Seatbelt", available = true, shell = shell,
            detail = "macOS Seatbelt：写仅限项目目录/临时目录/构建缓存，读全盘放行。"
        )
        else -> linuxSandbox.status(shell)
    }

    /** 组装好的命令：argv + 可选警告前缀 + 是否已是独立进程组（直接子进程 pid==pgid）。 */
    data class WrappedCommand(
        val argv: List<String>,
        val warning: String?,
        /**
         * 直接子进程是否为进程组长（pid == pgid）。为 true 时宿主可按 pgid 整组回收
         * （stop_process / runCommand 超时清理）。macOS 经 perl setpgrp、Linux 经 setsid。
         */
        val processGroupLeader: Boolean
    )

    /**
     * 组装执行 argv：Linux 不可用时抛 MederiStateException；Windows 保留裸跑警告。
     *
     * 同时给每条命令套进程组长包装（posix）：macOS 用系统自带 perl 的 `setpgrp(0,0)` 后
     * `exec sandbox-exec`，Linux 用 setsid——让整条命令树共享一个新 PGID（= 直接子进程 pid）。
     * 原因：macOS Seatbelt 无 process-signal 操作（实测 unbound variable），沙箱内互杀不可能；
     * 必须由宿主在沙箱外按进程组回收（见 ProcessRegistry）。
     *
     * @return 组装结果（argv + 警告前缀 + 是否独立进程组）
     */
    fun wrap(command: String): WrappedCommand {
        val status = status()
        val baseArgv = when {
            isWindows -> if (shell == "bash.exe") listOf("bash.exe", "-c", command)
                         else listOf("cmd", "/c", command)
            isMac -> listOf("sandbox-exec", "-f", seatbeltProfileFile().absolutePath, shell, "-c", command)
            else -> linuxSandbox.wrap(listOf(shell, "-c", command))
        }
        val warning = if (status.available) null else "[sandbox] ${status.detail}\n"

        val leaderPrefix = posixGroupLeaderPrefix()
        return if (leaderPrefix != null) {
            WrappedCommand(leaderPrefix + baseArgv, warning, processGroupLeader = true)
        } else {
            WrappedCommand(baseArgv, warning, processGroupLeader = false)
        }
    }

    /**
     * posix 平台让直接子进程成为独立进程组组长（pid==pgid）的前缀 argv；不可用返回 null。
     * - macOS：/usr/bin/perl 系统自带必在，`setpgrp(0,0)` 后 exec 原命令（同 pid 保持组长身份）
     * - Linux：setsid（util-linux 默认带）；缺失则降级为无进程组
     */
    private fun posixGroupLeaderPrefix(): List<String>? = when {
        isWindows -> null
        isMac -> listOf(
            "/usr/bin/perl",
            "-e",
            """setpgrp(0,0) or die ${'$'}!; exec @ARGV""",
            "--"
        )
        setsidAvailable -> listOf("setsid")
        else -> null
    }

    /** Linux setsid 探测（lazy 一次） */
    private val setsidAvailable: Boolean by lazy {
        runCatching { ProcessBuilder("setsid", "--version").start().waitFor() == 0 }
            .getOrDefault(false)
    }

    // ==================== macOS Seatbelt ====================

    /**
     * 生成（或复用缓存）SBPL profile 临时文件。
     * profile 内容按白名单目录集合 hash 缓存：同集合复用同一文件，避免每命令重写。
     */
    private fun seatbeltProfileFile(): File {
        val content = seatbeltProfile()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val file = File(System.getProperty("java.io.tmpdir"), "mederi-sandbox-$hash.sb")
        if (!file.exists()) {
            file.writeText(content)
            file.deleteOnExit()
        }
        return file
    }

    private fun writableDirs(): List<String> {
        val home = System.getProperty("user.home")
        val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp"
        val builtin = listOf(
            tmpDir, "/tmp", "/private/tmp", "/private/var/folders",
            // JVM ecosystem
            "$home/.gradle", "$home/.m2", "$home/.konan",
            "$home/Library/Caches", "$home/Library/Java",
            // JS/TS ecosystem
            "$home/.npm", "$home/.bun", "$home/.pnpm-store",
            // Rust
            "$home/.cargo",
            // Go
            "$home/go",
            // Python (pip/poetry/pipenv/uv share ~/.cache and ~/.local)
            "$home/.local",
            // generic cache (covers pip, Homebrew, etc.)
            "$home/.cache",
            // device files
            "/dev/null", "/dev/stdin", "/dev/stdout", "/dev/stderr"
        )
        return (projectDirs + SandboxConfig.extraWritablePaths + builtin)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.trimEnd('/')) }   // canonical 变体，覆盖 /tmp→/private/tmp 类解析
            .distinct()
    }

    private fun seatbeltProfile(): String = buildString {
        appendLine("(version 1)")
        appendLine("(deny default)")
        appendLine("(allow process-exec*)")
        appendLine("(allow process-fork)")
        appendLine("(allow mach-lookup)")
        appendLine("(allow system-socket)")
        appendLine("(allow sysctl-read)")
        appendLine("(allow ipc-posix*)")
        appendLine("(allow ipc-sysv*)")
        appendLine("(allow network*)")
        appendLine("(allow process-info*)")
        appendLine("(allow file-read*)")
        // /usr/libexec/java_home 探测 JDK 需要 sysctl-write（CFPreferences 内部用），
        // 缺失时 gradlew 的 java_home 探测链在沙箱内报 "Unable to locate a Java Runtime"
        appendLine("(allow sysctl-write)")
        appendLine("(allow file-write*")
        writableDirs().forEach { dir -> appendLine("  (subpath \"$dir\")") }
        appendLine("  (literal \"/dev/null\")")
        appendLine("  (literal \"/dev/stdin\")")
        appendLine("  (literal \"/dev/stdout\")")
        appendLine("  (literal \"/dev/stderr\")")
        appendLine(")")
    }

    companion object {
        /**
         * 系统环境块（不含时间——时间由 EnvironmentInfoProvider 注入）。
         * 注入系统提示词动态后缀：AI 需要知道但不该让用户每次输入的环境事实。
         */
        fun environmentNote(sandbox: CommandSandbox?): String = buildString {
            val status = sandbox?.status()
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            if (status != null) {
                appendLine("Shell: ${status.shell}${if (status.available) " (writes sandboxed)" else " (unsandboxed)"}")
                appendLine("Sandbox: ${if (status.available) status.backend else "none"} — ${status.detail}")
            }
            appendLine("Process control: list_processes / stop_process manage mederi-spawned processes only")
        }
    }
}
