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
 * 3. http/https/mailto 等一律 null（交给系统浏览器）。
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

    @Test
    fun externalLinksReturnNull() {
        assertNull(LinkTargetClassifier.localPathOrNull("https://example.com/a"))
        assertNull(LinkTargetClassifier.localPathOrNull("http://localhost:8080/x"))
        assertNull(LinkTargetClassifier.localPathOrNull("mailto:a@b.c"))
        assertNull(LinkTargetClassifier.localPathOrNull(""))
        assertNull(LinkTargetClassifier.localPathOrNull("javascript:alert(1)"))
    }
}
