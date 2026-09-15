package xyz.mederi.browser.bidi

import kotlinx.serialization.Serializable

@Serializable
data class AxNode(
    val refid: String = "",
    val tagName: String = "",
    val role: String = "",
    val id: String = "",
    val className: String = "",
    val text: String = "",
    val isVisible: Boolean = true,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val centerX: Int = 0,
    val centerY: Int = 0,
    val childCount: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
    val iframeSrc: String? = null,
    val selector: String = "",
    val occludedBy: String? = null,
    val nodeId: String = "",
    val childIds: List<String> = emptyList()
)

@Serializable
data class AxTreeData(
    val url: String = "",
    val innerWidth: Int = 0,
    val innerHeight: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val documentWidth: Int = 0,
    val documentHeight: Int = 0,
    val devicePixelRatio: Double = 1.0,
    val totalElements: Int = 0,
    val visibleElements: Int = 0,
    val hiddenElements: Int = 0,
    val iframeCount: Int = 0,
    val nodes: List<AxNode> = emptyList()
)

enum class SnapshotMode { CLEAN, RAW }

data class SnapshotResult(
    val yaml: String,
    val rawTree: AxTreeData
)

data class Rect(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
}

data class Point(val x: Int, val y: Int)

data class ViewportInfo(
    val scrollX: Int,
    val scrollY: Int,
    val viewW: Int,
    val viewH: Int
)

@Serializable
data class LocateResult(
    val centerX: Int = 0,
    val centerY: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val tagName: String = "",
    val role: String = "",
    val text: String = "",
    val isVisible: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val selector: String = ""
)

enum class SelectorType { CSS, XPATH, TEXT, ROLE, LABEL, PLACEHOLDER, ALT_TEXT, TITLE, TEST_ID }

enum class LoadingState { IDLE, LOADING, FINISHED, ERROR }

enum class KeyboardKey {
    ENTER, TAB, ESCAPE, BACKSPACE, DELETE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    SHIFT, CONTROL, ALT, META, SPACE,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    A, C, V, X, S, Z
}

object BiDiKeyMap {
    private val map = mapOf(
        KeyboardKey.ENTER to "\uE007",
        KeyboardKey.TAB to "\uE004",
        KeyboardKey.ESCAPE to "\uE00C",
        KeyboardKey.BACKSPACE to "\uE003",
        KeyboardKey.DELETE to "\uE017",
        KeyboardKey.ARROW_UP to "\uE013",
        KeyboardKey.ARROW_DOWN to "\uE015",
        KeyboardKey.ARROW_LEFT to "\uE012",
        KeyboardKey.ARROW_RIGHT to "\uE014",
        KeyboardKey.SHIFT to "\uE008",
        KeyboardKey.CONTROL to "\uE009",
        KeyboardKey.ALT to "\uE00A",
        KeyboardKey.META to "\uE03D",
        KeyboardKey.SPACE to "\uE00D",
        KeyboardKey.HOME to "\uE011",
        KeyboardKey.END to "\uE010",
        KeyboardKey.PAGE_UP to "\uE00E",
        KeyboardKey.PAGE_DOWN to "\uE00F",
        KeyboardKey.INSERT to "\uE016",
        KeyboardKey.F1 to "\uE031", KeyboardKey.F2 to "\uE032", KeyboardKey.F3 to "\uE033",
        KeyboardKey.F4 to "\uE034", KeyboardKey.F5 to "\uE035", KeyboardKey.F6 to "\uE036",
        KeyboardKey.F7 to "\uE037", KeyboardKey.F8 to "\uE038", KeyboardKey.F9 to "\uE039",
        KeyboardKey.F10 to "\uE03A", KeyboardKey.F11 to "\uE03B", KeyboardKey.F12 to "\uE03C",
        KeyboardKey.A to "a", KeyboardKey.C to "c", KeyboardKey.V to "v",
        KeyboardKey.X to "x", KeyboardKey.S to "s", KeyboardKey.Z to "z"
    )

    fun toBiDi(key: KeyboardKey): String = map[key] ?: key.name.lowercase()
}

fun KeyboardKey.toBiDiCode(): String = BiDiKeyMap.toBiDi(this)
