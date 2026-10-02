package xyz.mederi

@JsName("Date")
private external class JsDate {
    companion object {
        fun now(): Double
    }
}

actual fun currentTimeMillis(): Long = JsDate.now().toLong()
