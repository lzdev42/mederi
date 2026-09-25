package xyz.mederi.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserBrainTest {

    @Test
    fun `test judge prompt building with recipe`() {
        val userPrompt = BrowserBrainPrompt.buildJudgeUserPrompt(
            content = "Company: Mederi\nRole: Kotlin Engineer\nRemote: Yes",
            instruction = "Check if remote is allowed",
            recipeRules = "Rule 1: Only consider remote positions."
        )

        assertTrue(userPrompt.contains("Recipe Rules"))
        assertTrue(userPrompt.contains("Only consider remote positions"))
        assertTrue(userPrompt.contains("Company: Mederi"))
        assertTrue(userPrompt.contains("Check if remote is allowed"))
    }

    @Test
    fun `test report prompt building with history`() {
        val userPrompt = BrowserBrainPrompt.buildReportUserPrompt(
            taskGoal = "Find remote jobs",
            taskHistory = "Step 1: Opened site\nStep 2: Scrolled down",
            rawOperatorMessage = "Extracted 5 jobs",
            recipeRules = "Filter remote"
        )

        assertTrue(userPrompt.contains("Find remote jobs"))
        assertTrue(userPrompt.contains("Step 1: Opened site"))
        assertTrue(userPrompt.contains("Extracted 5 jobs"))
        assertTrue(userPrompt.contains("Filter remote"))
    }

    @Test
    fun `test brain result compact string variants`() {
        val matchTrue = BrainResult(
            success = true,
            data = mapOf("match" to "true", "reason" to "Requirement satisfied")
        )
        assertEquals("match=true, reason=Requirement satisfied", matchTrue.toCompactString())

        val summaryOnly = BrainResult(
            success = true,
            data = mapOf("summary" to "Task finished with 10 results")
        )
        assertEquals("summary=Task finished with 10 results", summaryOnly.toCompactString())

        val failure = BrainResult(
            success = false,
            text = "Rate limited"
        )
        assertEquals("FAIL: Rate limited", failure.toCompactString())
    }

    @Test
    fun `test recipe model defaults`() {
        val recipe = BrowserRecipe(name = "test_recipe")
        assertEquals("test_recipe", recipe.name)
        assertEquals("", recipe.description)
        assertEquals("", recipe.judgeRules)
        assertEquals(null, recipe.drillScript)
    }
}
