package xyz.mederi.ui.browser

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 浏览器自动化操作动作类别（供 UI 匹配对应前置图标）。
 */
@Serializable
enum class BrowserActionKind {
    /** 打开 / 跳转网页 */
    NAVIGATE,

    /** 点击页面元素 */
    CLICK,

    /** 输入文本 / 填写表单 */
    INPUT,

    /** 滚动页面 */
    SCROLL,

    /** 读取页面结构 / 快照 */
    SNAPSHOT,

    /** 等待页面或元素 */
    WAIT,

    /** 执行脚本 */
    EVALUATE,

    /** 其他动作 */
    OTHER,
}

/**
 * 单个自动化动作步骤的执行状态。
 */
@Serializable
enum class BrowserStepStatus {
    /** 正在执行中（高亮卡片 + 旋转加载指示器） */
    RUNNING,

    /** 已完成（绿色对勾 + 耗时） */
    COMPLETED,

    /** 执行失败（红色叉号） */
    FAILED,
}

/**
 * 浏览器自动化流程时间线中的单个节点（MVVM Model）。
 *
 * 分为三类：
 * 1. [Narration]：AI 发出的自然语言旁白/思考说明（如 `"我看看网页"`）；
 * 2. [Action]：浏览器具体执行动作（如 `"正在点击"`、`"正在打开网页"`）；
 * 3. [Screenshot]：直接内嵌展示的网页截图缩略图卡片（支持点击放大预览）。
 */
@Immutable
@Serializable
sealed interface BrowserFlowStep {
    /** 节点稳定唯一标识（供 `LazyColumn` key 复用） */
    val id: String

    /** 节点产生时间戳（epoch ms，0 表示未指定） */
    val timestampMillis: Long

    /**
     * AI 旁白 / 思考说明节点（如 `"我看看网页上的搜索结果"`）。
     */
    @Immutable
    @Serializable
    data class Narration(
        override val id: String,
        val text: String,
        override val timestampMillis: Long = 0L,
    ) : BrowserFlowStep

    /**
     * 浏览器执行动作节点（如 `"正在点击"`、`"正在打开网页"`）。
     *
     * @property kind 动作类型（决定图标）
     * @property title 动作主文案（如 `"正在点击"`）
     * @property target 操作目标描述或 URL（如 `"Kotlin Docs | Documentation"`）
     * @property status 当前动作执行状态
     * @property durationMs 动作耗时（毫秒，完成时填充）
     */
    @Immutable
    @Serializable
    data class Action(
        override val id: String,
        val kind: BrowserActionKind = BrowserActionKind.OTHER,
        val title: String,
        val target: String? = null,
        val status: BrowserStepStatus = BrowserStepStatus.RUNNING,
        val durationMs: Long? = null,
        override val timestampMillis: Long = 0L,
    ) : BrowserFlowStep

    /**
     * 网页截图节点：直接在时间线内渲染截图缩略图，点击可放大预览高清大图。
     *
     * @property imageUrl 图片数据源（支持本地绝对路径、`file://`、`data:image/png;base64,...`、HTTP URL）
     * @property title 截图标题说明（如 `"搜索结果页截图"`，为空时使用默认文案）
     * @property resolution 可选尺寸或元信息微标（如 `"1280×800"`）
     */
    @Immutable
    @Serializable
    data class Screenshot(
        override val id: String,
        val imageUrl: String,
        val title: String? = null,
        val resolution: String? = null,
        override val timestampMillis: Long = 0L,
    ) : BrowserFlowStep
}

/**
 * 浏览器自动化流程视图的完整 MVVM 状态（由 `WorkspaceViewModel.browserAutomationState` 持有）。
 *
 * 核心规则：
 * - **自动化操作进行中（[isAutomating] == true）**：视图强制弹出显示（[isVisible] == true），
 *   且**不可关闭**（[canClose] == false，隐藏关闭按钮并显示执行中锁定标签）；
 * - **自动化操作结束后（[isAutomating] == false）**：允许用户手动关闭（[canClose] == true）
 *   或通过浏览器导航栏右侧的「流程」按钮重新展开查看；
 * - **宽度可调整**：通过左侧拖拽手柄在 [MIN_PANEL_WIDTH_DP] ~ [MAX_PANEL_WIDTH_DP] 范围内调节 [panelWidthDp]；
 * - **截图点击放大**：[previewScreenshot] 非空时弹出大图预览层（Lightbox）。
 */
@Immutable
@Serializable
data class BrowserAutomationUiState(
    val isAutomating: Boolean = false,
    val isPanelOpen: Boolean = false,
    val panelWidthDp: Float = DEFAULT_PANEL_WIDTH_DP,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val taskSummary: String? = null,
    val steps: List<BrowserFlowStep> = emptyList(),
    val previewScreenshot: BrowserFlowStep.Screenshot? = null,
) {
    /** 流程视图是否应当渲染：自动化进行中强制显示；非自动化时由 [isPanelOpen] 控制。 */
    val isVisible: Boolean
        get() = isAutomating || isPanelOpen

    /** 当前是否允许关闭流程视图：自动化操作进行中不可关闭。 */
    val canClose: Boolean
        get() = !isAutomating

    /** 流程中包含的截图总数。 */
    val screenshotCount: Int
        get() = steps.count { it is BrowserFlowStep.Screenshot }

    companion object {
        const val DEFAULT_PANEL_WIDTH_DP = 268f
        const val MIN_PANEL_WIDTH_DP = 180f
        const val MAX_PANEL_WIDTH_DP = 420f

        fun clampWidthDp(widthDp: Float): Float =
            widthDp.coerceIn(MIN_PANEL_WIDTH_DP, MAX_PANEL_WIDTH_DP)
    }
}
