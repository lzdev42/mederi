package xyz.mederi.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.rp_documents_title
import org.jetbrains.compose.resources.stringResource
import xyz.mederi.theme.AppTheme
import xyz.mederi.ui.overview.OverviewDocumentsContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 概览"文档"行（2026-11）：会话文档入口——
 * - 空列表整行不渲染（同 实施计划行/待办行 语义：无内容不占位）
 * - 非空渲染全部文档标题；点击条目回调 onOpen（上层接 openDocument 打开文档面板）
 */
@OptIn(ExperimentalTestApi::class)
class OverviewDocumentsRowTest {

    private fun doc(id: String, title: String) = ChatListItem.DocumentCard(
        key = "k_$id",
        artifactId = id,
        title = title,
        content = "content of $title",
        lineCount = 10,
        charCount = 100,
        isCompleted = true,
        isStreaming = false,
    )

    @Test
    fun emptyDocumentsRendersNothing() = runComposeUiTest {
        var header = ""
        setContent {
            AppTheme {
                header = stringResource(Res.string.rp_documents_title)
                OverviewDocumentsContent(emptyList(), true, {}, {})
            }
        }
        runOnIdle { }
        onNodeWithText(header).assertDoesNotExist()
    }

    @Test
    fun documentsListedAndClickOpens() = runComposeUiTest {
        val docs = listOf(doc("a1", "存储层重构验收报告"), doc("a2", "ask_user 事故链根因报告"))
        var opened: ChatListItem.DocumentCard? = null
        setContent {
            AppTheme {
                OverviewDocumentsContent(docs, true, {}, onOpen = { opened = it })
            }
        }
        onNodeWithText("存储层重构验收报告").assertExists().performClick()
        runOnIdle {
            assertEquals("a1", opened?.artifactId)
            assertTrue(opened?.content == "content of 存储层重构验收报告")
        }
        opened = null
        onNodeWithText("ask_user 事故链根因报告").performClick()
        runOnIdle { assertEquals("a2", opened?.artifactId) }
    }
}
