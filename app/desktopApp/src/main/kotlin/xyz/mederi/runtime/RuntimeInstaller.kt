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
    const val HOMEBREW_INSTALL = """/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)""""
    private val scriptName = if (windows) "install.ps1" else "install.sh"
    private val windowsSystem = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
    private val powerShell = windowsSystem.resolve("WindowsPowerShell/v1.0/powershell.exe")
    private val curl = if (windows) windowsSystem.resolve("curl.exe").path else "/usr/bin/curl"
    private val curlOptions = listOf(
        "-q", "--fail", "--silent", "--show-error", "--location", "--proto", "=https",
        "--proto-redir", "=https", "--connect-timeout", "15", "--max-time", "120",
    )

    private val installerUrl by lazy {
        check(!mac) { "Use Homebrew to install system runtimes on macOS." }
        val revision = javaClass.getResourceAsStream("/runtime/revision.txt")
            ?.bufferedReader()?.use { it.readText().trim() }.orEmpty()
        check(revision.matches(Regex("[0-9a-f]{40}"))) {
            "This build has no published installer revision. Build from a published commit containing the runtime scripts."
        }
        "https://cdn.jsdelivr.net/gh/lzdev42/mederi@$revision/scripts/runtime/$scriptName"
    }

    private fun arguments(missing: Set<McpRuntime.Component>) =
        missing.map { it.name.lowercase(Locale.ROOT) } + "uv"

    fun homebrewCommand(missing: Set<McpRuntime.Component>) = "brew install " + arguments(missing).joinToString(" ")

    fun terminalCommand(missing: Set<McpRuntime.Component>): String {
        val url = installerUrl
        val options = curlOptions.joinToString(" ")
        val components = arguments(missing).joinToString(" ")
        return if (windows) {
            val dollar = '$'
            """
                & { ${dollar}ErrorActionPreference = 'Stop';
                ${dollar}work = Join-Path ([IO.Path]::GetTempPath()) ('mederi-runtime-' + [guid]::NewGuid().ToString('N'));
                New-Item -ItemType Directory -Path ${dollar}work -ErrorAction Stop | Out-Null;
                try { ${dollar}script = Join-Path ${dollar}work '$scriptName';
                & (Join-Path ${dollar}env:SystemRoot 'System32\curl.exe') $options --output ${dollar}script '$url';
                if (${dollar}LASTEXITCODE -ne 0) { throw 'Could not download the runtime installer.' };
                & (Join-Path ${dollar}env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe')
                -NoProfile -ExecutionPolicy Bypass -File ${dollar}script $components;
                if (${dollar}LASTEXITCODE -ne 0) { throw 'Runtime installation failed.' }
                } finally { Remove-Item -LiteralPath ${dollar}work -Recurse -Force -ErrorAction SilentlyContinue } }
            """.trimIndent().lineSequence().joinToString(" ")
        } else {
            val dollar = '$'
            val body = """
                set -eu; work=${dollar}(mktemp -d);
                trap 'rm -rf -- "${dollar}work"' EXIT;
                $curl $options --output "${dollar}work/$scriptName" "$url";
                /usr/bin/sudo /bin/bash "${dollar}work/$scriptName" $components
            """.trimIndent().lineSequence().joinToString(" ")
            "/bin/bash -c '${body.replace("'", "'\"'\"'")}'"
        }
    }

    /** Returns installer output on failure. The caller always probes again to handle partial success. */
    suspend fun install(missing: Set<McpRuntime.Component>): String? = runInterruptible(Dispatchers.IO) {
        val work = Files.createTempDirectory("mederi-runtime-").toFile()
        try {
            val log = work.resolve("setup.log")
            if (mac) {
                val brew = homebrewExecutable()
                    ?: return@runInterruptible "Homebrew is required. Run the official installation command in Terminal."
                return@runInterruptible runProcess(
                    listOf(brew.path, "install") + arguments(missing), work, log, timeoutSeconds = 20 * 60,
                )
            }

            val script = work.resolve(scriptName)
            val download = listOf(curl) + curlOptions + listOf("--output", script.path, installerUrl)
            runProcess(download, work, log, timeoutSeconds = 130)?.let { failure ->
                return@runInterruptible "Could not download the runtime installer.\n$failure"
            }
            val launcher = if (windows) {
                listOf(powerShell.path, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path)
            } else {
                listOf("/usr/bin/sudo", "-n", "/bin/bash", script.path)
            }
            runProcess(launcher + arguments(missing), work, log, timeoutSeconds = 20 * 60)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun homebrewExecutable(): File? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map(::File)
        val standard = listOf(File("/opt/homebrew/bin"), File("/usr/local/bin"))
        return (path + standard).asSequence()
            .filter { it.isAbsolute }
            .map { it.resolve("brew") }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun runProcess(argv: List<String>, work: File, log: File, timeoutSeconds: Long): String? {
        val process = ProcessBuilder(argv)
            .directory(work)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { if (mac) environment()["NONINTERACTIVE"] = "1" }
            .start()
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return "The process timed out.\n${log.readTail()}".trimEnd()
            }
            return if (process.exitValue() == 0) null
            else log.readTail().ifBlank { "The process exited with code ${process.exitValue()}." }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    private fun File.readTail(): String = RandomAccessFile(this, "r").use { reader ->
        val count = minOf(reader.length(), 4096).toInt()
        reader.seek(reader.length() - count)
        ByteArray(count).also(reader::readFully).decodeToString().trim()
    }
}
