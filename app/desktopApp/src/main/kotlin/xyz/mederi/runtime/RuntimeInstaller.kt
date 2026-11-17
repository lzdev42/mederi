package xyz.mederi.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import xyz.mederi.core.contract.TerminalSession
import xyz.mederi.mcp.runtime.McpRuntime
import xyz.mederi.server.terminal.PtyTerminalHub
import java.io.File
import java.util.Base64
import java.util.Locale

internal object RuntimeInstaller {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    private val sessions = PtyTerminalHub()
    private val scriptName = if (windows) "install.ps1" else "install.sh"
    private val windowsSystem = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
    private val powerShell = windowsSystem.resolve("WindowsPowerShell/v1.0/powershell.exe")
    private const val homebrewUrl = "https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh"
    private const val passwordPrompt = "[Mederi password] "
    private val curlOptions = listOf(
        "-q", "--fail", "--silent", "--show-error", "--location", "--proto", "=https",
        "--proto-redir", "=https", "--connect-timeout", "15", "--max-time", "120",
    ).joinToString(" ")

    private val installerUrl by lazy {
        val revision = javaClass.getResourceAsStream("/runtime/revision.txt")
            ?.bufferedReader()?.use { it.readText().trim() }.orEmpty()
        check(revision.matches(Regex("[0-9a-f]{40}"))) {
            "This build has no published installer revision."
        }
        "https://cdn.jsdelivr.net/gh/lzdev42/mederi@$revision/scripts/runtime/$scriptName"
    }

    fun needsHomebrew(): Boolean = mac && homebrewExecutable() == null

    suspend fun start(missing: Set<McpRuntime.Component>): TerminalSession {
        var started: TerminalSession? = null
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                sessions.startCommand(command(missing), "Mederi Setup").also { started = it }
            }
        } catch (failure: Throwable) {
            started?.kill()
            throw failure
        }
    }

    private fun command(missing: Set<McpRuntime.Component>): List<String> {
        val components = missing.joinToString(" ") { it.name.lowercase(Locale.ROOT) } + " uv"
        return when {
            mac -> listOf("/bin/bash", "-c", macInstall(components.trim()))
            windows -> {
                val encoded = Base64.getEncoder().encodeToString(windowsInstall(components.trim()).toByteArray(Charsets.UTF_16LE))
                listOf(powerShell.path, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            }
            else -> listOf("/bin/bash", "-c", linuxInstall(components.trim()))
        }
    }

    private fun linuxInstall(components: String) = """
        set -eu
        /bin/stty -echo
        work=${'$'}(mktemp -d)
        trap 'rm -rf -- "${'$'}work"' EXIT
        printf '%s\n' '[Mederi] Downloading'
        /usr/bin/curl $curlOptions --output "${'$'}work/$scriptName" '$installerUrl'
        /usr/bin/sudo -p '$passwordPrompt' /bin/bash -c \
            'printf "%s\n" "[Mederi] Installing"; exec /bin/bash "${'$'}@"' bash "${'$'}work/$scriptName" $components
    """.trimIndent()

    private fun macInstall(components: String): String {
        val brew = homebrewExecutable()
        if (brew != null) {
            return """
                /bin/stty -echo || exit
                printf '%s\n' '[Mederi] Installing'
                HOMEBREW_NO_ANALYTICS=1 exec ${shellQuote(brew.path)} install $components
            """.trimIndent()
        }
        return """
            set -eu
            /bin/stty -echo
            work=${'$'}(mktemp -d)
            trap 'rm -rf -- "${'$'}work"' EXIT
            printf '%s\n' '[Mederi] Downloading'
            /usr/bin/curl $curlOptions --output "${'$'}work/homebrew.sh" '$homebrewUrl'
            printf '%s\n' '[Mederi] Installing'
            unset NONINTERACTIVE CI SUDO_ASKPASS
            SUDO_PROMPT='$passwordPrompt' /bin/bash "${'$'}work/homebrew.sh"
            for brew in /opt/homebrew/bin/brew /usr/local/bin/brew; do
                if [[ -x "${'$'}brew" ]]; then
                    printf '%s\n' '[Mederi] Installing'
                    HOMEBREW_NO_ANALYTICS=1 "${'$'}brew" install $components
                    exit
                fi
            done
            exit 1
        """.trimIndent()
    }

    private fun windowsInstall(components: String) = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}work = Join-Path ([IO.Path]::GetTempPath()) ('mederi-runtime-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path ${'$'}work | Out-Null
        try {
            ${'$'}script = Join-Path ${'$'}work '$scriptName'
            Write-Output '[Mederi] Downloading'
            & (Join-Path ${'$'}env:SystemRoot 'System32\curl.exe') $curlOptions --output ${'$'}script '$installerUrl'
            if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }
            & (Join-Path ${'$'}env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -ExecutionPolicy Bypass -File ${'$'}script $components
            exit ${'$'}LASTEXITCODE
        } finally {
            Remove-Item -LiteralPath ${'$'}work -Recurse -Force -ErrorAction SilentlyContinue
        }
    """.trimIndent()

    private fun homebrewExecutable(): File? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map(::File)
        val standard = listOf(File("/opt/homebrew/bin"), File("/usr/local/bin"))
        return (path + standard).asSequence()
            .filter { it.isAbsolute }
            .map { it.resolve("brew") }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}

internal enum class InstallStage { DOWNLOADING, INSTALLING, PASSWORD, CONTINUE_HOMEBREW, WINDOWS_APPROVAL }

/** Recognize our own stage markers and Homebrew's interactive continuation prompt across PTY reads. */
internal class InstallTerminalStatus {
    private var pending = ""
    private val sgr = Regex("\\u001b\\[[0-9;]*m")

    fun accept(chunk: String): InstallStage? {
        var stage: InstallStage? = null
        val lines = (pending + chunk).split('\n')
        pending = lines.last().takeLast(512)
        for (line in lines.dropLast(1) + pending) {
            val text = sgr.replace(line, "").trim()
            val detected = when {
                text == "[Mederi] Downloading" -> InstallStage.DOWNLOADING
                text == "[Mederi] Installing" || text.startsWith("Installing ") -> InstallStage.INSTALLING
                text == "[Mederi] Windows approval" -> InstallStage.WINDOWS_APPROVAL
                text == "[Mederi password]" || text == "Password:" -> InstallStage.PASSWORD
                text.startsWith("Press RETURN/ENTER to continue") -> InstallStage.CONTINUE_HOMEBREW
                text.startsWith("==> ") -> InstallStage.INSTALLING
                else -> null
            }
            if (detected != null) stage = detected
        }
        return stage
    }
}
