package xyz.mederi.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** 设备级表现策略；不依赖任务业务状态，不控制任务执行或输入可用性。 */
@Immutable
data class WorkspaceMotionPolicy(val reducedMotion: Boolean = false) {
    val feedbackMillis: Int get() = if (reducedMotion) 0 else FeedbackMillis
    val disclosureMillis: Int get() = if (reducedMotion) 0 else DisclosureMillis
    val panelMillis: Int get() = if (reducedMotion) 0 else PanelMillis
    fun loops(running: Boolean, visible: Boolean = true): Boolean = running && visible && !reducedMotion
    fun <T> feedbackSpec(): TweenSpec<T> = tween(feedbackMillis, easing = FastOutSlowInEasing)
    fun <T> disclosureSpec(): TweenSpec<T> = tween(disclosureMillis, easing = FastOutSlowInEasing)
    fun <T> panelSpec(): TweenSpec<T> = tween(panelMillis, easing = FastOutSlowInEasing)

    companion object {
        const val FeedbackMillis = 120
        const val DisclosureMillis = 180
        const val PanelMillis = 200
        const val TaskCycleMillis = 2400
        const val LoadingCycleMillis = 1000
    }
}

val LocalWorkspaceMotion = staticCompositionLocalOf { WorkspaceMotionPolicy() }
