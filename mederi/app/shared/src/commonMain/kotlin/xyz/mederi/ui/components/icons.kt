package xyz.mederi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 极简大脑矢量图标（用于思维链/深度思考展示）
 */
val BrainIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Brain",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val nodes = PathParser().parsePathString(
            "M12 18V5 " +
            "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4 " +
            "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5 " +
            "M17.997 5.125a4 4 0 0 1 2.526 5.77 " +
            "M18 18a4 4 0 0 0 2-7.464 " +
            "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517 " +
            "M6 18a4 4 0 0 1-2-7.464 " +
            "M6.003 5.125a4 4 0 0 0-2.526 5.77"
        ).toNodes()
        addPath(
            pathData = nodes,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()
}

/**
 * 极简终端命令提示符矢量图标（>_）
 */
val TerminalPromptIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TerminalPrompt",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        // > 提示符折线
        val nodesChevron = PathParser().parsePathString("M 2.5 4 L 6.5 7.5 L 2.5 11").toNodes()
        addPath(
            pathData = nodesChevron,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        // _ 下划线
        val nodesUnderscore = PathParser().parsePathString("M 8 11.5 L 13 11.5").toNodes()
        addPath(
            pathData = nodesUnderscore,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}
