package xyz.mederi.prompt

import xyz.mederi.tools.background.BackgroundTaskManager
import xyz.mederi.tools.sandbox.CommandSandbox
import xyz.mederi.tools.sandbox.SandboxConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 运行时环境信息提供器：每轮注入系统提示词动态尾段。
 *
 * 环境信息（时间 / OS / Shell / 沙箱 / 项目目录 / Java）从用户消息尾部移出，
 * 改为系统提示词动态后缀每轮注入——避免每条历史消息各带一份环境快照导致 token 膨胀。
 * 用户消息仅保留时间戳（per-message），环境元数据在此每轮刷新。
 */
object EnvironmentInfoProvider {

    fun build(
        commandSandbox: CommandSandbox?,
        directories: List<String>,
        stoppedBackgroundTasks: List<BackgroundTaskManager.BackgroundTaskEntry> = emptyList()
    ): String {
        val now = Instant.now()
        val tz = ZoneId.systemDefault()
        val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC).format(now)
        val utcDow = now.atZone(ZoneOffset.UTC).dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val zoned = now.atZone(tz)
        val local = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").format(zoned)
        val localDow = zoned.dayOfWeek
            .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

        return buildString {
            append("\n\n# Runtime Environment\n")
            appendLine("UTC: $utc ($utcDow)")
            appendLine("Local: $local (${tz.id}, $localDow)")
            append(CommandSandbox.environmentNote(commandSandbox))
            appendLine("Project dir: ${directories.first()}")
            SandboxConfig.extraWritablePaths.takeIf { it.isNotEmpty() }
                ?.let { appendLine("Extra writable paths: ${it.joinToString(", ")}") }
            appendLine("Mederi workdir: ${File(directories.first(), ".mederi").absolutePath}")
            // 用户停止的后台任务（consumeStoppedForSession 取出即消费，下轮不再显示）
            if (stoppedBackgroundTasks.isNotEmpty()) {
                appendLine("Recently stopped background tasks:")
                for (task in stoppedBackgroundTasks) {
                    val desc = task.command ?: task.description ?: task.taskId
                    appendLine("- ${task.taskId} (${task.type.name.lowercase()}): $desc [stopped by user]")
                }
            }
        }
    }
}
