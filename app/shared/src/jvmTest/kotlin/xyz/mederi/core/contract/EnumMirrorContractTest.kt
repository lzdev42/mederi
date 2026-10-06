package xyz.mederi.core.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mederi.core.contract.models.AgentMode
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.core.contract.models.ModelOrigin
import xyz.mederi.core.contract.models.ProtocolType
import xyz.mederi.core.contract.models.QuestionRequest
import xyz.mederi.core.contract.models.TodoStatus

/**
 * 枚举镜像对等锁测试（AGENTS §6「契约同步」）。
 *
 * core 与 app/shared 是两个独立模块，core 不能依赖 app/shared，UI 侧只能镜像一份常量。
 * 本测试把「镜像 entries == core 真值」变成机器校验：
 * - 任一侧加枚举值未同步另一侧 → 此测试必须失败
 * - 字段名/类型漂移 → 此测试必须失败
 *
 * 锁的枚举对（均经 valueOf(.name) 或 @SerialName JSON 桥接）：
 * - core [xyz.mederi.domain.model.EventType] ↔ contract [CoreEventType]（MederiModelMapper:118）
 * - core [xyz.mederi.provider.domain.model.ProviderType] ↔ contract [ProtocolType]（MederiModelMapper:100）
 * - core [xyz.mederi.domain.model.AgentMode] ↔ contract [AgentMode]（MederiInputMapper:119）
 * - core [xyz.mederi.domain.model.ModelOrigin] ↔ contract [ModelOrigin]（ModelMerge 语义）
 * - core [xyz.mederi.domain.model.TodoStatus] ↔ contract [TodoStatus]（@SerialName JSON 桥）
 * - core [xyz.mederi.question.Question] ↔ contract [QuestionRequest.Question]（JSON wire 桥）
 */
class EnumMirrorContractTest {

    // ─── EventType ↔ CoreEventType ─────────────────────────────────────────────

    @Test
    fun eventTypeEntriesMatchCoreEventType() {
        val coreNames = xyz.mederi.domain.model.EventType.entries.map { it.name }.toSet()
        val contractNames = CoreEventType.entries.map { it.name }.toSet()
        assertEquals(
            coreNames, contractNames,
            "EventType↔CoreEventType 两侧枚举值集合不一致：\n" +
                "core-only=${coreNames - contractNames}\n" +
                "contract-only=${contractNames - coreNames}"
        )
    }

    // ─── ProviderType ↔ ProtocolType ────────────────────────────────────────────

    @Test
    fun providerTypeEntriesMatchProtocolType() {
        val coreNames = xyz.mederi.provider.domain.model.ProviderType.entries.map { it.name }.toSet()
        val contractNames = ProtocolType.entries.map { it.name }.toSet()
        assertEquals(
            coreNames, contractNames,
            "ProviderType↔ProtocolType 两侧枚举值集合不一致：\n" +
                "core-only=${coreNames - contractNames}\n" +
                "contract-only=${contractNames - coreNames}"
        )
    }

    // ─── AgentMode ↔ AgentMode ──────────────────────────────────────────────────

    @Test
    fun agentModeEntriesMatch() {
        val coreNames = xyz.mederi.domain.model.AgentMode.entries.map { it.name }.toSet()
        val contractNames = AgentMode.entries.map { it.name }.toSet()
        assertEquals(
            coreNames, contractNames,
            "AgentMode 两侧枚举值集合不一致：\n" +
                "core-only=${coreNames - contractNames}\n" +
                "contract-only=${contractNames - coreNames}"
        )
    }

    // ─── ModelOrigin ↔ ModelOrigin ──────────────────────────────────────────────

    @Test
    fun modelOriginEntriesMatch() {
        val coreNames = xyz.mederi.domain.model.ModelOrigin.entries.map { it.name }.toSet()
        val contractNames = ModelOrigin.entries.map { it.name }.toSet()
        assertEquals(
            coreNames, contractNames,
            "ModelOrigin 两侧枚举值集合不一致：\n" +
                "core-only=${coreNames - contractNames}\n" +
                "contract-only=${contractNames - coreNames}"
        )
    }

    // ─── TodoStatus @SerialName 一致性 ─────────────────────────────────────────

    /**
     * core TodoStatus 用 UPPER_SNAKE（PENDING）；契约 TodoStatus 用 PascalCase（Pending）。
     * entry name 有意不同，桥接点是 @SerialName（JSON wire），故锁 @SerialName 集合相等。
     */
    @Test
    fun todoStatusSerialNamesMatch() {
        val json = Json

        val coreSerialNames = xyz.mederi.domain.model.TodoStatus.entries
            .map { json.encodeToJsonElement(it).toString() }
            .toSet()
        val contractSerialNames = TodoStatus.entries
            .map { json.encodeToJsonElement(it).toString() }
            .toSet()

        assertEquals(
            coreSerialNames, contractSerialNames,
            "TodoStatus 两侧 @SerialName 集合不一致（JSON wire 值）：\n" +
                "core-only=${coreSerialNames - contractSerialNames}\n" +
                "contract-only=${contractSerialNames - coreSerialNames}"
        )
        // 数量也必须相等（防止一侧多值而 @SerialName 恰好撞了另一侧某值）
        assertEquals(
            xyz.mederi.domain.model.TodoStatus.entries.size,
            TodoStatus.entries.size,
            "TodoStatus 两侧枚举值数量不一致"
        )
    }

    // ─── Question data class 字段对等 ──────────────────────────────────────────

    @Test
    fun questionFieldsMatch() {
        val json = Json

        // 构造 core Question
        val coreQuestion = xyz.mederi.question.Question(
            id = "q1",
            prompt = "Test prompt",
            options = listOf("a", "b"),
            multiSelect = true,
        )
        // 构造 contract Question
        val contractQuestion = QuestionRequest.Question(
            id = "q1",
            prompt = "Test prompt",
            options = listOf("a", "b"),
            multiSelect = true,
        )

        val coreJson = json.encodeToJsonElement(coreQuestion) as JsonObject
        val contractJson = json.encodeToJsonElement(contractQuestion) as JsonObject

        assertEquals(
            coreJson.keys, contractJson.keys,
            "Question 两侧字段名集合不一致：\n" +
                "core-only=${coreJson.keys - contractJson.keys}\n" +
                "contract-only=${contractJson.keys - coreJson.keys}"
        )

        // 逐字段值比对（同一输入，序列化后值必须相同）
        assertTrue(
            coreJson.keys.all { coreJson[it] == contractJson[it] },
            "Question 两侧同名字段序列化值不一致"
        )
    }
}
