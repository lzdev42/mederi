package xyz.mederi.browser.obscura

import java.io.File

object ObscuraBinaryLocator {
    private val windows = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
    private val exeName = if (windows) "obscura.exe" else "obscura"

    fun resolve(): String? = resolve(
        appDir = System.getProperty("compose.application.dir")?.let(::File),
        jarLocation = currentJarLocation(),
        workingDir = File(System.getProperty("user.dir")),
        pathLookup = ::obscuraOnPath
    )

    internal fun resolve(appDir: File?, jarLocation: File?, workingDir: File?, pathLookup: () -> Boolean): String? {
        val roots = buildList<File> {
            appDir?.let(::add)
            jarLocation?.parentFile?.let(::add)
            workingDir?.let(::add)
        }
        for (root in roots) {
            candidates(root).firstOrNull { it.isFile && it.canExecute() }?.let { return it.canonicalPath }
        }
        return if (pathLookup()) exeName else null
    }

    internal fun candidates(root: File): List<File> = listOf(
        root.resolve("../Resources/$exeName"),  // macOS .app
        root.resolve("../resources/$exeName"),   // 桌面 bin/、服务端 lib/
        root.resolve("resources/$exeName")       // user.dir = 安装根
    )

    private fun currentJarLocation(): File? = runCatching {
        File(ObscuraBinaryLocator::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    private fun obscuraOnPath(): Boolean =
        System.getenv("PATH")?.split(File.pathSeparator)?.any { dir ->
            File(dir, exeName).let { it.isFile && it.canExecute() }
        } == true
}
