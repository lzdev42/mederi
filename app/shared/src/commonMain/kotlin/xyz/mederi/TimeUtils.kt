package xyz.mederi

import xyz.mederi.util.TimeFormatter

expect fun currentTimeMillis(): Long

/**
 * 格式化时间为短时刻 "HH:mm"（用户系统所属时区）。
 * 统一委托至 [TimeFormatter.formatShortTime]。
 */
fun formatMessageTime(epochMillis: Long): String =
    TimeFormatter.formatShortTime(epochMillis)

/**
 * 格式化 ISO/日期时间字符串为短时刻 "HH:mm"（用户系统所属时区）。
 * 统一委托至 [TimeFormatter.formatShortTime]。
 */
fun formatMessageTime(isoOrDateString: String): String =
    TimeFormatter.formatShortTime(isoOrDateString)
