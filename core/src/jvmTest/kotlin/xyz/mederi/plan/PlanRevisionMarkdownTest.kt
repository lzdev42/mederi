package xyz.mederi.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanRevisionMarkdownTest {
    @Test
    fun `plain replacements are grouped under their plan headings`() {
        val before = """
            # 请求失败重试策略

            ## 实现方案
            最多重试 3 次

            ## 验收标准
            共发起 4 次请求
        """.trimIndent()
        val after = before.replace("重试 3 次", "重试 2 次").replace("发起 4 次", "发起 3 次")

        assertEquals(
            """
                ## 修订记录

                **修订原因**　缩短失败等待

                ### 实现方案
                - ~~最多重试 3 次~~ → **最多重试 2 次**

                ### 验收标准
                - ~~共发起 4 次请求~~ → **共发起 3 次请求**
            """.trimIndent(),
            renderPlanRevisionMarkdown(before, after, "缩短失败等待"),
        )
    }

    @Test
    fun `table and code changes retain the Markdown needed to render each block`() {
        val fence = "```sh"
        val before = """
            ## 验收标准
            | 场景 | 次数 |
            |---|---|
            | 超时 | 4 |

            $fence
            # Run the collector
            check --count 4
            ```
        """.trimIndent()
        val after = before.replace("| 超时 | 4 |", "| 超时 | 3 |\n| 权限错误 | 1 |")
            .replace("check --count 4", "check --count 3")

        val result = renderPlanRevisionMarkdown(before, after, "调整重试上限")

        assertEquals(2, result.lineSequence().count { it == "> |---|---|" })
        assertTrue(result.contains("> | 权限错误 | 1 |"))
        assertTrue(result.contains("> $fence\n> # Run the collector\n> check --count 3\n> ```"))
        assertTrue(result.contains("**修改前**"))
        assertTrue(result.contains("**修改后**"))
        assertFalse(result.contains("### Run the collector"))
    }

    @Test
    fun `adding a final table row retains the original table alongside the new one`() {
        val before = """
            ## 验收标准
            | 场景 | 次数 |
            |---|---|
            | 超时 | 3 |

            ## 后续步骤
        """.trimIndent()
        val after = before.replace("| 超时 | 3 |", "| 超时 | 3 |\n| 权限错误 | 1 |")

        val result = renderPlanRevisionMarkdown(before, after, "补充权限错误验收")

        assertEquals(2, result.lineSequence().count { it == "> | 超时 | 3 |" })
        assertFalse(result.contains("后续步骤"))
    }

    @Test
    fun `revision text cannot break inline Markdown and identical versions keep their reason`() {
        val result = renderPlanRevisionMarkdown("旧 **文本**", "新 <内容>", "# 换行\n修订")

        assertTrue(result.contains("- ~~旧 \\*\\*文本\\*\\*~~ → **新 \\<内容\\>**"))
        assertTrue(result.contains("**修订原因**　\\# 换行 修订"))
        assertTrue(renderPlanRevisionMarkdown("方案", "方案", "重提").contains("方案正文未变化。"))
    }
}
