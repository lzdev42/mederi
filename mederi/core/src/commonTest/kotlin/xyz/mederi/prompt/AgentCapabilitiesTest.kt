package xyz.mederi.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mederi.domain.model.AgentCapabilities
import xyz.mederi.domain.model.SubagentRole
import xyz.mederi.skills.domain.SkillInfo

/**
 * 中心化代理能力表 + skills 提示词注入测试。
 * 新增子代理角色时改 AgentCapabilities.of 一个分支即可，这里锁定语义。
 */
class AgentCapabilitiesTest {

    @Test
    fun of_mapsRoles() {
        assertEquals(AgentCapabilities.MAIN, AgentCapabilities.of(null))
        assertEquals(AgentCapabilities.EXECUTOR, AgentCapabilities.of(SubagentRole.EXECUTOR))
        assertEquals(AgentCapabilities.RESEARCHER, AgentCapabilities.of(SubagentRole.RESEARCHER))
        assertEquals(AgentCapabilities.BROWSER, AgentCapabilities.of(SubagentRole.BROWSER_OPERATOR))
        assertEquals(AgentCapabilities.BROWSER, AgentCapabilities.of(SubagentRole.BROWSER_BRAIN))
    }

    @Test
    fun browser_mapsToBrowserCapabilities() {
        assertTrue(AgentCapabilities.BROWSER.inheritMcp, "浏览器角色继承 MCP")
        assertTrue(AgentCapabilities.BROWSER.inheritSkills, "浏览器角色注入 skills")
    }

    @Test
    fun mainAndExecutor_inheritMcpAndSkills() {
        assertTrue(AgentCapabilities.MAIN.inheritMcp, "主代理继承 MCP")
        assertTrue(AgentCapabilities.MAIN.inheritSkills, "主代理注入 skills")
        assertTrue(AgentCapabilities.EXECUTOR.inheritMcp, "执行者继承 MCP")
        assertTrue(AgentCapabilities.EXECUTOR.inheritSkills, "执行者注入 skills")
    }

    @Test
    fun researcher_inheritsMcpButNotSkills() {
        assertTrue(AgentCapabilities.RESEARCHER.inheritMcp, "研究者继承 MCP（研究可用 context7 等）")
        assertFalse(AgentCapabilities.RESEARCHER.inheritSkills, "研究者不注入 skills")
    }

    @Test
    fun withSkills_appendsSectionWhenSkillsExist() {
        val base = "BASE PROMPT"
        val skills = listOf(
            SkillInfo(name = "csv-analysis", description = "Parse CSV files", location = "/skills/csv-analysis/SKILL.md"),
            SkillInfo(name = "latex-format", description = "Format LaTeX", location = "/skills/latex-format/SKILL.md")
        )
        val result = SystemPrompts.withSkills(base, skills)
        assertTrue(result.startsWith("BASE PROMPT"))
        assertTrue(result.contains("# Available Skills"))
        assertTrue(result.contains("**csv-analysis**: Parse CSV files (SKILL.md at `/skills/csv-analysis/SKILL.md`)"))
        assertTrue(result.contains("**latex-format**: Format LaTeX"))
    }

    @Test
    fun withSkills_returnsBaseUnchangedWhenEmpty() {
        val base = "BASE PROMPT"
        assertEquals(base, SystemPrompts.withSkills(base, emptyList()))
    }
}
