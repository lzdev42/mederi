package xyz.mederi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Play
import xyz.mederi.core.contract.models.Conversation
import xyz.mederi.core.contract.models.ConversationStatus
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiColors

@Composable
fun SubAgentCard(
    conversation: Conversation,
    isExpandedDefault: Boolean = false,
    onSelectConversation: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalMederiColors.current
    var isExpanded by remember { mutableStateOf(isExpandedDefault) }

    val subAgentName = conversation.title
    val status = if (conversation.status == ConversationStatus.Working) "Running..." else "Idle"
    val goal = "智能体关联目标: General"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "SubAgent: $subAgentName",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (status.contains("Done") || status.contains("Completed")) colors.accentSuccess.copy(alpha = 0.2f) else colors.accentPrimary.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = status,
                        color = if (status.contains("Done") || status.contains("Completed")) colors.accentSuccess else colors.accentPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = if (isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = "展开",
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { isExpanded = !isExpanded }
                )
            }
        }

        if (goal.isNotEmpty()) {
            Text(
                text = "目标: $goal",
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = if (isExpanded) 10 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HorizontalDivider(color = colors.divider)
                Text(
                    text = "子 Agent 后台异步运行中，输出已同步回主流程。",
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
                TextButton(
                    onClick = { onSelectConversation(conversation.id) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("查看独立日志详情 >", color = colors.accentPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun SubAgentTabContent(
    subAgents: List<Conversation>,
    colors: MederiColors,
    onSelectConversation: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "子 AGENT 任务追踪器 (${subAgents.size})",
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        if (subAgents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "当前会话暂无派生的子 Agent 任务",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
        } else {
            subAgents.forEach { conversation ->
                SubAgentCard(
                    conversation = conversation,
                    isExpandedDefault = false,
                    onSelectConversation = onSelectConversation
                )
            }
        }
    }
}
