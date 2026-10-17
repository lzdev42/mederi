package xyz.mederi.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckSquare
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.rp_todo_list_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoStatus
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.ui.WorkspaceViewModel

/**
 * 概览浮动窗 Todo 清单行。
 *
 * 折叠态摘要：`completedCount/totalCount`；空列表时整行不渲染（无内容不留空行）。
 * 展开态：四态任务列表（Pending 灰 / InProgress 高亮 / Completed 绿勾划线 / Failed 红），
 * 渲染逻辑与 TodoListCard 一致；Cancelled 无特殊处理（走 else 灰底分支）。
 */
@Composable
fun OverviewTodoRow(
    viewModel: WorkspaceViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val todoList = viewModel.todos
    val completedCount = todoList.count { it.status == TodoStatus.Completed }
    // 空列表时整行不渲染（不调用 OverviewAccordionRow）
    if (todoList.isEmpty()) return

    OverviewAccordionRow(
        icon = FeatherIcons.CheckSquare,
        title = stringResource(Res.string.rp_todo_list_title),
        summary = {
            Text(
                text = "$completedCount/${todoList.size}",
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
            )
        },
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todoList.forEach { task ->
                TodoRowItem(task = task)
            }
        }
    }
}

@Composable
private fun TodoRowItem(task: TodoItem) {
    val colors = LocalMederiColors.current
    // 四态：Completed 绿勾 / InProgress 高亮圆点 / Failed 红 / else（Pending/Cancelled）灰
    val isDone = task.status == TodoStatus.Completed
    val isRunning = task.status == TodoStatus.InProgress
    val isFailed = task.status == TodoStatus.Failed
    val boxColor = when {
        isDone -> colors.accentPrimary
        isRunning -> colors.accentPrimary
        isFailed -> colors.accentDanger
        else -> colors.buttonSecondary
    }
    val textColor = when {
        isDone -> colors.textMuted
        isRunning -> colors.textPrimary
        isFailed -> colors.accentDanger
        else -> colors.textPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (isRunning) boxColor.copy(alpha = 0.25f) else boxColor
                )
                .border(
                    1.dp,
                    boxColor,
                    RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = colors.onAccentPrimary,
                    modifier = Modifier.size(10.dp),
                )
            } else if (isRunning) {
                // 进行中：实心圆点（点即"正在做"的视觉焦点，与 TodoListCard 一致）
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.accentPrimary)
                )
            }
        }

        Text(
            text = task.content,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
            textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}