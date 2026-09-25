package xyz.mederi

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatMessageTime(epochMillis: Long): String {
    val millis = if (epochMillis <= 0) System.currentTimeMillis() else epochMillis
    val instant = java.time.Instant.ofEpochMilli(millis)
    val local = instant.atZone(java.time.ZoneId.systemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}
