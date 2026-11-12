package xyz.mederi.mcp.runtime

import xyz.mederi.mcp.engine.McpRuntimeUnavailableException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared by desktop preflight and MCP stdio, including a headless host using the same runtimes. */
object McpRuntime {
    enum class Component { NODE, PYTHON, GIT }

    private val os = System.getProperty("os.name")
    private val windows = os.startsWith("Windows", ignoreCase = true)
    private val mac = os.startsWith("Mac", ignoreCase = true)
    private val localBin = File(System.getProperty("user.home"), ".local/bin")
    private val runtimeNames = setOf("node", "npm", "npx", "python", "python3", "git", "uv", "uvx")
    private val pythonNames = setOf("python", "python3")

    fun missing(): Set<Component> = missing(environment(emptyMap()))

    internal fun missing(environment: Map<String, String>): Set<Component> = buildSet {
        if (!runs("node", listOf("--version"), environment)
            || !runs("npm", listOf("--version"), environment)
            || !runs("npx", listOf("--version"), environment)
        ) add(Component.NODE)
        if (!runs("python3", listOf("-E", "-S", "-c", "import sys; sys.exit(sys.version_info[0] != 3)"), environment)) {
            add(Component.PYTHON)
        }
        if (!runs("git", listOf("--version"), environment)) add(Component.GIT)
    }

    internal fun environment(overrides: Map<String, String>): Map<String, String> {
        val environment: MutableMap<String, String> =
            if (windows) sortedMapOf(String.CASE_INSENSITIVE_ORDER) else linkedMapOf()
        environment.putAll(System.getenv())
        environment.putAll(overrides)
        val hasExplicitPath = overrides.keys.any { it.equals("PATH", ignoreCase = windows) }
        if (!hasExplicitPath) {
            val inherited = environment["PATH"].orEmpty().split(File.pathSeparator).map(::File)
            environment["PATH"] = (inherited + extraDirectories(environment))
                .filter { it.isAbsolute && it.isDirectory }
                .distinct()
                .joinToString(File.pathSeparator)
        }
        return environment
    }

    private fun extraDirectories(environment: Map<String, String>): List<File> = if (windows) {
        // A running desktop process keeps its original PATH after a machine-wide installation.
        val programFiles = listOf("ProgramW6432", "ProgramFiles", "ProgramFiles(x86)")
            .mapNotNull { environment[it]?.let(::File) }.distinct()
        val pythonDirectory = Regex("Python3([0-9]+)(?:-.*)?", RegexOption.IGNORE_CASE)
        programFiles.flatMap { root ->
            listOf(root.resolve("nodejs"), root.resolve("Git/cmd"), root.resolve("WinGet/Links")) +
                root.listFiles().orEmpty().mapNotNull { dir ->
                    pythonDirectory.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to dir }
                }.sortedByDescending { it.first }.map { it.second }
        } + listOfNotNull(environment["LOCALAPPDATA"]?.let { File(it, "Microsoft/WinGet/Links") }, localBin)
    } else if (mac) {
        listOf(
            File("/opt/homebrew/bin"), File("/usr/local/bin"),
            File("/Library/Frameworks/Python.framework/Versions/Current/bin"),
            File("/Library/Developer/CommandLineTools/usr/bin"),
            File("/Applications/Xcode.app/Contents/Developer/usr/bin"),
            File("/usr/bin"), File("/bin"), localBin,
        )
    } else {
        listOf(File("/usr/local/bin"), File("/usr/bin"), File("/bin"), localBin)
    }

    internal fun stdioCommandLine(command: String, args: List<String>, environment: Map<String, String>): List<String> {
        val argv = commandLine(command, args, environment)
        val bareName = if (windows) command.lowercase().removeSuffix(".exe") else command
        if (argv.first() != command || (bareName != "uvx" && bareName != "uv")) return argv
        if (bareName == "uvx") {
            val uv = commandLine("uv", listOf("tool", "run") + args, environment)
            if (uv.first() != "uv") return uv
        }
        throw McpRuntimeUnavailableException(
            "This MCP server needs uv (uvx) on the host. Install it and add it to PATH: https://docs.astral.sh/uv/getting-started/installation/"
        )
    }

    internal fun commandLine(command: String, args: List<String>, environment: Map<String, String>): List<String> {
        val file = File(command)
        val name = if (windows) file.name.lowercase().removeSuffix(".cmd").removeSuffix(".exe") else file.name
        if (name !in runtimeNames) return listOf(command) + args
        val windowsNpm = windows && name in setOf("npm", "npx") && !file.name.endsWith(".exe", ignoreCase = true)
        if (file.parent != null && !(windowsNpm && file.name.endsWith(".cmd", ignoreCase = true) && file.isFile)) {
            return listOf(command) + args
        }
        val directories = if (file.parent == null) {
            environment["PATH"].orEmpty().split(File.pathSeparator).map(::File).filter { it.isAbsolute }
        } else {
            listOf(file.absoluteFile.parentFile)
        }
        val names = if (name in pythonNames) listOf(name, if (name == "python") "python3" else "python") else listOf(name)
        for (candidate in names) {
            for (dir in directories) {
                if (windows && dir.name.equals("WindowsApps", ignoreCase = true)) continue // Microsoft Store aliases
                if (windowsNpm) {
                    // Call the JS used by the official .cmd launcher, keeping MCP arguments literal.
                    val node = dir.resolve("node.exe")
                    val cli = dir.resolve("node_modules/npm/bin/$name-cli.js")
                    if (node.isFile && cli.isFile) return listOf(node.path, cli.path) + args
                }
                if (file.parent != null) continue
                val executable = dir.resolve(if (windows) "$candidate.exe" else candidate)
                // Probe the actual Command Line Tools binaries above instead of launching Apple's install prompts.
                if (mac && executable.path in setOf("/usr/bin/python3", "/usr/bin/git")) continue
                if (executable.isFile && executable.canExecute()) return listOf(executable.path) + args
            }
        }
        return listOf(command) + args
    }

    private fun runs(command: String, args: List<String>, environment: Map<String, String>): Boolean {
        val argv = commandLine(command, args, environment)
        if (argv.first() == command) return false // Do not fall back to a Store or Xcode alias when probing.
        return try {
            val process = ProcessBuilder(argv).apply {
                environment().clear()
                environment().putAll(environment)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }.start()
            try {
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: IOException) {
            false
        }
    }
}
