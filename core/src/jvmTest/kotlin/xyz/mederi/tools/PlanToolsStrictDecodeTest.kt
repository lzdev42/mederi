package xyz.mederi.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.mederi.domain.model.AgentMode
import xyz.mederi.domain.model.MederiEvent
import xyz.mederi.plan.Notebook
import xyz.mederi.plan.PlanApprovalRequester
import xyz.mederi.plan.PlanStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * plan-strict-decode / plan-desc-structure 验收测试。
 *
 * PlanTools 的 4 个宽松 serializer（LenientStringList / LenientWritePlanArgs /
 * LenientSubtaskArg / LenientGenerateSpecArgs）与 coerceObjectListField 已全部删除：
 * 三种常见错形（单字符串当 List<String>、双层编码字符串当复合列表、单对象当复合列表）
 * 不再在解码层矫正，一律抛 kotlinx SerializationException，由 Koog GenericAgentEnvironment
 * 把原始错误回喂模型自纠。
 *
 * Json 配置与 mederiToolSerializer 对齐（ignoreUnknownKeys=true、explicitNulls=false），
 * 不含 coerceInputValues（该开关只处理 null/非法枚举的默认值替换，不影响本测试要钉的形状错；
 * 不放进来是为了让测试配置与「错形必抛」语义一一对应）。
 */
class PlanToolsStrictDecodeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ── 三种错形均抛 SerializationException ──

    @Test
    fun `single string for List String field throws`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<PlanTools.WritePlanArgs>(
                """{"title":"t","subtasks":[{"name":"s0","targetFiles":"src/a.kt"}]}"""
            )
        }
        assertTrue(e.message!!.contains("targetFiles"), "error must locate the field: ${e.message}")
    }

    @Test
    fun `double-encoded string for composite list field throws`() {
        // 内层为真实可解析的 JSON 数组（旧 LenientWritePlanArgs 会解析它并当作 keyDecisions）
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<PlanTools.WritePlanArgs>(
                """{"title":"t","subtasks":[],"keyDecisions":"[{\"question\":\"q\",\"choice\":\"c\",\"rationale\":\"r\"}]"}"""
            )
        }
        assertTrue(e.message!!.contains("keyDecisions"), "error must locate the field: ${e.message}")
    }

    @Test
    fun `single object for composite list field throws`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString<PlanTools.WritePlanArgs>(
                """{"title":"t","subtasks":[{"name":"s0","decisions":{"question":"q","choice":"c"}}]}"""
            )
        }
        assertTrue(e.message!!.contains("decisions"), "error must locate the field: ${e.message}")
    }

    @Test
    fun `well-formed args still decode`() {
        // 严格化不得误伤合法形状
        val args = json.decodeFromString<PlanTools.WritePlanArgs>(
            """{"title":"t","subtasks":[{"name":"s0","targetFiles":["src/a.kt"],"verification":"echo ok","verificationExpected":"ok","verificationExpectStdoutContains":["ok"]}]}"""
        )
        assertTrue(args.subtasks.size == 1)
        assertTrue(args.subtasks[0].targetFiles == listOf("src/a.kt"))
        assertTrue(args.subtasks[0].verificationExpectStdoutContains == listOf("ok"))
    }

    // ── 结构常量 + 工具描述 ──

    @Test
    fun `args structure constant lists every field`() {
        val structure = WRITE_PLAN_ARGS_STRUCTURE
        val required = listOf(
            // WritePlanArgs 顶层
            "planId", "title", "summary", "projectContext", "languageStack", "businessLogic",
            "background", "problemAnalysis", "approach", "expectedOutcome", "testMethod",
            "inScope", "outScope", "openQuestions", "keyDecisions", "changes",
            "dataAndParams", "risks", "successCriteria", "architecture", "researchNotes",
            "appendix", "subtasks",
            // DecisionArg
            "question", "choice", "rationale", "alternatives",
            // PlannedChangeArg
            "module", "action", "filePath", "description",
            // SubtaskArg
            "name", "planDetail", "targetFiles", "decisions", "verification",
            "verificationCwd", "verificationTimeoutSeconds", "verificationExpected",
            "verificationExpectStdoutContains", "verificationExpectStdoutNotContains",
            "dependsOn", "parallelizable", "acceptanceCases", "acceptanceFiles",
            "overallAcceptance",
            // AcceptanceCase
            "id", "objective", "setup", "input", "steps", "observationSource", "expected",
            "detects", "manual"
        )
        required.forEach { field ->
            assertTrue(
                structure.contains("\"$field\""),
                "structure must list field \"$field\"\n$structure"
            )
        }
        // 只含字段名 + 空值/类型说明，不得夹带示例数据（用户明确要求无数据示例）
        assertTrue(!structure.contains("example", ignoreCase = true), structure)
    }

    private val tmpDir = File.createTempFile("mederi-plan-strict-test", "").apply {
        delete()
        mkdirs()
        File(this, ".mederi/plans").mkdirs()
    }

    private val planStore = PlanStore(listOf(tmpDir.absolutePath))
    private val eventBus = MutableSharedFlow<MederiEvent>(replay = 64)
    private val planApprovalRequester = PlanApprovalRequester("sess_strict", eventBus)

    private fun planTools() = PlanTools(
        sessionId = "sess_strict",
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

    @Test
    fun `write_plan description includes arguments structure`() {
        val description = planTools().WritePlanTool().descriptor.description
        assertTrue(description.contains("Arguments format:"), "must reference the structure: $description")
        assertTrue(description.contains("\"subtasks\""), "must list subtasks: $description")
        assertTrue(description.contains("\"acceptanceCases\""), "must list acceptanceCases: $description")
        assertTrue(description.contains("\"parallelizable\""), "must list parallelizable: $description")
        assertTrue(description.contains("\"dependsOn\""), "must list dependsOn: $description")
    }
}