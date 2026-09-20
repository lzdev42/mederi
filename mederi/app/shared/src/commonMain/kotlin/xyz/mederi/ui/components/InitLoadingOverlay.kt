package xyz.mederi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.initload_initializing
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.LocalMederiColors

/**
 * 初始化全屏加载遮罩层。
 * 注意：当前架构为直接调用库，已不存在异步初始化过程；本组件暂无用途，留下备用。
 */
@Composable
fun InitLoadingOverlay(
    isVisible: Boolean,
    statusText: String = stringResource(Res.string.initload_initializing)
) {
    if (!isVisible) return

    val colors = LocalMederiColors.current

    // 全屏半透明遮罩并拦截手势/点击
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfaceOverlay)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // 拦截点击
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceCard)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = colors.accentPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )

            Text(
                text = statusText,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
