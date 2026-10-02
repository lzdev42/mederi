package xyz.mederi.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 子代理活动词表的**跨模块契约锁**（AGENTS §6「契约同步」）。
 *
 * core 与 app/shared 是两个模块，core 不能依赖 app/shared，UI 侧只能镜像一份常量
 * （`xyz.mederi.core.contract.models.SubagentActivity`）。本测试把「镜像 == core 真值」变成
 * 机器校验：core 改词表而契约没跟上时，此测试必须失败——而不是等到运行期 UI 活动徽标
 * 悄悄回落成「深度思考中…」（历史缺陷：生产端发大写、UI 比小写，三个分支恒不命中）。
 */
class SubagentActivityContractTest {

    @Test
    fun contractMirrorMatchesCoreValueByValue() {
        assertEquals(
            xyz.mederi.tools.subagent.SubagentActivity.THINKING,
            xyz.mederi.core.contract.models.SubagentActivity.THINKING,
            "THINKING 两侧不一致：core 改词表后必须同步契约镜像"
        )
        assertEquals(
            xyz.mederi.tools.subagent.SubagentActivity.TOOL_CALL,
            xyz.mederi.core.contract.models.SubagentActivity.TOOL_CALL,
            "TOOL_CALL 两侧不一致：core 改词表后必须同步契约镜像"
        )
        assertEquals(
            xyz.mederi.tools.subagent.SubagentActivity.OUTPUT,
            xyz.mederi.core.contract.models.SubagentActivity.OUTPUT,
            "OUTPUT 两侧不一致：core 改词表后必须同步契约镜像"
        )
    }

    @Test
    fun activityValuesAreDistinctAndNonBlank() {
        val values = setOf(
            xyz.mederi.tools.subagent.SubagentActivity.THINKING,
            xyz.mederi.tools.subagent.SubagentActivity.TOOL_CALL,
            xyz.mederi.tools.subagent.SubagentActivity.OUTPUT
        )
        assertEquals(3, values.size, "三个活动值必须互不相同，否则 UI 徽标分支会互相遮蔽")
        assertTrue(values.all { it.isNotBlank() }, "活动值不得为空串")
    }
}
