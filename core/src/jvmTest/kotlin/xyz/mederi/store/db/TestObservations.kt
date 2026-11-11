package xyz.mederi.store.db

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 测试观测收集器。
 *
 * 把断言通过后的**实际运行时观测值**记录到 core/build/test-obs.json，
 * 验证命令 `cat` 该文件、acceptance runner 按计划层期望值比对。
 *
 * 原则：record 的值必须来自运行时实测变量（非硬编码字符串）——
 * 计划层期望值在批准时固定，测试断言在实现时写，两者不一致即被抓（独立交叉校验）。
 */
object TestObservations {
    private val json = Json { prettyPrint = false }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val observations = linkedMapOf<String, String>()

    /** 记录一条实测观测值并整体重写观测文件（幂等累积，多个测试类共享同一文件）。 */
    fun record(caseId: String, observed: String) {
        observations[caseId] = observed
        File("build/test-obs.json")
            .apply { parentFile?.mkdirs() }
            .writeText(json.encodeToString(serializer, observations.toMap()))
    }
}
