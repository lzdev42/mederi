package xyz.mederi.ui.overview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.mederi.core.contract.preferences.PreferencesStore

/**
 * 概览浮动窗的动态状态（最小化 / 手风琴展开行）。
 *
 * - [minimized] 控制窗口 ⇄ 胶囊态的 Crossfade 切换，持久化到 preferences.json；
 * - [expandedRowIndex] 为手风琴单展开互斥索引（null = 全部折叠）。
 *
 * 位置固定在右上角，不需要持久化坐标。
 */
class OverviewWindowState {
    /** 是否最小化为胶囊（true = 胶囊态） */
    var minimized by mutableStateOf(false)

    /** 手风琴当前展开行索引（null = 全部折叠；单展开互斥） */
    var expandedRowIndex by mutableStateOf<Int?>(null)

    /** 读取持久化的最小化状态。 */
    suspend fun load(prefs: PreferencesStore) {
        minimized = prefs.getBoolean(KEY_MIN, false)
    }

    /** 写回最小化状态。 */
    suspend fun save(prefs: PreferencesStore) {
        prefs.putBoolean(KEY_MIN, minimized)
    }

    companion object {
        private const val KEY_MIN = "overview.window.minimized"
    }
}
