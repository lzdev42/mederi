package xyz.mederi.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// 1. 设计 Token 定义 (Design Tokens)
// ============================================================================
internal object ProviderTokens {
    val SpacingXSmall = 4.dp
    val SpacingSmall = 8.dp
    val SpacingMedium = 12.dp
    val SpacingLarge = 16.dp
    val SpacingXLarge = 24.dp

    val RadiusBadge = RoundedCornerShape(4.dp)
    val RadiusControl = RoundedCornerShape(6.dp)
    val RadiusCard = RoundedCornerShape(8.dp)
    val RadiusWorkspace = RoundedCornerShape(10.dp)

    val FontTitle = 13.5.sp
    val FontValue = 12.sp
    val FontLabel = 11.sp
    val FontBadge = 10.5.sp
}