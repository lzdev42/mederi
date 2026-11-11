package xyz.mederi.browser

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mederi.browser.obscura.ObscuraBinaryLocator

class ObscuraBinaryLocatorTest {

    private val tempDirs = mutableListOf<File>()
    private val windows = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
    private val exeName = if (windows) "obscura.exe" else "obscura"

    private fun freshDir(): File {
        val dir = java.nio.file.Files.createTempDirectory("obscura-locator-").toFile()
        tempDirs.add(dir)
        return dir
    }

    @BeforeTest
    fun before() {
        File("build/obscura-locator").mkdirs()
    }

    @AfterTest
    fun after() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun writeObservation(name: String, value: String) {
        File("build/obscura-locator").mkdirs()
        File("build/obscura-locator/$name.txt").writeText(value)
    }

    private fun lastTwo(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return parts.takeLast(2).joinToString("/")
    }

    private fun executableFile(parent: File, name: String): File {
        parent.mkdirs()
        val file = File(parent, name)
        file.createNewFile()
        file.setExecutable(true, false)
        return file
    }

    @Test
    fun `macos bundle layout resolves Resources obscura`() {
        val root = freshDir()
        val macOsDir = File(root, "App.app/Contents/MacOS")
        macOsDir.mkdirs()
        executableFile(File(root, "App.app/Contents/Resources"), exeName)
        val empty = freshDir()

        val result = ObscuraBinaryLocator.resolve(macOsDir, null, empty) { false }
        val observed = result?.let(::lastTwo) ?: "null"
        writeObservation("macos", if (observed == "Resources/$exeName") observed else "wrong:$observed")

        // macOS expectation is Resources/obscura (exeName is "obscura" on macOS)
        val expectedSuffix = "Resources/$exeName"
        writeObservation("macos", if (observed == expectedSuffix) observed else "wrong:$observed")
        // On non-Windows expected is Resources/obscura; keep assertion strict on suffix
        assertEquals(expectedSuffix, observed)
    }

    @Test
    fun `server and desktop layout resolves resources obscura`() {
        val root = freshDir()
        File(root, "bin").mkdirs()
        File(root, "lib").mkdirs()
        executableFile(File(root, "resources"), exeName)
        val empty = freshDir()

        val result = ObscuraBinaryLocator.resolve(null, File(root, "lib/server.jar"), empty) { false }
        val observed = result?.let(::lastTwo) ?: "null"
        writeObservation("server", if (observed == "resources/$exeName") observed else "wrong:$observed")
        assertEquals("resources/$exeName", observed)
    }

    @Test
    fun `working dir at install root resolves resources obscura`() {
        val root = freshDir()
        executableFile(File(root, "resources"), exeName)
        val empty = freshDir()

        // workingDir itself holds resources/obscura (install root)
        val result = ObscuraBinaryLocator.resolve(null, null, root) { false }
        val observed = result?.let(::lastTwo) ?: "null"
        // merge into server output per spec (not required to be separate file)
        writeObservation("server", if (observed == "resources/$exeName") observed else "wrong:$observed")
        assertEquals("resources/$exeName", observed)
    }

    @Test
    fun `absent everywhere returns null honestly`() {
        val empty1 = freshDir()
        val empty2 = freshDir()
        val empty3 = freshDir()

        val result = ObscuraBinaryLocator.resolve(empty1, File(empty2, "x.jar"), empty3) { false }
        writeObservation("absent", result?.toString() ?: "null")
        assertNull(result)
    }

    @Test
    fun `path fallback returns command name`() {
        val empty1 = freshDir()
        val empty2 = freshDir()
        val empty3 = freshDir()

        val result = ObscuraBinaryLocator.resolve(empty1, File(empty2, "x.jar"), empty3) { true }
        writeObservation("path", result ?: "null")
        assertEquals(exeName, result)
    }
}
