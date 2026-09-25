package xyz.emuci.diff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.emuci.diff.model.DiffLine
import xyz.emuci.diff.syntax.DiffSyntaxHighlighter
import xyz.emuci.syntax.ast.TokenType
import xyz.emuci.syntax.theme.CodeTheme

private val GUTTER_ACTION_BLUE = Color(0xFF007ACC)
private val GUTTER_TEXT_MUTED = Color(0xFF6B7280)

/**
 * 差异单行视图组件。
 * 严格按照高清晰度规范：双列等宽右对齐行号、软折行行号顶部对齐且不重复、整行增删半透明底色。
 */
@Composable
fun DiffLineRow(
    diffLine: DiffLine,
    language: String?,
    theme: CodeTheme,
    modifier: Modifier = Modifier,
    onGutterActionClick: ((lineNo: Int, isNew: Boolean) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val oldLineStr = when (diffLine) {
        is DiffLine.Unchanged -> diffLine.oldLineNo.toString()
        is DiffLine.Deleted -> diffLine.oldLineNo.toString()
        is DiffLine.Added -> ""
    }

    val newLineStr = when (diffLine) {
        is DiffLine.Unchanged -> diffLine.newLineNo.toString()
        is DiffLine.Added -> diffLine.newLineNo.toString()
        is DiffLine.Deleted -> ""
    }

    val lineBgColor = when (diffLine) {
        is DiffLine.Added -> theme.diffAddedLineBackground
        is DiffLine.Deleted -> theme.diffRemovedLineBackground
        is DiffLine.Unchanged -> Color.Transparent
    }

    val lineNumberStyle = remember(theme) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = GUTTER_TEXT_MUTED,
            textAlign = TextAlign.End,
        )
    }

    val codeTextStyle = remember(theme) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = theme.colorFor(TokenType.PLAIN),
        )
    }

    val highlightedContent = remember(diffLine.content, language, theme) {
        DiffSyntaxHighlighter.highlightLine(diffLine.content, language, theme)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(lineBgColor)
            .hoverable(interactionSource)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 1. Gutter 行号悬停动作插槽 (蓝色加号)
        Box(
            modifier = Modifier
                .width(22.dp)
                .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isHovered && onGutterActionClick != null) {
                val actionLineNo = when (diffLine) {
                    is DiffLine.Added -> diffLine.newLineNo to true
                    is DiffLine.Deleted -> diffLine.oldLineNo to false
                    is DiffLine.Unchanged -> diffLine.newLineNo to true
                }
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(GUTTER_ACTION_BLUE)
                        .clickable { onGutterActionClick(actionLineNo.first, actionLineNo.second) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "+",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    )
                }
            }
        }

        // 2. 左列旧行号 (固定 42.dp，右对齐，顶部对齐)
        Box(
            modifier = Modifier
                .width(42.dp)
                .padding(end = 4.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            BasicText(
                text = oldLineStr,
                style = lineNumberStyle,
                maxLines = 1,
            )
        }

        // 3. 右列新行号 (固定 42.dp，右对齐，顶部对齐)
        Box(
            modifier = Modifier
                .width(42.dp)
                .padding(end = 12.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            BasicText(
                text = newLineStr,
                style = lineNumberStyle,
                maxLines = 1,
            )
        }

        // 4. 代码内容区 (支持软折行，整行高亮，不重复输出行号)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            BasicText(
                text = highlightedContent,
                style = codeTextStyle,
            )
        }
    }
}
