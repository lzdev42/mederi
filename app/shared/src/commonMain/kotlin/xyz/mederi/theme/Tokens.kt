package xyz.mederi.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// 全局设计 Token（取值真理源：docs/design/standard/01-tokens.md §2.3 / §3.1 / §4.1）
// 与主题无关：dark/light 共用同一套 dp/sp 值。
// 命名避开 panel-local 的 ProviderTokens（ui/settings，保留待后续任务消除）。
// ============================================================================

/** 间距刻度（01-tokens §3.1） */
object MederiSpacing {
    /** 标准刻度 2/3/4/5/6/8/10/12/14/16/20/24 的命名化；5 归 XS~Tight 之间按需用 Tiny/Tight */
    val Micro = 2.dp
    val Tiny = 3.dp
    val XS = 4.dp
    val Tight = 6.dp
    val SM = 8.dp
    val Compact = 10.dp
    val MD = 12.dp
    val Loose = 14.dp
    val LG = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
}

/** 圆角刻度（01-tokens §4.1） */
object MederiRadius {
    /** badge：tag / chip / status badge */
    val Badge = 3.dp
    /** square：icon-btn-minimal / panel-header-btn 方角图标按钮（标准 §1.5 / §1.7 的 4dp） */
    val Square = 4.dp
    /** control：按钮 / 输入 / pill / switch */
    val Control = 6.dp
    /** card：卡片 / 容器 */
    val Card = 8.dp
    /** pill：= track 高一半（tab-badge / running-pulse / switch-track） */
    val Pill = 10.dp
    /** Bubble 仅用于 user-bubble 特殊 RoundedCornerShape(12,12,2,12) */
    val Bubble = 12.dp
    /** Dialog 为弹窗保留（标准未覆盖弹窗，沿用现有 12dp） */
    val Dialog = 12.dp
}

/** 字号阶梯（01-tokens §2.3 12 档；450 字重 Compose 无，用 Normal 近似） */
object MederiTypeScale {
    val Caption = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val Micro = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Normal)
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val BodyCompact = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal)
    val Body = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val Row = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
    val Title = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Section = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal)
    val Header = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val H2 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val Metric = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val MetricLg = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium)
}
