package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 链接目标分档的穷举锁定测试（本地路径 vs 外部链接的唯一真理源）。
 *
 * 易错点全部显式断言：
 * 1. `file://` / `file:` 前缀必须剥掉前缀；
 * 2. `/` 开头绝对路径与 Windows 盘符（`C:\...`）原样返回；
 * 3. http/https/mailto 等一律 null（交给系统浏览器）；
 * 4. `#L10-L20` 这类行锚点必须剥掉（否则扩展名分档误判 → 代码文件打不开）。
 */
class LinkTargetClassifierTest {

    @Test
    fun fileUrlPrefixesAreStripped() {
        assertEquals("/Users/x/plan.md", LinkTargetClassifier.localPathOrNull("file:///Users/x/plan.md"))
        assertEquals("/Users/x/plan.md", LinkTargetClassifier.localPathOrNull("file:/Users/x/plan.md"))
    }

    @Test
    fun absolutePathAndWindowsDriveReturnAsIs() {
        assertEquals("/etc/hosts", LinkTargetClassifier.localPathOrNull("/etc/hosts"))
        assertEquals("C:\\repo\\file.kt", LinkTargetClassifier.localPathOrNull("C:\\repo\\file.kt"))
        assertEquals("D:/data", LinkTargetClassifier.localPathOrNull("D:/data"))
    }

    /**
     * 行锚点必须剥掉：AI 生成的代码引用链接形如 `file:///abs/Foo.kt#L10-L20`，
     * 锚点留在路径里会让扩展名变成 `kt#l10-l20` → 分档误判（既不开查看器，`open -R` 也失败）。
     */
    @Test
    fun lineAnchorsAreStripped() {
        assertEquals("/Users/x/Foo.kt", LinkTargetClassifier.localPathOrNull("file:///Users/x/Foo.kt#L10-L20"))
        assertEquals("/Users/x/Foo.kt", LinkTargetClassifier.localPathOrNull("file:///Users/x/Foo.kt#L58"))
        assertEquals("/Users/x/Foo.kt", LinkTargetClassifier.localPathOrNull("file:/Users/x/Foo.kt#L10-L20"))
        assertEquals("/Users/x/docs/01-core.md", LinkTargetClassifier.localPathOrNull("/Users/x/docs/01-core.md#L638"))
        assertEquals("C:\\repo\\Foo.kt", LinkTargetClassifier.localPathOrNull("C:\\repo\\Foo.kt#L3-L9"))
    }

    /** 剥完锚点后只剩空路径的资源（如 `file://#L1`）不能返回空串，按非本地链接处理。 */
    @Test
    fun emptyPathAfterStrippingFragmentReturnsNull() {
        assertNull(LinkTargetClassifier.localPathOrNull("file://#L1"))
    }

    @Test
    fun externalLinksReturnNull() {
        assertNull(LinkTargetClassifier.localPathOrNull("https://example.com/a"))
        assertNull(LinkTargetClassifier.localPathOrNull("http://localhost:8080/x"))
        assertNull(LinkTargetClassifier.localPathOrNull("mailto:a@b.c"))
        assertNull(LinkTargetClassifier.localPathOrNull(""))
        assertNull(LinkTargetClassifier.localPathOrNull("javascript:alert(1)"))
    }
}
