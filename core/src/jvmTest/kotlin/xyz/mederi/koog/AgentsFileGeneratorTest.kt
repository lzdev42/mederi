package xyz.mederi.koog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.mederi.infrastructure.koog.stripCodeFence

/**
 * AGENTS.md 生成服务的纯逻辑回归：
 * 1. stripCodeFence：模型无视"裸输出"指令时剥掉首尾代码围栏；裸输出不动。
 * （LLM 成功路径需要真实模型/API key，不做自动化；失败路径由 runCatching + check 覆盖）
 */
class AgentsFileGeneratorTest {

    @Test
    fun `裸 markdown 输出原样返回`() {
        assertEquals("# Title\n\ncontent", stripCodeFence("# Title\n\ncontent"))
    }

    @Test
    fun `首尾包裹的代码围栏被剥掉`() {
        assertEquals(
            "# Title\n\ncontent",
            stripCodeFence("```markdown\n# Title\n\ncontent\n```")
        )
    }

    @Test
    fun `只有开头围栏或围栏不成对时不动`() {
        assertEquals("```markdown\n# Title", stripCodeFence("```markdown\n# Title"))
        assertEquals("a ``` b", stripCodeFence("a ``` b"))
    }

    @Test
    fun `stripCodeFence 对空文本安全`() {
        assertEquals("", stripCodeFence(""))
        assertTrue(true) // 到这里说明没抛异常
    }
}
