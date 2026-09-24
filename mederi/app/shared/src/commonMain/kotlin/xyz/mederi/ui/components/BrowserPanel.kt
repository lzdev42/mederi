package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.RightDockPanel
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 外部自动化浏览器（Camoufox）任务与配置监控面板。
 *
 * 架构：
 * 1. 顶部：可折叠 Camoufox 运行时配置区（状态、执行路径、模式参数骨架）；
 * 2. 底部：浏览器自动化任务执行流程追踪区（步骤列表、时间线状态骨架）。
 */
@Composable
internal fun BrowserPanelContent(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceWorkspace)
    ) {
        // 1. 顶部可折叠区：Camoufox 引擎配置
        CamoufoxConfigSection(colors = colors)

        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // 2. 底部主体区：自动化任务执行流程
        BrowserTaskFlowSection(
            viewModel = viewModel,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 顶部可折叠的 Camoufox 配置卡片。
 */
@Composable
private fun CamoufoxConfigSection(
    colors: MederiColors
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSidebar)
            .padding(12.dp)
    ) {
        // 头部：状态概要与折叠按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 运行状态指示点
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)) // 就绪绿色
                )
                Text(
                    text = "Camoufox 运行时",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "v0.4.x · 就绪",
                    fontSize = 11.sp,
                    color = colors.textMuted
                )
            }

            Icon(
                imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = if (isExpanded) "折叠配置" else "展开配置",
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        // 折叠内容：详细配置脚手架
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceWorkspace)
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConfigRow(label = "引擎模式", value = "外部独立进程 (BiDi)", colors = colors)
                ConfigRow(label = "执行路径", value = "自动探测 (~/.mederi/browser/camoufox)", colors = colors)
                ConfigRow(label = "指纹伪装", value = "启用 (Anti-Bot / Canvas Spoofing)", colors = colors)
                ConfigRow(label = "无头运行", value = "默认开启 (任务启动自动拉起)", colors = colors)
            }
        }
    }
}

@Composable
private fun ConfigRow(
    label: String,
    value: String,
    colors: MederiColors
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.textSecondary
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textPrimary
        )
    }
}

/**
 * 底部任务执行流程区（时间线 / 步骤流架子）。
 */
@Composable
private fun BrowserTaskFlowSection(
    viewModel: WorkspaceViewModel,
    colors: MederiColors,
    modifier: Modifier = Modifier
) {
    // 任务流程脚手架数据模型（占位）
    val dummySteps = remember {
        listOf(
            TaskStepItem("1", "启动外部浏览器", "就绪", StepStatus.SUCCESS, "0.4s"),
            TaskStepItem("2", "导航目标网页", "等待任务调度", StepStatus.IDLE, "-"),
            TaskStepItem("3", "提取页面结构 (AX Tree)", "等待任务调度", StepStatus.IDLE, "-"),
            TaskStepItem("4", "执行自动化操作", "等待任务调度", StepStatus.IDLE, "-")
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "任务执行流程",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = "空闲",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }

        // 步骤时间线列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(dummySteps) { step ->
                TaskStepRow(step = step, colors = colors)
            }
        }
    }
}

private enum class StepStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED
}

private data class TaskStepItem(
    val id: String,
    val title: String,
    val description: String,
    val status: StepStatus,
    val duration: String
)

@Composable
private fun TaskStepRow(
    step: TaskStepItem,
    colors: MederiColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceSidebar)
            .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val (statusColor, statusIcon) = when (step.status) {
            StepStatus.SUCCESS -> Color(0xFF4CAF50) to FeatherIcons.CheckCircle
            StepStatus.RUNNING -> Color(0xFF2196F3) to FeatherIcons.Loader
            StepStatus.FAILED -> Color(0xFFF44336) to FeatherIcons.AlertCircle
            StepStatus.IDLE -> colors.textMuted to FeatherIcons.Circle
        }

        Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(16.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Text(
                text = step.description,
                fontSize = 10.sp,
                color = colors.textMuted
            )
        }

        Text(
            text = step.duration,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textMuted
        )
    }
}

/** 右侧面板标题唯一映射点（枚举不持有表现层文案，i18n 约定）。 */
@Composable
internal fun rightDockPanelTitle(panel: RightDockPanel): String = stringResource(
    when (panel) {
        RightDockPanel.OVERVIEW -> Res.string.rightdock_overview
        RightDockPanel.DIFF -> Res.string.rightdock_diff
        RightDockPanel.PLAN -> Res.string.rightdock_plan
        RightDockPanel.ARTIFACTS -> Res.string.rightdock_artifacts
        RightDockPanel.TERMINAL -> Res.string.rightdock_terminal
        RightDockPanel.BROWSER -> Res.string.rightdock_browser
    }
)