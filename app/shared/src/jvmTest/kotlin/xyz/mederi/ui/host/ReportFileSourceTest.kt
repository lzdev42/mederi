package xyz.mederi.ui.host

import java.io.File
import java.util.UUID
import kotlin.test.*

class ReportFileSourceTest {
    @Test fun typedReadPreservesTextAndDistinguishesMissingFromIoFailure() {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val fixture = File(repo, "temp/report-source-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val raw = "  报告\n\n"
            File(fixture, "report.md").writeText(raw)
            File(fixture, "directory").mkdir()
            val source = ProjectFileTreeProviderJvm(fixture)
            assertEquals(raw, source.readTextResult("report.md").getOrThrow())
            assertNull(source.readTextResult("missing.md").getOrThrow())
            assertTrue(source.readTextResult("directory").isFailure)
            assertEquals(raw, source.readText("report.md")); assertNull(source.readText("directory"))
        } finally { fixture.deleteRecursively() }
    }
}
