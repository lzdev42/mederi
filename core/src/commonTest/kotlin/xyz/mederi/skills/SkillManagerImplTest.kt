package xyz.mederi.skills

import xyz.mederi.api.exception.MederiValidationException
import xyz.mederi.store.InMemorySettingsStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SkillManagerImpl 文件系统逻辑测试（不触网：downloader 注入 fake）。
 */
class SkillManagerImplTest {

    private fun tempRoot(): File {
        val dir = File.createTempFile("mederi-skills-test", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun managerFor(root: File, downloader: suspend (String) -> ByteArray = { error("不应下载") }): SkillManagerImpl {
        return SkillManagerImpl(
            settingsStore = InMemorySettingsStore(),
            defaultSkillsRoot = root.absolutePath,
            downloader = downloader,
        )
    }

    private fun writeSkill(root: File, name: String, description: String = "desc of $name") {
        val dir = File(root, name)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(
            "---\nname: $name\ndescription: $description\n---\n\n# $name\n"
        )
    }

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** 写一个使用 YAML 折叠块（description: >）的 SKILL.md。 */
    private fun writeSkillFolded(root: File, name: String, vararg lines: String) {
        val dir = File(root, name)
        dir.mkdirs()
        val body = lines.joinToString("\n") { "  $it" }
        File(dir, "SKILL.md").writeText("---\nname: $name\ndescription: >\n$body\n---\n\n# $name\n")
    }

    /** 写一个 description 为空值 + 缩进多行（Koog 会整体忽略）的 SKILL.md。 */
    private fun writeSkillEmptyValueBlock(root: File, name: String, vararg lines: String) {
        val dir = File(root, name)
        dir.mkdirs()
        val body = lines.joinToString("\n") { "  $it" }
        File(dir, "SKILL.md").writeText("---\nname: $name\ndescription:\n$body\n---\n\n# $name\n")
    }

    @Test
    fun listOnEmptyRootReturnsEmpty() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val manager = managerFor(root)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun listDiscoversSkillFromSkillMd() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        writeSkill(root, "csv-analysis")
        val manager = managerFor(root)

        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("csv-analysis", skills[0].name)
        assertEquals("desc of csv-analysis", skills[0].description)
        assertTrue(skills[0].location.endsWith("csv-analysis/SKILL.md"))
    }

    @Test
    fun listSkipsDirectoryWithoutSkillMd() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        File(root, "not-a-skill").mkdirs()
        val manager = managerFor(root)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun setRootDirectoryPersistsAndCreatesDir() = kotlinx.coroutines.runBlocking {
        val manager = managerFor(tempRoot())
        val newRoot = File(tempRoot().parentFile, "custom-skills-${System.nanoTime()}")

        manager.setRootDirectory(newRoot.absolutePath)
        assertEquals(newRoot.absolutePath, manager.getRootDirectory())
        assertTrue(newRoot.exists())
    }

    @Test
    fun uninstallDeletesSkillDirectory() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        writeSkill(root, "greeting")
        val manager = managerFor(root)

        assertEquals(1, manager.list().size)
        manager.uninstall("greeting")
        assertTrue(manager.list().isEmpty())
        assertTrue(!File(root, "greeting").exists())
    }

    @Test
    fun uninstallUnknownSkillThrows() {
        kotlinx.coroutines.runBlocking {
            val manager = managerFor(tempRoot())
            assertFailsWith<xyz.mederi.api.exception.MederiNotFoundException> {
                manager.uninstall("nope")
            }
        }
    }

    @Test
    fun installDownloadsUnzipsAndMovesIntoRoot() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val zip = zipOf(
            mapOf(
                "csv-analysis/SKILL.md" to "---\nname: csv-analysis\ndescription: Parse CSV files\n---\n\n# CSV\n",
                "csv-analysis/script.py" to "print('hello')",
            )
        )
        val manager = managerFor(root, downloader = { zip })

        val installed = manager.install("https://example.com/skill.zip")
        assertEquals("csv-analysis", installed.name)

        // zip 内的 SKILL.md 与脚本都落在根目录
        assertTrue(File(root, "csv-analysis/SKILL.md").exists())
        assertTrue(File(root, "csv-analysis/script.py").exists())

        // 重新扫描可发现
        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("csv-analysis", skills[0].name)
    }

    @Test
    fun installReinstallingSameSkillOverwrites() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        writeSkill(root, "csv-analysis", "old desc")
        val zip = zipOf(
            mapOf(
                "csv-analysis/SKILL.md" to "---\nname: csv-analysis\ndescription: new desc\n---\n\n# CSV\n",
            )
        )
        val manager = managerFor(root, downloader = { zip })

        manager.install("https://example.com/skill.zip")
        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("new desc", skills[0].description)
    }

    @Test
    fun installWithoutSkillMdThrowsValidation() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val zip = zipOf(mapOf("random/file.txt" to "hello"))
        val manager = managerFor(root, downloader = { zip })

        assertFailsWith<MederiValidationException> {
            manager.install("https://example.com/not-skill.zip")
        }
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun installRejectsNonHttpUrl() {
        kotlinx.coroutines.runBlocking {
            val manager = managerFor(tempRoot())
            assertFailsWith<MederiValidationException> {
                manager.install("file:///tmp/skill.zip")
            }
        }
    }

    @Test
    fun listParsesFoldedBlockDescription() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        writeSkillFolded(root, "compose-skill", "Jetpack Compose skill.", "Only use when asked.")
        val manager = managerFor(root)
        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("compose-skill", skills[0].name)
        assertEquals("Jetpack Compose skill. Only use when asked.", skills[0].description)
    }

    @Test
    fun listFindsSkillWithEmptyValueBlockDescription() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        writeSkillEmptyValueBlock(root, "csv-analysis", "Parse CSV files.", "Handle edge cases.")
        val manager = managerFor(root)
        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("Parse CSV files. Handle edge cases.", skills[0].description)
    }

    @Test
    fun installWithFoldedBlockWorks() = kotlinx.coroutines.runBlocking {
        val root = tempRoot()
        val zip = zipOf(
            mapOf(
                "compose-skill/SKILL.md" to "---\nname: compose-skill\ndescription: >\n  Jetpack Compose skill.\n  Only use when asked.\n---\n\n# Compose\n",
            )
        )
        val manager = managerFor(root, downloader = { zip })
        val installed = manager.install("https://example.com/skill.zip")
        assertEquals("compose-skill", installed.name)
        assertEquals("Jetpack Compose skill. Only use when asked.", installed.description)
        val skills = manager.list()
        assertEquals(1, skills.size)
        assertEquals("Jetpack Compose skill. Only use when asked.", skills[0].description)
    }
}
