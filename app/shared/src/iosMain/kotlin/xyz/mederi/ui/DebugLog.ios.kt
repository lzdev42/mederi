package xyz.mederi.ui

actual fun printError(line: String, throwable: Throwable?) {
    // iOS：stdout 即可（Xcode console / oslog 桥接由宿主决定）
    println(line)
    throwable?.let { println(it.toString()) }
}

actual fun printLine(line: String) {
    println(line)
}
