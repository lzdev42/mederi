package xyz.mederi.browser

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CamoufoxSettingsMappingTest {

    @Test
    fun `resolveProfileDir with browserHome ends with profiles under browserHome`() {
        val home = Files.createTempDirectory("mederi-camoufox-home-").toString()

        val dir: Path? = CamoufoxSettings(browserHome = home).resolveProfileDir()

        assertNotNull(dir)
        assertEquals("profiles", dir.fileName.toString())
        assertEquals(home, dir.parent.toString())
    }

    @Test
    fun `resolveProfileDir with only binaryPath ends with profiles next to binary parent`() {
        val parent = Files.createTempDirectory("mederi-camoufox-bin-")
        val binary = File(parent.toFile(), "camoufox").absolutePath

        val dir: Path? = CamoufoxSettings(binaryPath = binary).resolveProfileDir()

        assertNotNull(dir)
        assertEquals("profiles", dir.fileName.toString())
        assertEquals(parent.toString(), dir.parent.toString())
    }

    @Test
    fun `resolveProfileDir returns null when both paths empty`() {
        assertNull(CamoufoxSettings().resolveProfileDir())
        assertNull(CamoufoxSettings(browserHome = " ", binaryPath = "").resolveProfileDir())
    }
}