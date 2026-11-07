package xyz.mederi.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import xyz.mederi.mcp.runtime.McpRuntime
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object RuntimeInstaller {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    private val scriptName = if (windows) "install.ps1" else "install.sh"
    private val powerShell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe")

    private fun arguments(missing: Set<McpRuntime.Component>) =
        missing.map { it.name.lowercase(Locale.ROOT) }

    private val terminalScript by lazy {
        val work = Files.createTempDirectory("mederi-runtime-manual-").toFile()
        work.deleteOnExit()
        work.resolve(scriptName).also { script ->
            script.deleteOnExit()
            copyBundledScript(script)
        }
    }

    fun terminalCommand(missing: Set<McpRuntime.Component>): String {
        val script = terminalScript.absolutePath
        val components = arguments(missing).joinToString(" ")
        return if (windows) {
            val launcher = powerShell.absolutePath.replace("'", "''")
            val escapedScript = script.replace("'", "''")
            "& '$launcher' -NoProfile -ExecutionPolicy Bypass -File '$escapedScript' $components"
        } else {
            val escapedScript = script.replace("'", "'\"'\"'")
            "/bin/bash '$escapedScript' $components"
        }
    }

    private fun copyBundledScript(script: File) {
        checkNotNull(javaClass.getResourceAsStream("/runtime/$scriptName")).use { source ->
            script.outputStream().use { source.copyTo(it) }
        }
    }

    /** Returns installer output on failure. The caller always probes again to handle partial success. */
    suspend fun install(missing: Set<McpRuntime.Component>): String? = runInterruptible(Dispatchers.IO) {
        val work = Files.createTempDirectory("mederi-runtime-").toFile()
        try {
            val script = work.resolve(scriptName)
            val log = work.resolve("setup.log")
            copyBundledScript(script)
            val launcher = if (windows) {
                listOf(powerShell.path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path)
            } else {
                listOf("/bin/bash", script.path)
            }
            val process = ProcessBuilder(launcher + arguments(missing))
                .directory(work)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment()["MEDERI_RUNTIME_HOME"] = McpRuntime.directory.absolutePath }
                .start()
            try {
                if (process.waitFor(20, TimeUnit.MINUTES) && process.exitValue() == 0) null
                else log.readTail()
            } finally {
                if (process.isAlive) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun File.readTail(): String = RandomAccessFile(this, "r").use { reader ->
        val count = minOf(reader.length(), 4096).toInt()
        reader.seek(reader.length() - count)
        ByteArray(count).also(reader::readFully).decodeToString().trim()
    }
}
