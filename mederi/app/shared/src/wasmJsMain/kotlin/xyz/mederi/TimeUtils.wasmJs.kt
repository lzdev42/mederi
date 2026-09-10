package xyz.mederi

@JsName("Date")
private external class JsDate(time: Double = definedExternally) {
    fun getHours(): Int
    fun getMinutes(): Int
    companion object {
        fun now(): Double
    }
}

actual fun currentTimeMillis(): Long = JsDate.now().toLong()

actual fun formatMessageTime(epochMillis: Long): String {
    val millis = if (epochMillis <= 0) JsDate.now() else epochMillis.toDouble()
    val date = JsDate(millis)
    val hour = date.getHours().toString().padStart(2, '0')
    val minute = date.getMinutes().toString().padStart(2, '0')
    return "$hour:$minute"
}
