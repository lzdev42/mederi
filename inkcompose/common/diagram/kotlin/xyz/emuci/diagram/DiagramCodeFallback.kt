package xyz.emuci.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 无法渲染的图表（PlantUML/DOT 等非 Mermaid 语法、渲染失败）降级为源码展示。
 * 样式自包含（MaterialTheme），不依赖 markdown 渲染主题，供 diagram 域与
 * markdown 管线的 Fallback 路由共用。
 */
@Composable
internal fun DiagramCodeFallback(
    code: String,
    typeName: String,
    modifier: Modifier = Modifier,
    decorate: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentModifier = if (decorate) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colorScheme.surfaceVariant)
            .padding(12.dp)
    } else {
        modifier.fillMaxWidth()
    }

    Column(
        modifier = contentModifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Text(
                text = "📊",
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = "$typeName Diagram",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant,
                ),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = code.trimEnd('\n'),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
