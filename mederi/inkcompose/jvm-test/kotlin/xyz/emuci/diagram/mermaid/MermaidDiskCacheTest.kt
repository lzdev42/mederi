package xyz.emuci.diagram.mermaid

import xyz.emuci.inkcompose.MermaidCacheConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidDiskCacheTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("mederi-mermaid-test").toFile()
        MermaidCacheConfig.setBaseDirectory(tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testDynamicBaseDirectoryConfiguration() {
        val configured = MermaidCacheConfig.getBaseDirectory()
        assertEquals(tempDir.absolutePath, configured)
        assertEquals(File(tempDir, "mermaid").absolutePath, MermaidDiskCache.getMermaidDir().absolutePath)
    }

    @Test
    fun testDirectorySelfHealing() {
        val mermaidDir = MermaidDiskCache.getMermaidDir()
        assertTrue(mermaidDir.exists(), "Directory should exist initially")

        // 模拟外部删除
        val deleted = mermaidDir.delete()
        assertTrue(deleted || !mermaidDir.exists(), "Directory should be deleted")
        assertFalse(mermaidDir.exists())

        // 访问自愈
        val healedDir = MermaidDiskCache.getMermaidDir()
        assertTrue(healedDir.exists(), "Directory must self-heal upon access")
    }

    @Test
    fun testZeroByteFileSelfHealing() {
        val key = "zero_byte_test"
        val file = MermaidDiskCache.getCacheFile(key)
        file.createNewFile()
        assertEquals(0L, file.length())
        assertTrue(file.exists())

        // 读取时检测到 0 字节损坏文件，应自动将其删除并返回 null
        val bitmap = MermaidDiskCache.readValidBitmap(key)
        assertNull(bitmap, "0-byte file should return null bitmap")
        assertFalse(file.exists(), "Corrupted 0-byte file must be deleted during self-healing")
    }

    @Test
    fun testCorruptedFileSelfHealing() {
        val key = "corrupted_file_test"
        val file = MermaidDiskCache.getCacheFile(key)
        file.writeBytes("THIS_IS_NOT_A_VALID_PNG_CONTENT".toByteArray())
        assertTrue(file.length() > 0)
        assertTrue(file.exists())

        // Skia 解码失败后，应自动清除损坏文件并返回 null
        val bitmap = MermaidDiskCache.readValidBitmap(key)
        assertNull(bitmap, "Corrupted file should return null bitmap")
        assertFalse(file.exists(), "Corrupted file must be deleted during self-healing")
    }

    @Test
    fun testAtomicSaveAndReadValidPng() {
        val key = "valid_save_test"
        // 1x1 透明 PNG 标头与块
        val valid1x1Png = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )

        MermaidDiskCache.savePng(key, valid1x1Png)
        val file = MermaidDiskCache.getCacheFile(key)
        assertTrue(file.exists())

        val bitmap = MermaidDiskCache.readValidBitmap(key)
        assertNotNull(bitmap, "Valid PNG must be decoded into ImageBitmap")
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    @Test
    fun testComputeKeyWithSessionKey() {
        val code = "graph TD\nA-->B"
        val keyWithMsg = MermaidDiskCache.computeKey(code, 42, "msg-12345")
        assertTrue(keyWithMsg.startsWith("msg-12345_"), "Key should be prefixed with sanitized session/msg ID, got: $keyWithMsg")

        val keyWithoutMsg = MermaidDiskCache.computeKey(code, 42, null)
        assertFalse(keyWithoutMsg.contains("msg-12345"))
    }

    @Test
    fun testClearSessionCache() {
        val dummyPng = byteArrayOf(1, 2, 3)
        val s1Key1 = MermaidDiskCache.computeKey("graph 1", 1, "session-alpha")
        val s1Key2 = MermaidDiskCache.computeKey("graph 2", 1, "session-alpha")
        val s2Key = MermaidDiskCache.computeKey("graph 3", 1, "session-beta")
        val noSessionKey = MermaidDiskCache.computeKey("graph 4", 1, null)

        MermaidDiskCache.savePng(s1Key1, dummyPng)
        MermaidDiskCache.savePng(s1Key2, dummyPng)
        MermaidDiskCache.savePng(s2Key, dummyPng)
        MermaidDiskCache.savePng(noSessionKey, dummyPng)

        assertTrue(MermaidDiskCache.getCacheFile(s1Key1).exists())
        assertTrue(MermaidDiskCache.getCacheFile(s1Key2).exists())
        assertTrue(MermaidDiskCache.getCacheFile(s2Key).exists())
        assertTrue(MermaidDiskCache.getCacheFile(noSessionKey).exists())

        // 清理 session-alpha
        MermaidCacheConfig.clearSessionCache("session-alpha")

        assertFalse(MermaidDiskCache.getCacheFile(s1Key1).exists(), "session-alpha files should be deleted")
        assertFalse(MermaidDiskCache.getCacheFile(s1Key2).exists(), "session-alpha files should be deleted")
        assertTrue(MermaidDiskCache.getCacheFile(s2Key).exists(), "session-beta files must remain")
        assertTrue(MermaidDiskCache.getCacheFile(noSessionKey).exists(), "non-session files must remain")
    }
}
