package xyz.mederi.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * token 短格式：大数量切 k/m/b 单位、两位小数、进位边界收敛；无数据（null）的显示由调用方处理。
 */
class FormatTokenShortTest {
    @Test
    fun smallCountsStayRaw() {
        assertEquals("0", formatTokenShort(0))
        assertEquals("42", formatTokenShort(42))
        assertEquals("999", formatTokenShort(999))
    }

    @Test
    fun thousandsUseTwoDecimalK() {
        assertEquals("1.00k", formatTokenShort(1_000))
        assertEquals("1.53k", formatTokenShort(1_530))
        assertEquals("42.85k", formatTokenShort(42_850))
        assertEquals("100.00k", formatTokenShort(100_000))
    }

    @Test
    fun millionsUseTwoDecimalM() {
        assertEquals("4.25m", formatTokenShort(4_250_000))
        assertEquals("1.23m", formatTokenShort(1_234_567))
        // 999_999 四舍五入到 1000.00k，收敛为 1.00m
        assertEquals("1.00m", formatTokenShort(999_999))
        // 已在 m 单位内四舍五入但不进位，保持 999.99m
        assertEquals("999.99m", formatTokenShort(999_994_000))
    }

    @Test
    fun billionsUseTwoDecimalB() {
        assertEquals("1.50b", formatTokenShort(1_500_000_000))
        assertEquals("2.30b", formatTokenShort(2_300_000_000))
    }
}
