package xyz.mederi.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import xyz.mederi.browser.BrowserRegistry
import xyz.mederi.browser.obscura.ObscuraBrowserControl
import xyz.mederi.core.bridge.MederiAiCore

/**
 * ST5：MederiAiCore.initialize 注册 Obscura 后端（collector 观测协议版）。
 *
 * 走 internal 直调路径而非真实 initialize()：真实 initialize() 会拉起完整
 * Mederi 实例（DB、workspace 恢复扫描、模型目录拉取、autotitle 事件流等），
 * 在单元测试环境下既重又易 flaky（SharedLogicDesktopTest 的真实构造都依赖
 * MockAiCore 或完整 runtime 装配）；而 Obscura 注册逻辑已抽成 internal
 * `registerObscuraBackend(resolver)` 由 initialize() 调用，
 * 测试直调它能精确验证注册语义（hit/absent/per-task 实例隔离）且零进程副作用。
 */
class ObscuraRegistrationTest {

    private val core = MederiAiCore(configDir = File(System.getProperty("java.io.tmpdir"), "mederi-obscura-test").path)

    @AfterTest
    fun cleanup() {
        BrowserRegistry.unregister("obscura")
        BrowserRegistry.defaultName = null
        core.obscuraBinaryResolver = { xyz.mederi.browser.obscura.ObscuraBinaryLocator.resolve() }
    }

    @Test
    fun st5Register() {
        val resolver: () -> String? = { "/fake/obscura" }
        core.registerObscuraBackend(resolver)
        assertTrue(BrowserRegistry.availableNames().contains("obscura"), "应注册 obscura")
        assertEquals("obscura", BrowserRegistry.default()?.name)
        writeObservation("st5_register", "obscura+default")
    }

    @Test
    fun st5Absent() {
        val resolver: () -> String? = { null }
        core.registerObscuraBackend(resolver)
        assertTrue(BrowserRegistry.availableNames().isEmpty(), "resolver 未命中时不得虚假注册")
        assertNull(BrowserRegistry.default(), "无注册时 default 应为 null")
        writeObservation("st5_absent", "empty-registry")
    }

    @Test
    fun st5PerTask() = runBlocking {
        val resolver: () -> String? = { "/fake/obscura" }
        core.registerObscuraBackend(resolver)
        val entry = BrowserRegistry.resolve("obscura")
        assertNotNull(entry)
        // 只调 factory（建轻量 control），不调 start()——不起进程
        val first = entry.factory("t1", "s1")
        val second = entry.factory("t2", "s2")
        assertTrue(first is ObscuraBrowserControl, "factory 应产出 ObscuraBrowserControl")
        assertTrue(second is ObscuraBrowserControl, "factory 应产出 ObscuraBrowserControl")
        assertFalse(first === second, "每任务应新建不同实例")
        writeObservation("st5_per_task", "distinct-instances")
    }

    // ---- 观测文件：gradle workingDir=app/shared/，collector 读仓库根 core/build/ --------------

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            if (File(current, "settings.gradle.kts").isFile) return current
            dir = current.parentFile
        }
        throw IllegalStateException("仓库根 (含 settings.gradle.kts) 在 user.dir 上溯 6 级内未找到: ${System.getProperty("user.dir")}")
    }

    private fun observationFile(): File {
        val dir = File(repoRoot(), "core/build")
        dir.mkdirs()
        return File(dir, "obscura-registration.json")
    }

    /** 合并写观测文件：三键齐全，每次只更新当前用例的键。 */
    private fun writeObservation(key: String, value: String) {
        val file = observationFile()
        val current = if (file.isFile) {
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())
        } else {
            JsonObject(emptyMap())
        }
        val merged = JsonObject(current + (key to JsonPrimitive(value)))
        file.writeText(kotlinx.serialization.json.Json {
            prettyPrint = true
        }.encodeToString(JsonObject.serializer(), merged))
    }
}
