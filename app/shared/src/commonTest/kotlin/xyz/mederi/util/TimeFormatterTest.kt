package xyz.mederi.util

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatterTest {

    private val utcZone = TimeZone.of("UTC")
    private val shanghaiZone = TimeZone.of("Asia/Shanghai") // UTC+8
    private val newYorkZone = TimeZone.of("America/New_York") // 2026-10-02 是 EDT (UTC-4)

    // 2026-10-02 04:15:30 UTC
    // 在上海（UTC+8）对应 2026-10-02 12:15:30
    // 在纽约（UTC-4）对应 2026-10-02 00:15:30
    private val sampleUtcIso = "2026-10-02T04:15:30Z"
    private val sampleEpochMillis = 1790914530000L // 2026-10-02T04:15:30Z

    @Test
    fun testFormatShortTimeWithTimeZone() {
        // Long millis
        assertEquals("04:15", TimeFormatter.formatShortTime(sampleEpochMillis, utcZone))
        assertEquals("12:15", TimeFormatter.formatShortTime(sampleEpochMillis, shanghaiZone))
        assertEquals("00:15", TimeFormatter.formatShortTime(sampleEpochMillis, newYorkZone))
        assertEquals("", TimeFormatter.formatShortTime(0L, shanghaiZone))
        assertEquals("", TimeFormatter.formatShortTime(-100L, shanghaiZone))

        // ISO String
        assertEquals("04:15", TimeFormatter.formatShortTime(sampleUtcIso, utcZone))
        assertEquals("12:15", TimeFormatter.formatShortTime(sampleUtcIso, shanghaiZone))
        assertEquals("00:15", TimeFormatter.formatShortTime(sampleUtcIso, newYorkZone))
        assertEquals("", TimeFormatter.formatShortTime("", shanghaiZone))
        assertEquals("", TimeFormatter.formatShortTime("   ", shanghaiZone))
    }

    @Test
    fun testFormatTimeWithSeconds() {
        // Long millis
        assertEquals("04:15:30", TimeFormatter.formatTimeWithSeconds(sampleEpochMillis, utcZone))
        assertEquals("12:15:30", TimeFormatter.formatTimeWithSeconds(sampleEpochMillis, shanghaiZone))
        assertEquals("00:15:30", TimeFormatter.formatTimeWithSeconds(sampleEpochMillis, newYorkZone))

        // ISO String
        assertEquals("04:15:30", TimeFormatter.formatTimeWithSeconds(sampleUtcIso, utcZone))
        assertEquals("12:15:30", TimeFormatter.formatTimeWithSeconds(sampleUtcIso, shanghaiZone))
        assertEquals("00:15:30", TimeFormatter.formatTimeWithSeconds(sampleUtcIso, newYorkZone))

        // ISO with millis & nanos
        assertEquals("12:15:30", TimeFormatter.formatTimeWithSeconds("2026-10-02T04:15:30.123Z", shanghaiZone))
        assertEquals("12:15:30", TimeFormatter.formatTimeWithSeconds("2026-10-02T04:15:30.123456Z", shanghaiZone))
    }

    @Test
    fun testFormatMonthDayTime() {
        // 跨日测试：纽约是 10/2 00:15，上海是 10/2 12:15
        assertEquals("10/2 04:15", TimeFormatter.formatMonthDayTime(sampleEpochMillis, utcZone))
        assertEquals("10/2 12:15", TimeFormatter.formatMonthDayTime(sampleEpochMillis, shanghaiZone))
        assertEquals("10/2 00:15", TimeFormatter.formatMonthDayTime(sampleEpochMillis, newYorkZone))

        // 字符串输入，包含空格分隔时间
        assertEquals("10/2 12:15", TimeFormatter.formatMonthDayTime(sampleUtcIso, shanghaiZone))
        assertEquals("9/20 16:12", TimeFormatter.formatMonthDayTime("2026-09-20 16:12:28", utcZone))

        // 容错降级
        assertEquals("invalid-time-str", TimeFormatter.formatMonthDayTime("invalid-time-str", shanghaiZone))
    }

    @Test
    fun testFormatFullDateTime() {
        assertEquals("2026-10-02 04:15:30", TimeFormatter.formatFullDateTime(sampleEpochMillis, utcZone))
        assertEquals("2026-10-02 12:15:30", TimeFormatter.formatFullDateTime(sampleEpochMillis, shanghaiZone))
        assertEquals("2026-10-02 00:15:30", TimeFormatter.formatFullDateTime(sampleEpochMillis, newYorkZone))

        assertEquals("2026-10-02 12:15:30", TimeFormatter.formatFullDateTime(sampleUtcIso, shanghaiZone))
        assertEquals("2026-10-02 12:15:30", TimeFormatter.formatFullDateTime("2026-10-02T04:15:30.456Z", shanghaiZone))

        // 容错降级
        assertEquals("invalid-string", TimeFormatter.formatFullDateTime("invalid-string", shanghaiZone))
    }
}
