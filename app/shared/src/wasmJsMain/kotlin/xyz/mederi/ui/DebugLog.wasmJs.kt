package xyz.mederi.ui

actual fun printError(line: String, throwable: Throwable?) {
    // wasmJs：println 直接映射到 console.log
    println(line)
    throwable?.let { println(it.toString()) }
}

actual fun printLine(line: String) {
    println(line)
}
