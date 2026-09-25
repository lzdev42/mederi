package xyz.mederi

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun formatMessageTime(epochMillis: Long): String {
    val seconds = if (epochMillis <= 0) NSDate().timeIntervalSince1970 else epochMillis / 1000.0
    val date = NSDate.dateWithTimeIntervalSince1970(seconds)
    val formatter = NSDateFormatter().apply {
        dateFormat = "HH:mm"
    }
    return formatter.stringFromDate(date)
}
