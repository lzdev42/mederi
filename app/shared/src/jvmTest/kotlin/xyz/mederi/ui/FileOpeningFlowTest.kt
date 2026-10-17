package xyz.mederi.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.mederi.core.contract.preferences.InMemoryPreferencesStore
import xyz.mederi.core.mock.MockAiCore
import xyz.mederi.ui.appstate.AppState
import xyz.mederi.ui.host.ProjectFileTreeProviderJvm
import xyz.mederi.util.defaultAppNameFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOpeningFlowTest {

    @Test
    fun testProjectFileTreeProviderJvmResolutionAndDirectory() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "mederi-file-tree-test-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val subDir = File(tempDir, "subDir").apply { mkdirs() }
            val testFile = File(subDir, "test.txt").apply { writeText("Hello Mederi") }

            val provider = ProjectFileTreeProviderJvm(tempDir)

            // 1. isDirectory 判定
            assertTrue(provider.isDirectory("subDir"), "相对路径目录应判定为 isDirectory=true")
            assertTrue(provider.isDirectory(subDir.absolutePath), "绝对路径目录应判定为 isDirectory=true")
            assertEquals(false, provider.isDirectory("subDir/test.txt"), "文件不应判定为 isDirectory")

            // 2. resolvePath 判定
            val resolved = provider.resolvePath("subDir/test.txt")
            assertEquals(testFile.absolutePath, resolved)

            // 3. readText 判定（相对路径自愈）
            val content = provider.readText("subDir/test.txt")
            assertEquals("Hello Mederi", content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDefaultAppNameForMacAndFallback() {
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        if (isMac) {
            val readme = File("README.md").absolutePath
            if (File(readme).exists()) {
                val app = defaultAppNameFor(readme)
                println("defaultAppNameFor(README.md) -> '$app'")
                assertNotNull(app, "macOS 上 README.md 应查询到系统关联应用（如 Xcode 或 文本编辑）")
                assertTrue(app.isNotBlank(), "应用名不能为空白")
            }

            // 未知后缀或不存在的文件必须返回 null
            val unknown = defaultAppNameFor("/tmp/nonexistent-file-${System.nanoTime()}.unknown_ext_xyz")
            assertNull(unknown, "不存在或未关联应用的文件必须返回 null")
        }
    }

    @Test
    fun testOpenFileDispatchLogic() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "mederi-openfile-test-${System.nanoTime()}")
        tempDir.mkdirs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val subDir = File(tempDir, "docs").apply { mkdirs() }
            val codeFile = File(tempDir, "schema.sq").apply { writeText("CREATE TABLE user (id TEXT);") }
            val makefile = File(tempDir, "Makefile").apply { writeText("build:\n\techo done") }
            val officeFile = File(tempDir, "report.docx").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }

            val mockAiCore = MockAiCore()
            mockAiCore.initialize()
            val prefs = InMemoryPreferencesStore()
            val appState = AppState(mockAiCore, prefs, scope)
            appState.hydrate()
            appState.fileTreeProvider = ProjectFileTreeProviderJvm(tempDir)

            val vm = WorkspaceViewModel(appState)

            // 1. 目录点击 -> 触发 PendingFileOpen.OpenDirectory
            vm.openFile("docs")
            val pendingDir = vm.pendingFileOpen
            assertNotNull(pendingDir, "点击目录应触发 pendingFileOpen")
            assertTrue(pendingDir is PendingFileOpen.OpenDirectory, "应为 OpenDirectory 弹窗态")
            assertEquals(subDir.absolutePath, pendingDir.path)
            vm.cancelPendingFileOpen()
            assertNull(vm.pendingFileOpen)

            // 2. 代码/文本文件（含 .sq 与无扩展名 Makefile） -> 直接内置打开，不弹外部弹窗
            vm.openFile("schema.sq")
            delay(100)
            assertNull(vm.pendingFileOpen, "代码文件不应触发外部打开弹窗")
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel, "应切换至 ARTIFACTS 面板内置查看")

            vm.openFile("Makefile")
            delay(100)
            assertNull(vm.pendingFileOpen, "Makefile 等文本候选不应触发外部打开弹窗")
            assertEquals(RightDockPanel.ARTIFACTS, vm.activeDockPanel)

            // 3. 外部二进制文件 (.docx) -> 触发外部弹窗（OpenExternally 或 RevealInFolder）
            vm.openFile("report.docx")
            withTimeout(3_000) {
                while (vm.pendingFileOpen == null) delay(50)
            }
            val pendingOffice = vm.pendingFileOpen
            assertNotNull(pendingOffice, "外部 Office 文件应触发 pendingFileOpen")
            assertTrue(
                pendingOffice is PendingFileOpen.OpenExternally || pendingOffice is PendingFileOpen.RevealInFolder,
                "应为 OpenExternally 或 RevealInFolder"
            )
            vm.confirmPendingFileOpen()
            assertNull(vm.pendingFileOpen, "确认后 pendingFileOpen 应被清空")

        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
