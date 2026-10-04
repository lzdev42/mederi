package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.rp_todo_list_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.core.contract.models.TodoItem
import xyz.mederi.core.contract.models.TodoStatus
import xyz.mederi.theme.MederiColors
import xyz.mederi.ui.components.atoms.CardHeader
import xyz.mederi.ui.components.atoms.PanelCard

@Composable
internal fun TodoListCard(
    todoList: List<TodoItem>,
    colors: MederiColors
) {
    val completedCount = todoList.count { it.status == TodoStatus.Completed }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardHeader(
            icon = null,
            title = stringResource(Res.string.rp_todo_list_title),
            count = {
                Text(
                    text = "$completedCount/${todoList.size}",
                    color = colors.textSecondary,
                    fontSize = 10.sp
                )
            }
        )

        // Tasks list（四态：Pending 灰 / InProgress 高亮 / Completed 绿勾划线 / Failed 红）
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            todoList.forEach { task ->
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
                    isFailed -> colors.accentDanger
                    else -> colors.textPrimary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceWorkspace)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                tint = colors.onAccentPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                        } else if (isRunning) {
                            // 进行中：实心圆点（不引图标，点即"正在做"的视觉焦点）
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
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }
    }
}