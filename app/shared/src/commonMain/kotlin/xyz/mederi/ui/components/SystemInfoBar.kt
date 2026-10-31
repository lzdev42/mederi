package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.ProcessStats
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.WorkspaceStyle
import xyz.mederi.ui.components.atoms.ProcessRow
import xyz.mederi.util.formatBytes
import xyz.mederi.util.formatCpuUsage

/** 资源诊断只控制视图展开；采集与业务任务状态保持各自的数据源。 */
@Composable
fun SystemInfoBar(stats: ProcessStats? = null, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    SystemInfoContent(stats, expanded, { expanded = it }, modifier)
}

@Composable
internal fun SystemInfoContent(
    stats: ProcessStats?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val cpuLabel = stringResource(Res.string.sys_cpu)
    val cpu = formatCpuUsage(stats?.cpuUsage)
    Column(modifier.fillMaxWidth().background(colors.surfaceSidebar)) {
        HorizontalDivider(color = colors.divider)
        ProcessRow(
            expanded = expanded, onExpandedChange = onExpandedChange,
            modifier = Modifier.padding(horizontal = 8.dp),
            icon = FeatherIcons.Activity,
            label = stringResource(Res.string.sysinfo_resources),
            summary = "$cpuLabel $cpu",
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val cores = if (stats != null && stats.cpuCores > 0)
                    " " + stringResource(Res.string.sysinfo_cores, stats.cpuCores) else ""
                ResourceMetric(cpuLabel, "$cpu$cores")
                ResourceMetric(stringResource(Res.string.sysinfo_memory_rss), formatBytes(stats?.rssBytes))
                val heap = if (stats?.heapMaxBytes != null)
                    "${formatBytes(stats.heapUsedBytes)} / ${formatBytes(stats.heapMaxBytes)}"
                else formatBytes(stats?.heapUsedBytes)
                ResourceMetric(stringResource(Res.string.sysinfo_heap), heap)
            }
        }
    }
}

@Composable
private fun ResourceMetric(label: String, value: String) {
    val colors = LocalMederiColors.current
    Column {
        Text(label, color = colors.textSecondary, style = WorkspaceStyle.Caption)
        Text(value, color = colors.textPrimary, style = WorkspaceStyle.Body)
    }
}
