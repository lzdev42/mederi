package xyz.mederi.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * browserHome 接线单元测试：browserRuntimeDirs 路径解析 + resolveRecipeFrom 从 skills 目录载出配方。
 */
class BrowserHomeWiringTest {

    @Test
    fun browserRuntimeDirsParsesReportsAndSkills() {
        val tmp = java.nio.file.Files.createTempDirectory("browser-home").toFile()
        val dirs = browserRuntimeDirs(tmp.absolutePath)
        assertNotNull(dirs)
        assertEquals(java.io.File(tmp, "reports"), dirs!!.workingDir)
        assertEquals(java.io.File(tmp, "skills"), dirs.skillsDir)
        // 未配置（null/空白）不伪造路径，返回 null
        assertNull(browserRuntimeDirs(null))
        assertNull(browserRuntimeDirs(""))
    }

    @Test
    fun resolveRecipeFromLoadsDrillScriptFromSkillsDir() {
        val tmp = java.nio.file.Files.createTempDirectory("browser-home-skill").toFile()
        val skillsDir = java.io.File(tmp, "skills")
        skillsDir.mkdirs()
        java.io.File(skillsDir, "demo.md").writeText(
            """
            ---
            name: demo
            description: 示例
            ---

            ## 操作规则
            打开示例页面。

            ## Drill
            ```json
            {"task_name":"demo_task","steps":[{"step_name":"go","action":"navigate","url":"https://demo.example"}]}
            ```

            ## 判定规则
            - 页面包含目标内容 → 成功
            """.trimIndent()
        )
        val store = RecipeStore(skillsDir)

        val loaded = resolveRecipeFrom(BrowserRecipe(name = "demo"), store)
        assertNotNull(loaded)
        assertTrue(loaded!!.drillScript?.contains("demo_task") == true)
        assertTrue(loaded.operatorContent.contains("打开示例页面"))

        // store 为 null 或 recipe 为 null 时原样返回
        assertNull(resolveRecipeFrom(null, store))
        // store 为 null：原样返回 recipe（不查文件系统），其 drillScript 仍为 null
        assertNull(resolveRecipeFrom(BrowserRecipe(name = "demo"), null)?.drillScript)
        // 未找到 skill：安全回退原 recipe（drillScript 仍为 null）
        assertNull(resolveRecipeFrom(BrowserRecipe(name = "missing"), store)?.drillScript)
    }
}
