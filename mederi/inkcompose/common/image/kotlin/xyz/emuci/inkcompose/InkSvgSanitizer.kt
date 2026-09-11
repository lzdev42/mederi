package xyz.emuci.inkcompose

/**
 * Skia SVGDOM 的已知缺陷修复：
 * SkSVGEllipse/SkSVGCircle 未能正确计算带变换或默认 objectBoundingBox 的渐变边界，
 * 导致使用 fill="url(#...)" 的渐变计算分母为 0 失败，退化为默认纯黑。
 *
 * 在交给 Skia 之前，将引用了 url(...) 的 <ellipse> 和 <circle> 转换为等价的 <path>，
 * 借助 SkSVGPath 完整的 objectBoundingBox 实现，使渐变正常渲染。
 */
internal fun sanitizeSvgForSkia(rawSvg: String): String {
    if (!rawSvg.contains("url(")) return rawSvg

    var result = rawSvg

    // 处理带 url 渐变的 <ellipse>
    val ellipseRegex = Regex(
        """<ellipse\b([^>]*\burl\([^)]+\)[^>]*)(?:/>|>(?:[\s\S]*?)</ellipse>)""",
        RegexOption.IGNORE_CASE
    )
    result = ellipseRegex.replace(result) { match ->
        val attrs = match.groupValues[1]
        val cx = Regex("""\bcx\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
        val cy = Regex("""\bcy\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
        val rx = Regex("""\brx\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
        val ry = Regex("""\bry\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()

        if (cx != null && cy != null && rx != null && ry != null) {
            val d = "M ${cx - rx} $cy A $rx $ry 0 1 0 ${cx + rx} $cy A $rx $ry 0 1 0 ${cx - rx} $cy Z"
            val cleanAttrs = attrs
                .replace(Regex("""\b(cx|cy|rx|ry)\s*=\s*["'][^"']*["']"""), "")
                .trim()
            val replaced = "<path d=\"$d\" $cleanAttrs/>"
            println("[InkSvgSanitizer] Log: Converted <ellipse> to <path> for gradient support: cx=$cx, cy=$cy, rx=$rx, ry=$ry")
            replaced
        } else {
            match.value
        }
    }

    // 处理带 url 渐变的 <circle>
    val circleRegex = Regex(
        """<circle\b([^>]*\burl\([^)]+\)[^>]*)(?:/>|>(?:[\s\S]*?)</circle>)""",
        RegexOption.IGNORE_CASE
    )
    result = circleRegex.replace(result) { match ->
        val attrs = match.groupValues[1]
        val cx = Regex("""\bcx\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
        val cy = Regex("""\bcy\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()
        val r = Regex("""\br\s*=\s*["']([^"a-zA-Z\s]+)""").find(attrs)?.groupValues?.get(1)?.toFloatOrNull()

        if (cx != null && cy != null && r != null) {
            val d = "M ${cx - r} $cy A $r $r 0 1 0 ${cx + r} $cy A $r $r 0 1 0 ${cx - r} $cy Z"
            val cleanAttrs = attrs
                .replace(Regex("""\b(cx|cy|r)\s*=\s*["'][^"']*["']"""), "")
                .trim()
            val replaced = "<path d=\"$d\" $cleanAttrs/>"
            println("[InkSvgSanitizer] Log: Converted <circle> to <path> for gradient support: cx=$cx, cy=$cy, r=$r")
            replaced
        } else {
            match.value
        }
    }

    return result
}
