package xyz.mederi.koog

import xyz.mederi.tools.subagent.SubagentManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubagentEventMessageTest {

    @Test
    fun testExtractReportPath() {
        val executorOutput = """
            Build successful. All tests passing.
            [executor report saved to .mederi/plans/plan_123/reports/01-executor.md]
        """.trimIndent()
        assertEquals(
            ".mederi/plans/plan_123/reports/01-executor.md",
            SubagentManager.extractReportPath(executorOutput)
        )

        val researchOutput = """
            Investigation finished.
            [research report saved to .mederi/plans/plan_abc/reports/00-research.md]
        """.trimIndent()
        assertEquals(
            ".mederi/plans/plan_abc/reports/00-research.md",
            SubagentManager.extractReportPath(researchOutput)
        )

        val noReportOutput = "Agent finished without saving any report file."
        assertNull(SubagentManager.extractReportPath(noReportOutput))
    }
}
