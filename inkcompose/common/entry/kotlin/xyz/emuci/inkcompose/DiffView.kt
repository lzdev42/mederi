package xyz.emuci.inkcompose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.diff.algorithm.MyersDiffAlgorithm
import xyz.emuci.diff.model.DiffDisplayItem
import xyz.emuci.diff.model.DiffFile
import xyz.emuci.diff.parser.UnifiedDiffParser
import xyz.emuci.diff.ui.DiffFileHeader
import xyz.emuci.diff.ui.DiffHunkExpander
import xyz.emuci.diff.ui.DiffLineRow
import xyz.emuci.syntax.theme.CodeTheme
import xyz.emuci.syntax.theme.LocalCodeTheme

/**
 * 核心差异查看器组件（纯数据驱动入口，绝对解耦）。
 *
 * @param diff 单个文件的结构化差异模型
 * @param modifier Compose Modifier
 * @param codeTheme 代码高亮主题，null 则使用 [LocalCodeTheme.current]
 * @param showHeader 是否在顶部渲染文件名与增删统计卡片
 * @param onGutterActionClick 行号左侧悬停动作（如添加评论）点击回调
 */
@Composable
fun DiffView(
    diff: DiffFile,
    modifier: Modifier = Modifier,
    codeTheme: CodeTheme? = null,
    showHeader: Boolean = true,
    onGutterActionClick: ((lineNo: Int, isNew: Boolean) -> Unit)? = null,
) {
    val effectiveTheme = codeTheme ?: LocalCodeTheme.current
    var isExpanded by remember(diff) { mutableStateOf(true) }
    val displayItems = remember(diff) { buildDisplayItems(diff) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(effectiveTheme.background)
    ) {
        if (showHeader) {
            DiffFileHeader(
                diffFile = diff,
                isExpanded = isExpanded,
                onToggleExpand = { isExpanded = !isExpanded },
            )
        }

        if (isExpanded) {
            if (displayItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.foundation.text.BasicText(
                        text = "无内容差异 (No changes)",
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFF6B7280),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                items(
                    items = displayItems,
                    key = { it.key }
                ) { item ->
                    when (item) {
                        is DiffDisplayItem.CodeLine -> {
                            DiffLineRow(
                                diffLine = item.line,
                                language = diff.language,
                                theme = effectiveTheme,
                                onGutterActionClick = onGutterActionClick,
                            )
                        }

                        is DiffDisplayItem.Collapsed -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // 向上展开的行
                                for (line in item.getExpandedTopLines()) {
                                    DiffLineRow(
                                        diffLine = line,
                                        language = diff.language,
                                        theme = effectiveTheme,
                                        onGutterActionClick = onGutterActionClick,
                                    )
                                }

                                // 折叠胶囊与展开控制器
                                if (item.remainingCount > 0) {
                                    DiffHunkExpander(collapsed = item)
                                }

                                // 向下展开的行
                                for (line in item.getExpandedBottomLines()) {
                                    DiffLineRow(
                                        diffLine = line,
                                        language = diff.language,
                                        theme = effectiveTheme,
                                        onGutterActionClick = onGutterActionClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * 标准 Git Patch / Unified Diff 格式文本输入入口。
 *
 * @param patch 标准 git diff 或 unified diff 字符串
 * @param modifier Compose Modifier
 * @param language 显式指定语言（可选，若提供则优先于后缀推断）
 * @param codeTheme 代码主题
 * @param showHeader 是否展示文件 Header
 * @param onGutterActionClick 行号悬停动作回调
 */
@Composable
fun DiffView(
    patch: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    codeTheme: CodeTheme? = null,
    showHeader: Boolean = true,
    onGutterActionClick: ((lineNo: Int, isNew: Boolean) -> Unit)? = null,
) {
    val diffFiles = remember(patch) { UnifiedDiffParser.parse(patch) }
    val primaryFile = diffFiles.firstOrNull()

    if (primaryFile == null) {
        Box(modifier = modifier)
        return
    }

    val finalDiff = if (language != null) primaryFile.copy(language = language) else primaryFile
    DiffView(
        diff = finalDiff,
        modifier = modifier,
        codeTheme = codeTheme,
        showHeader = showHeader,
        onGutterActionClick = onGutterActionClick,
    )
}

/**
 * 两个文本对比输入入口。
 *
 * @param oldText 原始文本
 * @param newText 变更后的文本
 * @param modifier Compose Modifier
 * @param language 代码语言（如 "kotlin", "xml", "json" 等）
 * @param filePath 文件路径（用于 Header 展示及自动推断语言）
 * @param contextLines 上下文行数，默认 3
 * @param codeTheme 代码主题
 * @param showHeader 是否展示文件 Header
 * @param onGutterActionClick 行号悬停动作回调
 */
@Composable
fun DiffView(
    oldText: String,
    newText: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    filePath: String? = null,
    contextLines: Int = 3,
    codeTheme: CodeTheme? = null,
    showHeader: Boolean = true,
    onGutterActionClick: ((lineNo: Int, isNew: Boolean) -> Unit)? = null,
) {
    val diff = remember(oldText, newText, contextLines, filePath, language) {
        val computed = MyersDiffAlgorithm.computeDiff(
            oldText = oldText,
            newText = newText,
            contextRadius = contextLines,
            filePath = filePath
        )
        if (language != null) computed.copy(language = language) else computed
    }

    DiffView(
        diff = diff,
        modifier = modifier,
        codeTheme = codeTheme,
        showHeader = showHeader,
        onGutterActionClick = onGutterActionClick,
    )
}

/**
 * 多文件差异列表视图组件。
 */
@Composable
fun MultiFileDiffView(
    diffs: List<DiffFile>,
    modifier: Modifier = Modifier,
    codeTheme: CodeTheme? = null,
    onGutterActionClick: ((filePath: String, lineNo: Int, isNew: Boolean) -> Unit)? = null,
) {
    val effectiveTheme = codeTheme ?: LocalCodeTheme.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(effectiveTheme.background)
    ) {
        items(
            items = diffs,
            key = { it.displayPath }
        ) { diffFile ->
            DiffView(
                diff = diffFile,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                codeTheme = effectiveTheme,
                showHeader = true,
                onGutterActionClick = onGutterActionClick?.let { callback ->
                    { lineNo, isNew -> callback(diffFile.displayPath, lineNo, isNew) }
                },
            )
        }
    }
}

/**
 * 将 DiffFile 转换为 UI 呈现用的包含折叠区段的项列表。
 */
internal fun buildDisplayItems(diffFile: DiffFile): List<DiffDisplayItem> {
    val items = mutableListOf<DiffDisplayItem>()
    val hunks = diffFile.hunks
    if (hunks.isEmpty()) return emptyList()

    for (i in hunks.indices) {
        val hunk = hunks[i]
        // 检查首个 Hunk 前或 Hunk 之间的折叠区
        if (i == 0) {
            val gapOld = hunk.oldStart - 1
            if (gapOld > 0) {
                items.add(
                    DiffDisplayItem.Collapsed(
                        id = "gap-0",
                        oldStart = 1,
                        newStart = 1,
                        totalCount = gapOld,
                    )
                )
            }
        } else {
            val prevHunk = hunks[i - 1]
            val prevOldEnd = prevHunk.oldStart + prevHunk.oldCount
            val prevNewEnd = prevHunk.newStart + prevHunk.newCount
            val gapOld = hunk.oldStart - prevOldEnd
            if (gapOld > 0) {
                items.add(
                    DiffDisplayItem.Collapsed(
                        id = "gap-$i",
                        oldStart = prevOldEnd,
                        newStart = prevNewEnd,
                        totalCount = gapOld,
                    )
                )
            }
        }

        // 添加该 hunk 内的代码行
        for ((lineIdx, line) in hunk.lines.withIndex()) {
            items.add(
                DiffDisplayItem.CodeLine(
                    line = line,
                    key = "hunk-$i-line-$lineIdx"
                )
            )
        }
    }
    return items
}
