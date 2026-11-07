package xyz.mederi.mcp.runtime

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared by desktop preflight and MCP stdio, including a headless host using the same runtimes. */
object McpRuntime {
    enum class Component { NODE, PYTHON, UV }

    private val home = File(System.getProperty("user.home"))
    val directory: File = home.resolve(".mederi/runtime")
    private val os = System.getProperty("os.name")
    private val windows = os.startsWith("Windows", ignoreCase = true)
    private val mac = os.startsWith("Mac", ignoreCase = true)
    private val runtimeNames = setOf("node", "npm", "npx", "python", "python3", "uv", "uvx")
    private val pythonNames = setOf("python", "python3")

    fun missing(): Set<Component> = missing(environment(emptyMap()))

    internal fun missing(environment: Map<String, String>): Set<Component> = buildSet {
        if (!runs("node", listOf("-e", "process.exit(+process.versions.node.split('.')[0] < 22 ? 1 : 0)"), environment)
            || !runs("npm", listOf("--version"), environment)
            || !runs("npx", listOf("--version"), environment)
        ) add(Component.NODE)
        if (!runs("python3", listOf("-I", "-c", "import sys; sys.exit(sys.version_info < (3, 10))"), environment)) {
            add(Component.PYTHON)
        }
        if (!runs("uv", listOf("--version"), environment) || !runs("uvx", listOf("--version"), environment)) {
            add(Component.UV)
        }
    }

    internal fun environment(overrides: Map<String, String>): Map<String, String> {
        val environment: MutableMap<String, String> =
            if (windows) sortedMapOf(String.CASE_INSENSITIVE_ORDER) else linkedMapOf()
        environment.putAll(System.getenv())
        environment.putAll(overrides)
        val hasExplicitPath = overrides.keys.any { it.equals("PATH", ignoreCase = windows) }
        if (!hasExplicitPath) {
            val managed = listOf(
                directory.resolve(if (windows) "node" else "node/bin"),
                directory.resolve("python-bin"),
                directory.resolve("uv"),
            )
            val inherited = environment["PATH"].orEmpty().split(File.pathSeparator).map(::File)
            environment["PATH"] = (managed + inherited + extraDirectories(environment))
                .filter { it.isAbsolute && it.isDirectory }
                .distinct()
                .joinToString(File.pathSeparator)
        }
        val python = directory.resolve("python")
        if (python.isDirectory) environment.putIfAbsent("UV_PYTHON_INSTALL_DIR", python.absolutePath)
        return environment
    }

    private fun extraDirectories(environment: Map<String, String>): List<File> = if (windows) {
        val local = environment["LOCALAPPDATA"]?.let(::File)
        listOfNotNull(
            environment["ProgramFiles"]?.let { File(it, "nodejs") },
            local?.resolve("Programs/nodejs"),
            local?.resolve("Microsoft/WinGet/Links"),
            home.resolve(".local/bin"),
        ) + local?.resolve("Programs/Python")?.listFiles().orEmpty().sortedDescending()
    } else {
        listOf(
            File("/opt/homebrew/bin"), File("/usr/local/bin"), File("/usr/bin"), File("/bin"),
            home.resolve(".local/bin"), home.resolve(".volta/bin"),
        ) + home.resolve(".nvm/versions/node").listFiles().orEmpty()
            .sortedByDescending { it.name.substringBefore('.').removePrefix("v").toIntOrNull() ?: 0 }
            .map { it.resolve("bin") }
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
        for (dir in directories) {
            if (windows && dir.name.equals("WindowsApps", ignoreCase = true)) continue // Microsoft Store aliases
            if (windowsNpm) {
                // Call the JS used by the official .cmd launcher, keeping MCP arguments literal.
                val node = dir.resolve("node.exe")
                val cli = dir.resolve("node_modules/npm/bin/$name-cli.js")
                if (node.isFile && cli.isFile) return listOf(node.path, cli.path) + args
            }
            if (file.parent != null) continue
            for (candidate in names) {
                val executable = dir.resolve(if (windows) "$candidate.exe" else candidate)
                if (mac && executable.path == "/usr/bin/python3") continue // Xcode installation shim
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
