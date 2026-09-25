package xyz.mederi.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowserDecisionParserTest {

    @Test
    fun `parse and extract standard actions`() {
        val json = """
        {
            "thought": "Navigate to page",
            "memory": "Starting",
            "actions": [
                {"type": "navigate", "url": "https://example.com"},
                {"type": "click", "elementRef": "12"},
                {"type": "type", "elementRef": "15", "text": "kotlin"},
                {"type": "scroll", "elementRef": "0", "deltaX": 0, "deltaY": 300},
                {"type": "done", "message": "finished"}
            ],
            "is_done": true
        }
        """.trimIndent()

        val decision = BrowserDecisionParser.parse(json)
        assertEquals("Navigate to page", decision.thought)
        assertTrue(decision.isDone)

        val actions = BrowserDecisionParser.toActions(decision)
        assertEquals(5, actions.size)
        assertIs<BrowserAction.Navigate>(actions[0])
        assertIs<BrowserAction.Click>(actions[1])
        assertIs<BrowserAction.Type>(actions[2])
        assertIs<BrowserAction.Scroll>(actions[3])
        assertIs<BrowserAction.Done>(actions[4])
    }

    @Test
    fun `parse and extract judge action`() {
        val json = """
        {
            "thought": "Checking job criteria",
            "memory": "Evaluating candidate",
            "actions": [
                {
                    "type": "judge",
                    "content": "Looking for 5+ years Kotlin experience. Remote allowed.",
                    "instruction": "Does this job allow remote work and match Kotlin requirement?"
                }
            ],
            "is_done": false
        }
        """.trimIndent()

        val decision = BrowserDecisionParser.parse(json)
        val actions = BrowserDecisionParser.toActions(decision)
        assertEquals(1, actions.size)
        val judgeAction = assertIs<BrowserAction.Judge>(actions[0])
        assertTrue(judgeAction.content.contains("Kotlin"))
        assertTrue(judgeAction.instruction.contains("remote work"))
    }

    @Test
    fun `brain result to compact string formats properly`() {
        val resultWithMatch = BrainResult(
            success = true,
            data = mapOf("match" to "true", "reason" to "Candidate meets all criteria"),
            text = "{\"match\": true, \"reason\": \"Candidate meets all criteria\"}"
        )
        assertEquals("match=true, reason=Candidate meets all criteria", resultWithMatch.toCompactString())

        val failedResult = BrainResult(
            success = false,
            text = "LLM API timeout"
        )
        assertEquals("FAIL: LLM API timeout", failedResult.toCompactString())
    }
}
