package xyz.mederi.ui.components.atoms

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import xyz.mederi.theme.LocalMederiColors
import xyz.mederi.theme.MederiRadius
import xyz.mederi.theme.MederiSpacing
import xyz.mederi.theme.MederiTypeScale

/**
 * Mederi chip / badge / status-dot 原子合集（docs/design/standard/02-components.md §3.6-§3.10）。
 *
 * 结构（尺寸/圆角/字号）按标准走 [MederiSpacing] / [MederiRadius] / [MederiTypeScale]；
 * 颜色读 [LocalMederiColors] 现有字段保持当前视觉（本轮不迁 Radix 色阶）。
 * 禁裸 hex；唯一例外 = Kt 品牌渐变 [KotlinGradient]（标准 §3.9 明示允许，集中定义于此）。
 *
 * 标准色阶缺口的近似约定（各原子 KDoc 亦注明）：
 * - iris-3 / iris-6 / iris-11（accent 软底/描边/文字）→ accentPrimary.copy(alpha=0.12f/0.35f) / accentPrimary；
 * - amber-3 / amber-6 / amber-11（warning 软底/描边/文字）→ accentWarning.copy(alpha=0.12f/0.35f) / accentWarning；
 * - green-3 / green-6 / green-11（success 软底/描边/文字）→ accentSuccess.copy(alpha=0.12f/0.35f) / accentSuccess；
 * - gray-4（role-tag / project-pill 底）→ surfaceHover。
 * 组件级尺寸不在全局刻度内（5dp 横padding、1dp 竖padding、6dp/7dp/8dp/13dp 点、14dp 方、9sp/8sp 字号），按标准值直用并注释。
 */

/**
 * Kotlin 品牌渐变（02-components §3.9 file-icon-kt-gradient）：标准明示的裸 hex 例外，全文件集中定义。
 * 仅 [MederiFileTypeIconSquare] 的 Kt 态使用。
 */
private val KotlinGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF7F52FF), Color(0xFFC711E1))
)

/** §3.6 tab-badge 字号：10sp 常规（现状 RawMessagesCard/MetricsCards 计数即 10sp 常规） */
private val TabBadgeText: TextStyle = MederiTypeScale.Caption.copy(fontWeight = FontWeight.Normal)

/** §3.6 plan-id-tag 字号：mono 10sp 常规 */
private val PlanIdText: TextStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)

/** §3.10 git-badge 字号：mono 10sp SemiBold */
private val GitBadgeText: TextStyle = TextStyle(
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold
)

/** §3.9 file-type-icon-square 字号：mono 8sp Bold */
private val FileTypeText: TextStyle = TextStyle(
    fontSize = 8.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold
)

/** §3.6 skill-compat-badge 字号：9sp SemiBold */
private val CompatText: TextStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)

/**
 * Tab 计数徽标（02-components §3.6 `tab-badge`）。
 *
 * 标准：1/5dp、radius Pill、`bg-input` + border、10sp `text-muted`。
 * 现状出处：RawMessagesCard / MetricsCards 的 CardHeader count（10sp textMuted）、
 * SubAgentComponents 头部计数（10sp textSecondary/accentPrimary）。
 * 按标准取 surfaceInput 底 + surfaceCardBorder 描边 + 10sp 常规 textMuted。
 */
@Composable
fun MederiTabBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Pill))
            .background(colors.surfaceInput)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Pill))
            .padding(horizontal = 5.dp, vertical = 1.dp), // 标准 1/5dp；5 不在 MederiSpacing 刻度，直用
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            color = colors.textMuted,
            style = TabBadgeText,
        )
    }
}

/**
 * 运行中脉冲徽标（02-components §3.6 `running-pulse-badge`）。
 *
 * 标准：1/6dp、radius Pill、accent 软底 + border iris-6、`accent-text` 10sp/500 + 6dp pulse dot
 * （accent + glow ring，1.5s scale 1↔1.3 / opacity 1↔0.6）。
 * 现状出处：SubAgentComponents「$runningCount 运行中」（RoundedCornerShape(10.dp) +
 * accentPrimary.copy(alpha=0.12f) + [RadarPulseDot] + 10sp Medium accentPrimary），
 * 现状无描边；按标准补 accent 级 border（accentPrimary.copy(alpha=0.35f)）。
 */
@Composable
fun MederiRunningPulseBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Pill))
            .background(colors.accentPrimary.copy(alpha = 0.12f))
            .border(
                1.dp,
                colors.accentPrimary.copy(alpha = 0.35f),
                RoundedCornerShape(MederiRadius.Pill)
            )
            .padding(horizontal = MederiSpacing.Tight, vertical = 1.dp), // 1/6dp
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MederiSpacing.XS),
    ) {
        RunningPulseDot(color = colors.accentPrimary)
        Text(
            text = text,
            color = colors.accentPrimary,
            style = MederiTypeScale.Caption, // 10sp Medium
        )
    }
}

/**
 * running-pulse-badge 的 6dp 脉冲圆点（02-components §3.6）：accent 实心 + 呼吸光环。
 * 1.5s scale 1↔1.3 / opacity 1↔0.6 反向循环（rememberInfiniteTransition 实现）。
 */
@Composable
private fun RunningPulseDot(
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "running_pulse_dot")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "running_pulse_scale"
    )
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "running_pulse_alpha"
    )
    Box(
        modifier = Modifier.size(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 光环：随 scale 放大、随 alpha 变淡
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                .clip(CircleShape)
                .border(1.5.dp, color, CircleShape)
        )
        // 核心点：呼吸透明度
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer(alpha = alpha)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * 子代理/角色标签（02-components §3.6 `subagent-role-tag`）。
 *
 * 标准：1/5dp、radius Badge、gray-4 底、`text-primary` 10sp/500。
 * 现状出处：SubAgentComponents「协同角色」行（11sp textPrimary Medium，未成徽标），
 * 此处按标准落徽标：surfaceHover 底 + 10sp Medium textPrimary。
 */
@Composable
fun MederiRoleTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Badge))
            .background(colors.surfaceHover)
            .padding(horizontal = 5.dp, vertical = 1.dp), // 标准 1/5dp
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.textPrimary,
            style = MederiTypeScale.Caption, // 10sp Medium
        )
    }
}

/**
 * 技能兼容徽标（02-components §3.6 `skill-compat-badge`）。
 *
 * 标准：1/4dp、radius Badge、amber-3/amber-6 底+描边、amber-11 9sp/600。
 * 现状出处：SkillManagementCard（RoundedCornerShape(3.dp) + accentPrimary.copy(alpha=0.12f) + 9sp Medium accentPrimary，无描边）；
 * 现状用 accent 系、标准为 amber 系——按 spec 以 warning（amber 等效）为准，并补标准 amber-6 级描边。
 */
@Composable
fun MederiCompatBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Badge))
            .background(colors.accentWarning.copy(alpha = 0.12f))
            .border(
                1.dp,
                colors.accentWarning.copy(alpha = 0.35f),
                RoundedCornerShape(MederiRadius.Badge)
            )
            .padding(horizontal = MederiSpacing.XS, vertical = 1.dp), // 1/4dp
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.accentWarning,
            style = CompatText,
        )
    }
}

/**
 * 项目胶囊（02-components §3.6 `header-project-pill`）。
 *
 * 标准：4/10dp、radius Control、gray-3 + border、`text-secondary` 12sp。
 * 现状出处：暂无现成内联实现（StatusBar / Workspace 未落项目徽标），按标准落地：
 * surfaceHover 底 + surfaceCardBorder 描边 + 12sp textSecondary。
 */
@Composable
fun MederiProjectPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Control))
            .background(colors.surfaceHover)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Control))
            .padding(horizontal = MederiSpacing.Compact, vertical = MederiSpacing.XS), // 4/10dp
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.textSecondary,
            style = MederiTypeScale.Body, // 12sp
        )
    }
}

/**
 * 计划 ID 标签（02-components §3.6 `plan-id-tag` / §2.5 plan-approval-block）。
 *
 * 标准：1/5dp、radius Badge、gray-3 + border、mono 10sp `text-secondary`。
 * 现状出处：PlanApprovalCard 仅有 DebugLog 记录 planId、未渲染徽标，按标准落地：
 * surfaceHover 底 + surfaceCardBorder 描边 + mono 10sp textSecondary。
 */
@Composable
fun MederiPlanIdTag(
    planId: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Badge))
            .background(colors.surfaceHover)
            .border(1.dp, colors.surfaceCardBorder, RoundedCornerShape(MederiRadius.Badge))
            .padding(horizontal = 5.dp, vertical = 1.dp), // 标准 1/5dp
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = planId,
            color = colors.textSecondary,
            style = PlanIdText,
        )
    }
}

/** [MederiStatusDot] 四态（02-components §3.7 conv-status-dot 的 Working/Waiting/Idle/Error 映射）。 */
enum class BadgeStatus {
    Working,
    Waiting,
    Idle,
    Error
}

/**
 * 会话/流转状态点（02-components §3.7 `conv-status-dot`）。
 *
 * 标准：6dp `accent`（idle→gray-8），现状为四态语义色：
 * Working→[MederiColors.statusWorking]、Waiting→[MederiColors.statusWaiting]、
 * Idle→[MederiColors.statusIdle]、Error→[MederiColors.statusError]。
 * 现状出处：Sidebar.kt 经 ConversationStatusDot（16dp 容器 + 6dp 核心点 + Working/Waiting 动效）
 * 消费同一组 status* 色；本原子为静态 6dp 圆点，[size] 可调（conv 6dp / 其他场景按需放大）。
 */
@Composable
fun MederiStatusDot(
    status: BadgeStatus,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    val colors = LocalMederiColors.current
    val color = when (status) {
        BadgeStatus.Working -> colors.statusWorking
        BadgeStatus.Waiting -> colors.statusWaiting
        BadgeStatus.Idle -> colors.statusIdle
        BadgeStatus.Error -> colors.statusError
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** [MederiStepStatusIcon] 步骤三态（02-components §3.7 step-dot-active / step-dot-todo / step-dot-done）。 */
enum class StepStatus {
    /** 执行中：8dp solid accent */
    Active,
    /** 待执行：7dp hollow，1.5dp gray-8 ring */
    Todo,
    /** 已完成：13dp check，14×14 green-11 */
    Done
}

/**
 * 计划步骤状态图标（02-components §3.7 step dots）。
 *
 * 现状出处：InfoPanels 子任务行（dot 6dp + 绿/蓝/textMuted 三色）、MetricsCards TodoListCard
 * （14dp 方框 + Check 图标 + accentPrimary 实心点）——本原子按标准 §3.7 落：
 * Active=8dp solid accent、Todo=7dp hollow（1.5dp textMuted ring，gray-8 等效）、
 * Done=14×14 内 13dp Check（accentSuccess，green-11 等效）。
 */
@Composable
fun MederiStepStatusIcon(
    status: StepStatus,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    when (status) {
        StepStatus.Active -> Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.accentPrimary)
        )
        StepStatus.Todo -> Box(
            modifier = modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(1.5.dp, colors.textMuted, CircleShape)
        )
        StepStatus.Done -> Box(
            modifier = modifier.size(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = colors.accentSuccess,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/** [MederiGitBadge] 三态（02-components §3.10 git/change badges）。 */
enum class GitChangeType {
    /** M：amber 软底+文字 */
    Modified,
    /** A：green 软底+文字 */
    Added,
    /** D：red 软底+文字（spec 清单将 Deleted 映射 danger；标准 §3.10 的 U/untracked 同 Added 走 green） */
    Deleted
}

/**
 * Git 变更徽标（02-components §3.10 `git/change badges`）。
 *
 * 标准：mono 10sp/600、0/4dp、radius Badge，M=amber-11 on amber-3、A/U=green-11 on green-3。
 * 现状出处：DiffPanelContent 仅以 +N/-N 数字着色（accentSuccess/accentDanger），未落字母徽标；
 * 按 spec 清单映射 Modified/Added/Deleted → warning/success/danger 软底+文字（amber/green/red 等效），
 * U（untracked）同 Added 走 green（标准 §3.10 原义）。
 */
@Composable
fun MederiGitBadge(
    type: GitChangeType,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val label = when (type) {
        GitChangeType.Modified -> "M"
        GitChangeType.Added -> "A"
        GitChangeType.Deleted -> "D"
    }
    val color = when (type) {
        GitChangeType.Modified -> colors.accentWarning
        GitChangeType.Added -> colors.accentSuccess
        GitChangeType.Deleted -> colors.accentDanger
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MederiRadius.Badge))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(MederiRadius.Badge))
            .padding(horizontal = MederiSpacing.XS, vertical = 0.dp), // 0/4dp
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = GitBadgeText,
        )
    }
}

/** [MederiFileTypeIconSquare] 三态（02-components §3.9 file type icon squares）。 */
enum class FileType {
    /** Kotlin：品牌渐变 [KotlinGradient]（标准明示裸 hex 例外） */
    Kt,
    /** Markdown：accentPrimary 底（现有等效 iris 系） */
    Md,
    /** Gradle：accentSuccess 底（现有等效 green 系） */
    Gradle
}

/**
 * 文件类型图标方块（02-components §3.9 `file type icon squares`）。
 *
 * 标准：14×14、radius Badge、白字 mono 8–8.5sp/700；Kt=品牌渐变 #7F52FF→#C711E1（[KotlinGradient]）、
 * MD=iris-3/iris-6/iris-11、Gradle=green-3/green-6/green-11。
 * 现状出处：无现成实现（DiffView 未落方块徽标），按 spec「白字 + 现有等效底色」落地：
 * Md→accentPrimary 实底、Gradle→accentSuccess 实底（保证 8sp 白字对比度；标准软底+色阶文字方案
 * 待色阶迁入后再切换）。14×14 放不下全词 "Gradle"，标签用缩写 "GL"（Kt / MD / GL）。
 */
@Composable
fun MederiFileTypeIconSquare(
    type: FileType,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMederiColors.current
    val label = when (type) {
        FileType.Kt -> "Kt"
        FileType.Md -> "MD"
        FileType.Gradle -> "GL"
    }
    val backgroundModifier = when (type) {
        FileType.Kt -> Modifier.background(KotlinGradient)
        FileType.Md -> Modifier.background(colors.accentPrimary)
        FileType.Gradle -> Modifier.background(colors.accentSuccess)
    }
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(MederiRadius.Badge))
            .then(backgroundModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = FileTypeText,
        )
    }
}
