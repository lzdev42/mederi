package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.sys_cpu
import mederi.app.shared.generated.resources.sysinfo_cores
import mederi.app.shared.generated.resources.sysinfo_heap
import mederi.app.shared.generated.resources.sysinfo_memory_rss
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProcessStats
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.util.formatBytes
import xyz.mederi.util.formatCpuUsage

/**
 * 吸底系统信息栏（SystemInfoBar）：纯进程资源消耗监控（CPU / RSS / JVM 堆）。
 *
 * 不显示业务错误——错误/警告统一走 ErrorBoard（输入框上方）。
 */
@Composable
fun SystemInfoBar(
    stats: ProcessStats? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(colors.surfaceSidebar)
            .border(width = 1.dp, color = colors.divider)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：进程资源消耗（CPU、RSS物理内存、JVM堆内存）
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CPU 指标
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.sys_cpu),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
                val cpuText = formatCpuUsage(stats?.cpuUsage)
                val coresText = if (stats != null && stats.cpuCores > 0) stringResource(Res.string.sysinfo_cores, stats.cpuCores) else ""
                Text(
                    text = "$cpuText$coresText",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "•",
                color = colors.divider,
                fontSize = 12.sp
            )

            // RSS 真实常驻内存指标
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.sysinfo_memory_rss),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = formatBytes(stats?.rssBytes),
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "•",
                color = colors.divider,
                fontSize = 12.sp
            )

            // JVM 堆内存指标
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.sysinfo_heap),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
                val heapText = if (stats?.heapMaxBytes != null) {
                    "${formatBytes(stats.heapUsedBytes)} / ${formatBytes(stats.heapMaxBytes)}"
                } else {
                    formatBytes(stats?.heapUsedBytes)
                }
                Text(
                    text = heapText,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}