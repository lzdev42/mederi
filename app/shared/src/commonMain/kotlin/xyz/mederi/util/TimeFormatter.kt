package xyz.mederi.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 统一时间格式化工具类（KMP 纯 Kotlin 跨平台实现）。
 *
 * 核心原则：
 * 1. 唯一真理源：所有面向用户展示时间的地方统一由本工具处理，杜绝各组件自写截串/格式化逻辑；
 * 2. 本地时区保证：默认强制转换为用户系统所属时区（[TimeZone.currentSystemDefault]）展示；
 * 3. 健壮容错：支持 Epoch 毫秒、标准 ISO-8601、空格分隔时间串等，解析失败安全降级不崩溃。
 */
object TimeFormatter {

    /**
     * 格式化为短时刻 "HH:mm"（如 "14:25"）。
     * 适用于：消息气泡时间戳、长文产物卡片、助手回复完成时刻等。
     */
    fun formatShortTime(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = toLocalDateTime(epochMillis, timeZone) ?: return ""
        return "${dt.hour.pad2()}:${dt.minute.pad2()}"
    }

    /**
     * 格式化为短时刻 "HH:mm"（如 "14:25"）。
     * 支持 ISO-8601 字符串、空格分隔时间或纯数字毫秒字符串。
     */
    fun formatShortTime(
        isoOrDateString: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = parseToLocalDateTime(isoOrDateString, timeZone) ?: return ""
        return "${dt.hour.pad2()}:${dt.minute.pad2()}"
    }

    /**
     * 格式化为带秒时刻 "HH:mm:ss"（如 "14:25:30"）。
     * 适用于：子代理运行标签、紧凑任务卡片等需要精确到秒的场景。
     */
    fun formatTimeWithSeconds(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = toLocalDateTime(epochMillis, timeZone) ?: return ""
        return "${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
    }

    /**
     * 格式化为带秒时刻 "HH:mm:ss"（如 "14:25:30"）。
     * 支持 ISO-8601 字符串、空格分隔时间或纯数字毫秒字符串。
     */
    fun formatTimeWithSeconds(
        isoOrDateString: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = parseToLocalDateTime(isoOrDateString, timeZone) ?: return ""
        return "${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
    }

    /**
     * 格式化为月日短时间 "M/d HH:mm"（如 "9/20 16:12"）。
     * 适用于：Raw 原始消息卡片时间戳等。
     */
    fun formatMonthDayTime(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = toLocalDateTime(epochMillis, timeZone) ?: return ""
        val month = dt.month.ordinal + 1
        return "$month/${dt.dayOfMonth} ${dt.hour.pad2()}:${dt.minute.pad2()}"
    }

    /**
     * 格式化为月日短时间 "M/d HH:mm"（如 "9/20 16:12"）。
     * 支持 ISO-8601 字符串、空格分隔时间或纯数字毫秒字符串。若解析失败安全降级为原串前 16 位。
     */
    fun formatMonthDayTime(
        isoOrDateString: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        if (isoOrDateString.isBlank()) return ""
        val dt = parseToLocalDateTime(isoOrDateString, timeZone)
        return if (dt != null) {
            val month = dt.month.ordinal + 1
            "$month/${dt.dayOfMonth} ${dt.hour.pad2()}:${dt.minute.pad2()}"
        } else {
            isoOrDateString.take(16)
        }
    }

    /**
     * 格式化为完整日期时间 "yyyy-MM-dd HH:mm:ss"（如 "2026-10-02 14:25:30"）。
     * 适用于：子代理详情面板启动时刻、诊断日志详细时间等。
     */
    fun formatFullDateTime(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val dt = toLocalDateTime(epochMillis, timeZone) ?: return ""
        val month = dt.month.ordinal + 1
        return "${dt.year}-${month.pad2()}-${dt.dayOfMonth.pad2()} ${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
    }

    /**
     * 格式化为完整日期时间 "yyyy-MM-dd HH:mm:ss"（如 "2026-10-02 14:25:30"）。
     * 支持 ISO-8601 字符串、空格分隔时间或纯数字毫秒字符串。若解析失败安全降级为原串。
     */
    fun formatFullDateTime(
        isoOrDateString: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        if (isoOrDateString.isBlank()) return ""
        val dt = parseToLocalDateTime(isoOrDateString, timeZone)
        return if (dt != null) {
            val month = dt.month.ordinal + 1
            "${dt.year}-${month.pad2()}-${dt.dayOfMonth.pad2()} ${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
        } else {
            isoOrDateString
        }
    }

    /**
     * 解析时间字符串为指定时区（默认本地时区）的 [LocalDateTime]。
     */
    fun parseToLocalDateTime(
        input: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): LocalDateTime? {
        if (input.isBlank()) return null

        // 1. 尝试纯数字（epoch millis 字符串）
        input.toLongOrNull()?.let { millis ->
            return toLocalDateTime(millis, timeZone)
        }

        val trimmed = input.trim()

        // 2. 规范化：替换空格为 'T'，支持如 "2026-09-20 16:12:28"
        val normalized = if ('T' in trimmed) trimmed else trimmed.replace(' ', 'T')

        // 3. 尝试解析带时区的标准 Instant（如 "2026-10-02T04:00:00Z" 或带 "+08:00"）
        try {
            return Instant.parse(normalized).toLocalDateTime(timeZone)
        } catch (_: Throwable) {
            // 继续后续尝试
        }

        // 4. 若无时区后缀，尝试当作 UTC 补 'Z'
        if (!normalized.endsWith("Z") && !normalized.contains("+") && !hasOffsetMinus(normalized)) {
            try {
                return Instant.parse("${normalized}Z").toLocalDateTime(timeZone)
            } catch (_: Throwable) {
                // 继续后续尝试
            }
        }

        // 5. 尝试直接作为本地 LocalDateTime 解析（如 "2026-10-02T12:00:00"）
        try {
            return LocalDateTime.parse(normalized)
        } catch (_: Throwable) {
            // 解析失败
        }

        return null
    }

    /**
     * 将 epoch 毫秒转换为指定时区（默认本地时区）的 [LocalDateTime]。
     */
    fun toLocalDateTime(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): LocalDateTime? {
        if (epochMillis <= 0L) return null
        return try {
            Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
        } catch (_: Throwable) {
            null
        }
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')

    /**
     * 检查字符串中是否存在时区偏移减号（位于时间部分之后的 '-'）。
     */
    private fun hasOffsetMinus(s: String): Boolean {
        val tIdx = s.indexOf('T')
        if (tIdx < 0) return false
        val afterT = s.substring(tIdx + 1)
        return '-' in afterT
    }
}
