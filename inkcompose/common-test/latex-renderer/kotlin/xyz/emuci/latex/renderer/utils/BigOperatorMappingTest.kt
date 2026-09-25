package xyz.emuci.latex.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class BigOperatorMappingTest {
    @Test
    fun mapsEveryRegisteredLargeSymbolOperator() {
        val expected = mapOf(
            "sum" to "∑",
            "prod" to "∏",
            "coprod" to "∐",
            "int" to "∫",
            "oint" to "∮",
            "iint" to "∬",
            "iiint" to "∭",
            "bigcap" to "⋂",
            "bigcup" to "⋃",
            "bigsqcup" to "⨆",
            "bigvee" to "⋁",
            "bigwedge" to "⋀",
            "bigoplus" to "⨁",
            "bigotimes" to "⨂",
            "bigodot" to "⨀",
            "biguplus" to "⨄"
        )

        expected.forEach { (command, glyph) ->
            assertEquals(glyph, mapBigOp(command), "\\$command should use its large glyph")
        }
    }
}
