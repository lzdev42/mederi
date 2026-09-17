package xyz.emuci.markdown.renderer.internal.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun parseDimensionDp(raw: String?): Dp? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim().lowercase()
    val num = trimmed.toDoubleOrNull()
    if (num != null && num > 0) return num.toFloat().dp
    if (trimmed.endsWith("dp") || trimmed.endsWith("px")) {
        val numPart = trimmed.substring(0, trimmed.length - 2).trim().toDoubleOrNull()
        if (numPart != null && numPart > 0) return numPart.toFloat().dp
    }
    return null
}

internal fun parseFontSizeSp(raw: String?): TextUnit? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim().lowercase()
    val num = trimmed.toDoubleOrNull()
    if (num != null && num > 0) return num.toFloat().sp
    if (trimmed.endsWith("sp") || trimmed.endsWith("px") || trimmed.endsWith("pt")) {
        val numPart = trimmed.substring(0, trimmed.length - 2).trim().toDoubleOrNull()
        if (numPart != null && numPart > 0) return numPart.toFloat().sp
    }
    return null
}
