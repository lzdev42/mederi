package xyz.mederi.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PlatformUtils 新增的两个宿主 OS I/O 方法的 smoke 测试。
 *
 * **只锁「不崩」这条最小契约**——不锁具体返回值：查询默认应用名与揭示文件夹都依赖宿主 OS 命令
 * （xdg-mime / assoc+ftype / open -R / explorer），在 headless / 沙箱 / 不同平台下结果都不可预测，
 * 断言具体值只会变成 flaky 测试。真正的行为保证是 actual 内部的 runCatching 兜底。
 */
class PlatformUtilsSmokeTest {

    private fun tempTxtFile(): String =
        File(System.getProperty("java.io.tmpdir"), "mederi-platformutils-smoke-${System.nanoTime()}.txt").path

    @Test
    fun defaultAppNameFor_doesNotThrow() {
        val path = tempTxtFile()
        val result = runCatching { defaultAppNameFor(path) }
        assertTrue(result.isSuccess, "defaultAppNameFor 不应抛异常：${result.exceptionOrNull()}")
        // 返回值 null / 非 null 都接受（平台能力差异，调用方据此降级）
    }

    @Test
    fun defaultAppNameFor_unknownExtension_returnsNullOrName_withoutThrowing() {
        val result = runCatching { defaultAppNameFor("/nonexistent-file-xyz.unknownext") }
        assertTrue(result.isSuccess, "defaultAppNameFor 对不存在的路径也不应抛异常：${result.exceptionOrNull()}")
    }

    @Test
    fun revealInFolder_nonexistentPath_doesNotThrow() {
        // 用不存在的路径触发失败分支，避免测试真的弹出 Finder / 资源管理器窗口
        val result = runCatching { revealInFolder("/nonexistent-file-xyz") }
        assertTrue(result.isSuccess, "revealInFolder 不应抛异常：${result.exceptionOrNull()}")
        // macOS: open -R 对不存在的路径返回非零 → false。
        // Windows: explorer 按实现约定「启动即 true」不判 exit code，故不对其断言具体值。
        if (System.getProperty("os.name", "").lowercase().contains("mac")) {
            assertEquals(false, result.getOrNull())
        }
    }
}