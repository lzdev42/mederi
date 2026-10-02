/**
 * PlatformUtils 的 JVM actual —— 宿主 OS I/O。
 *
 * 与 pty4j 终端 / cloudflared 隧道同类：这些能力在 iOS/Android/wasm 上没有 KMP 替代品（那是 JS/Obj-C/JNI 的活），
 * 只在 desktop 宿主进程里用 JVM 标准库（java.awt.Desktop / ProcessBuilder）实现即可，**不引 JNI/JNA**。
 * 由 UI 宿主进程自身发起，**不走 AI execute_command 的 Seatbelt 沙箱**（属宿主能力，同 openFile 先例）。
 *
 * 铁律：
 * 1. 外部命令一律 `ProcessBuilder(List<String>)` 数组传参，**禁拼 shell 字符串** → 空格/中文/引号安全、免注入；
 * 2. 全部 `runCatching` 兜底，`defaultAppNameFor` / `revealInFolder` **绝不抛异常**（调用方只判 null/false）；
 * 3. Windows `explorer /select,"path"` 是**单个参数**，不能拆成两个；
 * 4. Windows `assoc`/`ftype` 输出会本地化，**只抓 ProgID 与 exe 路径**，不解析本地化说明文字；
 * 5. macOS `defaultAppNameFor` 直接返回 null（纯命令拿不到精确应用名，走泛称，不引 JNA）。
 */
package xyz.mederi.util

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
    }
}

actual fun openFile(path: String) {
    try {
        val file = File(path)
        if (file.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    } catch (_: Exception) {
    }
}

// ---------------------------------------------------------------- defaultAppNameFor

/** 宿主 OS 名（小写），用于平台分流。 */
private val hostOsName: String = System.getProperty("os.name", "").lowercase()

private val isMacOs: Boolean = hostOsName.contains("mac")
private val isWindowsOs: Boolean = hostOsName.contains("win")
private val isLinuxOs: Boolean = hostOsName.contains("nix")

/**
 * Windows exe 文件名 → 人类可读应用名（键统一大写）。
 * 只覆盖常见项；未知 exe 走「去掉 .exe 的文件名」兜底，不算失败。
 */
private val WINDOWS_FRIENDLY_APP_NAMES: Map<String, String> = mapOf(
    "WINWORD.EXE" to "Microsoft Word",
    "EXCEL.EXE" to "Microsoft Excel",
    "POWERPNT.EXE" to "Microsoft PowerPoint",
    "WPS.EXE" to "WPS 文字",
    "ET.EXE" to "WPS 表格",
    "WPP.EXE" to "WPS 演示",
    "ACROBAT.EXE" to "Adobe Acrobat",
    "ACRORD32.EXE" to "Adobe Acrobat",
    "SUMATRAPDF.EXE" to "SumatraPDF",
    "7ZFM.EXE" to "7-Zip"
)

actual fun defaultAppNameFor(path: String): String? = runCatching {
    when {
        isMacOs -> null // macOS 无干净 CLI 可取默认应用名（NSWorkspace 需原生）→ 走泛称
        isWindowsOs -> windowsDefaultAppName(path)
        isLinuxOs -> linuxDefaultAppName(path)
        else -> null
    }
}.getOrNull()

/**
 * Linux：xdg-mime → .desktop 的 `Name[zh_CN]=` / `Name=`。
 * 读不到 .desktop 文件时回落到「去掉 .desktop 后缀的名字」。
 */
private fun linuxDefaultAppName(path: String): String? {
    val mime = runCommand("xdg-mime", "query", "filetype", path)?.trim()
    if (mime.isNullOrEmpty()) return null
    val desktopId = runCommand("xdg-mime", "query", "default", mime)?.trim()
    if (desktopId.isNullOrEmpty()) return null
    val fallbackName = desktopId.removeSuffix(".desktop").trim()
    if (fallbackName.isEmpty()) return null

    val desktopFile = linuxDesktopSearchDirs()
        .asSequence()
        .map { File(it, desktopId) }
        .firstOrNull { it.isFile }
        ?: return fallbackName

    val lines = runCatching { desktopFile.readLines() }.getOrElse { return fallbackName }
    // 本地化名优先（中文环境直出中文），否则 Name=
    val localized = lines.firstNotNullOfOrNull { desktopEntryValue(it, prefix = "Name[zh_CN") }
    if (!localized.isNullOrEmpty()) return localized
    val plain = lines.firstNotNullOfOrNull { desktopEntryValue(it, prefix = "Name") }
    if (!plain.isNullOrEmpty()) return plain
    return fallbackName
}

/** 从 `.desktop` 文件的一行里取 `prefix...=value` 的 value；注释行/格式不对返回 null。 */
private fun desktopEntryValue(line: String, prefix: String): String? {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("#")) return null
    if (!trimmed.startsWith(prefix)) return null
    val rest = trimmed.substring(prefix.length)
    // Name 与 Name[xx] 之后必须紧跟 '=' 或 '['，避免把 NameFoo=xx 误当成 Name
    if (!rest.startsWith("=") && !rest.startsWith("[")) return null
    val value = trimmed.substringAfter('=', "").trim()
    return value.takeIf { it.isNotEmpty() }
}

private fun linuxDesktopSearchDirs(): List<String> = listOfNotNull(
    System.getProperty("user.home")?.let { "$it/.local/share/applications" },
    "/usr/share/applications",
    "/usr/local/share/applications"
)

/**
 * Windows：`cmd /c assoc .<ext>` 取 ProgID → `cmd /c ftype <ProgID>` 取命令行里的 exe 路径 → 友好名表。
 * assoc/ftype 文本随系统语言本地化，因此只按 `=<ProgID>` 与 `.exe` 路径解析，不碰任何说明文字。
 */
private fun windowsDefaultAppName(path: String): String? {
    val ext = path.substringAfterLast('.', "").trim().trim('.')
    if (ext.isEmpty()) return null

    val assocOutput = runCommand("cmd", "/c", "assoc", ".$ext") ?: return null
    val progId = assocOutput.lineSequence()
        .map { it.trim().trim('"') }
        .firstOrNull { it.startsWith(".$ext=", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val ftypeOutput = runCommand("cmd", "/c", "ftype", progId) ?: return null
    val exePath = extractExePath(ftypeOutput) ?: return null
    val exeName = exePath.substringAfterLast('\\').substringAfterLast('/').trim()
    if (exeName.isEmpty()) return null
    return WINDOWS_FRIENDLY_APP_NAMES[exeName.uppercase()] ?: exeName.removeSuffix(".exe").trim().ifEmpty { null }
}

/** 从 `ftype` 输出（形如 `Word.Document.12="C:\...\WINWORD.EXE" %1`）里抓 exe 路径；抓不到返回 null。 */
private fun extractExePath(ftypeOutput: String): String? {
    val idx = ftypeOutput.lowercase().indexOf(".exe")
    if (idx < 0) return null
    val head = ftypeOutput.substring(0, idx)
    // 有引号时取最后一个 `"` 之后的部分；否则取最后一个空格之后的部分
    val quote = head.lastIndexOf('"')
    val path = if (quote >= 0) head.substring(quote + 1) else head.substringAfterLast(' ')
    return path.trim().takeIf { it.isNotEmpty() }
}

// ---------------------------------------------------------------- revealInFolder

actual fun revealInFolder(path: String): Boolean = runCatching {
    when {
        isMacOs -> runCommandExitCode("open", "-R", path) == 0
        // explorer 永远返回非零 exit（Windows 特性），故只看「能否启动」
        isWindowsOs -> runCatching { ProcessBuilder(listOf("explorer", "/select,\"$path\"")).start() }.isSuccess
        isLinuxOs -> linuxRevealInFolder(path)
        else -> false
    }
}.getOrDefault(false)

/** KDE 用 dolphin --select 真正选中；GNOME 等 nautilus 无可靠 select CLI → 回落打开父目录。 */
private fun linuxRevealInFolder(path: String): Boolean {
    val currentDesktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().uppercase()
    if (currentDesktop.contains("KDE") && runCommandExitCode("dolphin", "--select", path) == 0) {
        return true
    }
    val parent = File(path).parent ?: return false
    return runCommandExitCode("xdg-open", parent) == 0
}

// ---------------------------------------------------------------- 命令执行工具

/**
 * 外部命令的统一执行助手（internal 暴露给 jvmTest 做超时行为单测）。
 *
 * 铁律（2026-10 补）：等待一律带超时 [timeoutMillis]（默认 [DEFAULT_COMMAND_TIMEOUT_MILLIS] = 3s），
 * 超时即 `destroyForcibly()` 返回 null——UI 线程 / 调用方绝不能被挂死的外部命令冻结。
 * 数组传参（ProcessBuilder(List)），禁拼 shell 字符串。
 */
internal const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 3_000L

/** 数组传参跑一条命令，返回 stdout+stderr 合并后的 trim 文本；命令不存在/异常/超时返回 null。 */
private fun runCommand(vararg cmd: String): String? =
    runCommand(cmd.toList(), DEFAULT_COMMAND_TIMEOUT_MILLIS)

internal fun runCommand(cmd: List<String>, timeoutMillis: Long): String? = runCatching {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        null
    } else {
        process.inputStream.bufferedReader().use { it.readText() }.trim()
    }
}.getOrNull()

/** 数组传参跑一条命令，返回 exit code（命令不存在/异常/超时返回 null）。 */
private fun runCommandExitCode(vararg cmd: String): Int? =
    runCommandExitCode(cmd.toList(), DEFAULT_COMMAND_TIMEOUT_MILLIS)

internal fun runCommandExitCode(cmd: List<String>, timeoutMillis: Long): Int? = runCatching {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        null
    } else {
        process.exitValue()
    }
}.getOrNull()