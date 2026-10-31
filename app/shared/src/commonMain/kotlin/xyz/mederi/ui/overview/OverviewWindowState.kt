package xyz.mederi.ui.overview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.mederi.core.contract.preferences.PreferencesStore

/**
 * 概览浮动窗的动态状态（显示隐藏 / 手风琴展开行）。
 *
 * - [visible] 控制窗口显示/隐藏，持久化到 preferences.json；
 * - [expandedRowIndex] 为手风琴单展开互斥索引（null = 全部折叠）。
 *
 * 位置固定在右上角，不需要持久化坐标。
 */
class OverviewWindowState {
    /** 是否显示概览；隐藏不清空手风琴展开状态。 */
    var visible by mutableStateOf(true)

    /** 手风琴当前展开行索引（null = 全部折叠；单展开互斥） */
    var expandedRowIndex by mutableStateOf<Int?>(null)

    /** 读取持久化的最小化状态。 */
    suspend fun load(prefs: PreferencesStore) {
        visible = prefs.getBoolean(KEY_VISIBLE, !prefs.getBoolean(KEY_MIN, false))
    }

    /** 写回最小化状态。 */
    suspend fun save(prefs: PreferencesStore) {
        prefs.putBoolean(KEY_VISIBLE, visible)
    }

    companion object {
        private const val KEY_VISIBLE = "overview.window.visible"
        private const val KEY_MIN = "overview.window.minimized"
    }
}
