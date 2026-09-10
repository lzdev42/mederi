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
 * execute_command 的 OS 级写沙箱 + shell 探测链。
 *
 * 设计（docs/sandbox-plan.md）：
 * - **永远开，无开关**：不感知的安全不需要配置；合法扩大写范围的正确姿势是
 *   把目录加入项目或设置页全局白名单，而不是关沙箱。
 * - **读全盘放行，写锁白名单**：白名单 = 项目目录 + .mederi/ + /tmp 与 $TMPDIR +
 *   内置构建缓存（~/.gradle ~/.m2 ~/.cache ~/.konan ~/Library/Caches ~/Library/Java）+
 *   /dev 设备文件 + 全局白名单（SandboxConfig.extraWritablePaths）。
 * - 平台机制：macOS Seatbelt（sandbox-exec，系统自带零依赖）；
 *   Linux bubblewrap（只检测不代装，缺失降级警告）；Windows 无原语，降级警告。
 * - **shell 探测链**：macOS/Linux bash→sh（AI 生成 bash 语法，dash 执行会踩坑）；
 *   Windows bash.exe(Git Bash)→cmd /c。探测结果随环境块注入系统提示词。
 *
 * 每 turn 新建实例：bwrap 探测每 turn 重跑（~10ms），用户安装后无需重启。
 * JVM-only：ProcessBuilder + 平台二进制调用，无 KMP 替代品（core 当前仅 jvm 目标）。
 *
 * @param projectDirs 会话所属项目的目录列表（first 为主目录）
 */
class CommandSandbox(
    private val projectDirs: List<String>
) {

    /** 探测到的 shell 可执行名（bash/sh/cmd），构造时确定，每 turn 复用 */
    val shell: String by lazy { detectShell() }

    /** Windows 下 cmd 模式标记 */
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /** bwrap 是否已安装（版本探测），lazy 一次 */
    private val bwrapInstalled: Boolean by lazy {
        !isWindows && !isMac && runCatching {
            ProcessBuilder("bwrap", "--version").start().waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * bwrap 沙箱是否实际可用（功能烟测）。
     *
     * --version 成功 ≠ 沙箱可用：Ubuntu 23.10+ 默认 AppArmor 限制 unprivileged user
     * namespaces，bwrap 存在但建不了 namespace（Codex 社区已知坑）。必须真实建一次
     * 沙箱跑个 no-op 命令验证；失败则降级为无沙箱 + 精确提示原因。
     */
    private val bwrapFunctional: Boolean by lazy {
        bwrapInstalled && runCatching {
            val p = ProcessBuilder(
                "bwrap",
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--proc", "/proc",
                "--tmpfs", "/tmp",
                "/bin/true"
            ).start()
            p.waitFor() == 0
        }.getOrDefault(false)
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
        bwrapFunctional -> SandboxStatus(
            backend = "bubblewrap", available = true, shell = shell,
            detail = "Linux bubblewrap：写仅限项目目录/临时目录/构建缓存，读全盘放行。"
        )
        else -> SandboxStatus(
            backend = "none", available = false, shell = shell,
            detail = if (!bwrapInstalled) {
                "未检测到 bubblewrap，命令未沙箱运行。" +
                    "安装: sudo apt install bubblewrap / sudo dnf install bubblewrap / sudo pacman -S bubblewrap"
            } else {
                "bubblewrap 已安装但无法创建沙箱（常见于 Ubuntu 23.10+ 的 AppArmor " +
                    "对 unprivileged user namespaces 的限制），命令未沙箱运行。" +
                    "解决: sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 或发行版等效配置"
            }
        )
    }

    /**
     * 组装执行 argv：沙箱可用时包一层 OS 沙箱，否则裸命令（可能带警告）。
     *
     * @return (argv, 警告前缀)；警告为 null 表示无
     */
    fun wrap(command: String): Pair<List<String>, String?> {
        val status = status()
        val baseArgv = if (isWindows) {
            if (shell == "bash.exe") listOf("bash.exe", "-c", command)
            else listOf("cmd", "/c", command)
        } else {
            listOf(shell, "-c", command)
        }

        return when {
            isWindows -> baseArgv to "[sandbox] ${status.detail}\n"
            isMac -> Pair(listOf("sandbox-exec", "-f", seatbeltProfileFile().absolutePath, shell, "-c", command), null)
            bwrapFunctional -> Pair(bwrapArgv(baseArgv), null)
            else -> baseArgv to "[sandbox] ${status.detail}\n"
        }
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
            "$home/.gradle", "$home/.m2", "$home/.cache", "$home/.konan",
            "$home/Library/Caches", "$home/Library/Java",
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

    // ==================== Linux bubblewrap ====================

    private fun bwrapArgv(baseArgv: List<String>): List<String> {
        val argv = mutableListOf(
            "bwrap",
            "--ro-bind", "/", "/",
            "--dev", "/dev",
            "--proc", "/proc",
            "--tmpfs", "/tmp"
        )
        writableDirs().forEach { dir ->
            val f = File(dir)
            if (f.exists()) argv.addAll(listOf("--bind", dir, dir))
        }
        argv.addAll(baseArgv)
        return argv
    }

    companion object {
        /**
         * 系统环境块（不含时间——时间由 TurnExecutor 注入）。
         * 注入系统提示词/用户消息隐藏区：AI 需要知道但不该让用户每次输入的环境事实。
         */
        fun environmentNote(sandbox: CommandSandbox?): String = buildString {
            val status = sandbox?.status()
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            if (status != null) {
                appendLine("Shell: ${status.shell}${if (status.available) " (writes sandboxed)" else " (unsandboxed)"}")
                appendLine("Sandbox: ${if (status.available) status.backend else "none"} — ${status.detail}")
            }
            appendLine("Java: ${System.getProperty("java.version")}")
        }
    }
}
