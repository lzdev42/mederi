package xyz.mederi.ui

actual fun printError(line: String, throwable: Throwable?) {
    System.err.println(line)
    throwable?.printStackTrace(System.err)
}

actual fun printLine(line: String) {
    println(line)
}
