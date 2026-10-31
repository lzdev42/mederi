package xyz.mederi.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.WorkspaceStyle

/** 兼容既有供应商调用点；所有值转发canonical工作台规格，不建立第二套刻度。 */
internal object ProviderTokens {
    val SpacingXSmall = MederiSpacing.XS
    val SpacingSmall = MederiSpacing.SM
    val SpacingMedium = MederiSpacing.MD
    val SpacingLarge = MederiSpacing.LG
    val SpacingXLarge = MederiSpacing.XXL

    val RadiusBadge = RoundedCornerShape(MederiRadius.Badge)
    val RadiusControl = RoundedCornerShape(MederiRadius.Control)
    val RadiusCard = RoundedCornerShape(WorkspaceStyle.ObjectRadius)
    val RadiusWorkspace = RoundedCornerShape(WorkspaceStyle.InputRadius)

    val FontTitle = WorkspaceStyle.Title.fontSize
    val FontValue = WorkspaceStyle.Body.fontSize
    val FontLabel = WorkspaceStyle.Caption.fontSize
    val FontBadge = WorkspaceStyle.Caption.fontSize
}
