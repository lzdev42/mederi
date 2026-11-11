package xyz.mederi.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.AcceptanceCase
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * write_plan 入参容错化回归测试：DTO 全字段默认值化 → 解码永不因缺字段失败，
 * 缺字段到 validatePlan/更新路径一次性全部点名（fix ALL + retry once）。
 *
 * 背景（2026-11 实测）：glm-5.3 的 write_plan 18 个 change 里 7 个缺 rationale，
 * kotlinx 只报 "Field 'rationale' is required ... $.3" 第一个，模型逐轮猜补；
 * glm-5.2 的 "Fields [title, subtasks] are required" 大半是局部更新模式被 DTO 必填挡住。
 * 本测试钉住新语义：
 * - 缺字段可解码（解析进默认值）；
 * - 校验期逐字段点名（multiple errors in ONE message）；
 * - 局部更新形状（planId + 少数字段）可解码；
 * - AcceptanceCase 缺 detects 可解码，validationError 点名。
 *
 * validatePlan 是 private，经 WritePlanTool.execute 绕道（AUTONOMOUS 下校验通过即落盘）。
 */
class WritePlanArgsLeniencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val tmpDir = File.createTempFile("mederi-plan-leniency-test", "").apply {
        delete()
        mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))
    private val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
    private val planApprovalRequester = PlanApprovalRequester("sess_leniency", eventBus)

    private fun planTools() = PlanTools(
        sessionId = "sess_leniency",
        agentMode = AgentMode.AUTONOMOUS,
        planStore = planStore,
        planApprovalRequester = planApprovalRequester,
        notebook = Notebook(listOf(tmpDir.absolutePath)),
        eventBus = eventBus
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private val validChangeJson = """{"module":"core","action":"MODIFY","filePath":"a.kt","description":"d","rationale":"r"}"""

    private val validSubtaskJson = """{"name":"s0","planDetail":"d","targetFiles":["f.kt"],"verification":"./gradlew test","verificationExpected":"pass","overallAcceptance":true,"acceptanceCases":[{"id":"case1","objective":"o","setup":"s","input":"i","steps":"st","observationSource":"obs","expected":"exp","detects":"det"}]}"""

    private fun planJson(
        titleJson: String? = "\"t\"",
        changesJson: String = "[$validChangeJson]",
        subtasksJson: String = "[$validSubtaskJson]"
    ): String {
        val titlePart = titleJson?.let { "\"title\":$it," } ?: ""
        return """{$titlePart"summary":"s","approach":"a","languageStack":"Kotlin","keyDecisions":[{"question":"q","choice":"c","rationale":"r"}],"changes":$changesJson,"subtasks":$subtasksJson}"""
    }

    @Test
    fun `decodes without title and subtasks (partial update shape)`() {
        val args = json.decodeFromString<PlanTools.WritePlanArgs>("""{"planId":"plan_x","summary":"new summary"}""")
        assertEquals("plan_x", args.planId)
        assertEquals("new summary", args.summary)
        assertEquals("", args.title)
        assertTrue(args.subtasks.isEmpty())
    }

    @Test
    fun `decodes changes with repeated missing rationale (incident shape)`() {
        val args = json.decodeFromString<PlanTools.WritePlanArgs>(
            """{"title":"t","subtasks":[],"changes":[{"module":"core","action":"MODIFY","filePath":"a.kt","description":"d"},{"module":"core","action":"NEW","filePath":"b.kt","description":"d"}]}"""
        )
        assertEquals(2, args.changes.size)
        assertEquals("", args.changes[0].rationale)
        assertEquals("", args.changes[1].rationale)
    }

    @Test
    fun `execute reports all missing rationales in one validation error`() = runBlocking {
        val changesJson = """[
            {"module":"core","action":"MODIFY","filePath":"a.kt","description":"d"},
            {"module":"core","action":"MODIFY","filePath":"b.py","description":"d"},
            {"module":"core","action":"MODIFY","filePath":"c.rs","description":"d","rationale":"r"}
        ]"""
        val result = planTools().WritePlanTool().execute(
            json.decodeFromString<PlanTools.WritePlanArgs>(planJson(changesJson = changesJson))
        )
        assertTrue(result.startsWith("Error: Plan validation failed"), result)
        assertTrue(result.contains("fix ALL"), result)
        assertEquals(2, result.split("rationale is required").size - 1, result)
        assertTrue(result.contains("a.kt") && result.contains("b.py"), result)
        assertFalse(result.contains("failed to parse arguments"), result)
    }

    @Test
    fun `execute reports missing title and subtask name together`() = runBlocking {
        val subtaskNoName = """{"planDetail":"d","targetFiles":["f.kt"],"verification":"./gradlew test","verificationExpected":"pass","overallAcceptance":true,"acceptanceCases":[{"id":"case1","objective":"o","setup":"s","input":"i","steps":"st","observationSource":"obs","expected":"exp","detects":"det"}]}"""
        val result = planTools().WritePlanTool().execute(
            json.decodeFromString<PlanTools.WritePlanArgs>(
                planJson(titleJson = null, subtasksJson = "[$subtaskNoName]")
            )
        )
        assertTrue(result.startsWith("Error: Plan validation failed"), result)
        assertTrue(result.contains("Title is required"), result)
        assertTrue(result.contains("name is required"), result)
    }

    @Test
    fun `valid plan still passes validation`() = runBlocking {
        val result = planTools().WritePlanTool().execute(
            json.decodeFromString<PlanTools.WritePlanArgs>(planJson())
        )
        assertFalse(result.contains("Plan validation failed"), result)
        assertFalse(result.contains("failed to parse arguments"), result)
    }

    @Test
    fun `acceptance case missing detects decodes and validation names it`() {
        val case = json.decodeFromString<AcceptanceCase>(
            """{"id":"c1","objective":"o","setup":"s","input":"i","steps":"st","observationSource":"obs","expected":"exp"}"""
        )
        assertEquals("", case.detects)
        assertEquals("acceptance case 'c1': field 'detects' is blank", case.validationError())
    }
}
