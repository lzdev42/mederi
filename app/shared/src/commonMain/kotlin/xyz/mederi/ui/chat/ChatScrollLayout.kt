package xyz.mederi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 底部锚定的消息视口。领域列表仍按时间正序传入，仅在布局层反向投影。
 *
 * index 0 是身份固定的底部留白：贴底时内容增高/展开、新步骤插入、输入区变高，
 * LazyColumn 在本次测量中就会从底边向上布局，不需要布局后的 scrollToItem 追赶。
 * 用户上翻后则由消息 key 保留阅读锚点。短会话保持顶部对齐，不额外撑高推理块。
 */
@Composable
internal fun <T> ChatScrollLayout(
    items: List<T>,
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    itemKey: (T) -> Any,
    content: @Composable (index: Int, item: T) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ChatLayout.itemSpacing, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 固定锚点不可随最后一条消息的 ID 改变，否则追加消息会保留旧消息而离开底部。
        item(key = "chat_bottom_anchor") { Spacer(Modifier.height(12.dp)) }
        itemsIndexed(items.asReversed(), key = { _, item -> itemKey(item) }) { index, item ->
            content(items.lastIndex - index, item)
        }
    }
}
