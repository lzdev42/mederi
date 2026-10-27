package xyz.emuci.diff.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.diff.model.DiffDisplayItem
import xyz.emuci.syntax.theme.CodeTheme

/**
 * 折叠代码段落展开控制器组件。
 * 包含居中 "+N more lines" 胶囊与右侧上/下 "+10" 阶梯展开按钮。
 * 支持整行双击完全展开所有未修改代码行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiffHunkExpander(
    collapsed: DiffDisplayItem.Collapsed,
    modifier: Modifier = Modifier,
    theme: CodeTheme,
) {
    if (collapsed.remainingCount <= 0) return

    // patch 路径（无源行）：渲染不可交互静态标签
    if (collapsed.hiddenLines.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.diffExpanderBackground)
                    .border(1.dp, theme.diffExpanderBorderColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "+${collapsed.remainingCount} lines hidden",
                    style = TextStyle(
                        color = theme.diffExpanderTextColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onDoubleClick = { collapsed.expandAll() }
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // 1. 居中主胶囊 (+N more lines，点击全部展开)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.diffExpanderBackground)
                    .border(1.dp, theme.diffExpanderBorderColor, RoundedCornerShape(12.dp))
                    .clickable { collapsed.expandAll() }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "+${collapsed.remainingCount} more lines",
                    style = TextStyle(
                        color = theme.diffExpanderTextColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 2. 右侧阶梯展开双按钮 (上下两个 +10)
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 上方 +10：向折叠区上方扩展 10 行
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.diffExpanderBackground)
                        .border(1.dp, theme.diffExpanderBorderColor, RoundedCornerShape(6.dp))
                        .clickable { collapsed.expandTop(10) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "+10",
                        style = TextStyle(
                            color = theme.diffExpanderTextColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    )
                }

                // 下方 +10：向折叠区下方扩展 10 行
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.diffExpanderBackground)
                        .border(1.dp, theme.diffExpanderBorderColor, RoundedCornerShape(6.dp))
                        .clickable { collapsed.expandBottom(10) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "+10",
                        style = TextStyle(
                            color = theme.diffExpanderTextColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    )
                }
            }
        }
    }
}
