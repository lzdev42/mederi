package xyz.emuci.diff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.diff.model.DiffFile

private val HEADER_BG = Color(0xFF18181B)
private val TEXT_PRIMARY = Color(0xFFF3F4F6)
private val TEXT_MUTED = Color(0xFF9CA3AF)
private val GREEN_ADD = Color(0xFF4ADE80)
private val RED_DEL = Color(0xFFF87171)

/**
 * 差异文件卡片顶部 Header 栏。
 */
@Composable
fun DiffFileHeader(
    diffFile: DiffFile,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dirPath = diffFile.displayPath.substringBeforeLast('/', "").ifEmpty { null }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HEADER_BG)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // 折叠箭头
            BasicText(
                text = if (isExpanded) "▼" else "▶",
                style = TextStyle(color = TEXT_MUTED, fontSize = 10.sp),
                modifier = Modifier.padding(end = 8.dp)
            )

            // 文件名
            BasicText(
                text = diffFile.fileName,
                style = TextStyle(
                    color = TEXT_PRIMARY,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            )

            // 文件路径
            if (dirPath != null) {
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(
                    text = dirPath,
                    style = TextStyle(
                        color = TEXT_MUTED,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                )
            }
        }

        // 右侧统计与状态
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (diffFile.stats.additions > 0 || diffFile.stats.deletions == 0) {
                BasicText(
                    text = "+${diffFile.stats.additions}",
                    style = TextStyle(
                        color = GREEN_ADD,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                )
            }
            if (diffFile.stats.deletions > 0 || diffFile.stats.additions == 0) {
                BasicText(
                    text = "-${diffFile.stats.deletions}",
                    style = TextStyle(
                        color = RED_DEL,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                )
            }
        }
    }
}
