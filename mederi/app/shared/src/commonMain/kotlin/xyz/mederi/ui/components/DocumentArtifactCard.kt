package xyz.mederi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.*
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.attachment_lines
import mederi.app.shared.generated.resources.attachment_reader
import mederi.app.shared.generated.resources.attachment_writing
import mederi.app.shared.generated.resources.copy
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors

/**
 * 独立长文 Markdown 产物卡片 (DocumentArtifactCard)
 *
 * 遵循 Mederi 设计语言：
 * - 圆角 8dp，surfaceCard 背景，细边框 surfaceCardBorder，鼠标 hover 高亮；
 * - 左侧 34dp 图标容器（accentPrimary 强调背景 + FileText 图标，生成中伴随呼吸动画）；
 * - 中间两行：主标题（13sp SemiBold） + 元信息（生成时间 / 实时行数与字符数跳动）；
 * - 底部：1.5dp 流动光效指示条（仅在 isStreaming 且未完成时以动画横向流动展现）；
 * - 右侧：紧凑型“阅读器”胶囊按钮（带有 FeatherIcons.Sidebar）；
 * - 点击整张卡片即可滑出右侧扩展窗口并流式排版渲染。
 */
@Composable
fun DocumentArtifactCard(
    title: String,
    lineCount: Int,
    charCount: Int,
    createdAt: Long,
    isStreaming: Boolean,
    isCompleted: Boolean,
    onOpenInExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val isRunning = isStreaming && !isCompleted

    // 呼吸脉冲动画（生成中时图标微光）
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    // 底部流动光条平移动画
    val shimmerProgress by if (isRunning) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(8.dp))
            .clickable { onOpenInExtension() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧图标 + 中间标题与元数据
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. 图标容器
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentPrimary.copy(alpha = if (isRunning) 0.18f * pulseAlpha else 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FileText,
                            contentDescription = null,
                            tint = colors.accentPrimary.copy(alpha = if (isRunning) pulseAlpha else 1f),
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // 2. 中间文本信息
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    color = colors.statusWorking,
                                    strokeWidth = 1.3.dp,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = stringResource(Res.string.attachment_writing) + " · " +
                                        stringResource(Res.string.attachment_lines, lineCount) + " · " +
                                        formatCharCount(charCount),
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                val timeStr = if (createdAt > 0) xyz.mederi.formatMessageTime(createdAt) else ""
                                val meta = listOfNotNull(
                                    timeStr.takeIf { it.isNotBlank() },
                                    stringResource(Res.string.attachment_lines, lineCount),
                                    formatCharCount(charCount)
                                ).joinToString(" · ")
                                Text(
                                    text = meta,
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // 右侧“阅读器”胶囊按钮
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.buttonSecondary)
                        .padding(horizontal = 8.dp, vertical = 4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Sidebar,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = stringResource(Res.string.attachment_reader),
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 底部 1.5dp 流动光条
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .drawBehind {
                            val barWidth = size.width * 0.4f
                            val startX = (size.width + barWidth) * shimmerProgress - barWidth
                            drawRect(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        colors.accentPrimary,
                                        colors.accentSecondary,
                                        Color.Transparent
                                    ),
                                    startX = startX,
                                    endX = startX + barWidth
                                )
                            )
                        }
                )
            }
        }
    }
}
