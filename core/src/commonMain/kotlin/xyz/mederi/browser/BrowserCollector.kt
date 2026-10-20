package xyz.mederi.browser

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 浏览器自动化过程中采集到的单条结构化信息。
 * 用于多网站、多页面信息检索时暂存数据，解耦对话上下文，避免 Token 膨胀。
 *
 * @param url 来源网页 URL
 * @param siteName 网站名 / 网页标题
 * @param content 具体内容（Markdown 格式，可包含图片链接 ![alt](img_url)）
 * @param timestamp 采集时间戳（ISO-8601）
 */
@Serializable
data class BrowserCollectedItem(
    val url: String,
    val siteName: String,
    val content: String,
    val timestamp: String = Instant.now().toString()
)

/**
 * 浏览器任务的临时信息存储器（Scratchpad / Collector）。
 *
 * 线程安全，生命周期跟随单个浏览器任务（taskId）。
 * 操作员在检索过程中调用 collect_info 写入，Brain 和主代理在判定与最终汇报时读取。
 */
class BrowserCollector(
    initialItems: List<BrowserCollectedItem> = emptyList()
) {
    private val items = CopyOnWriteArrayList<BrowserCollectedItem>(initialItems)

    /**
     * 添加一条采集项，返回当前采集项总数。
     */
    fun add(item: BrowserCollectedItem): Int {
        items.add(item)
        return items.size
    }

    /**
     * 获取所有已采集项的快照列表。
     */
    fun list(): List<BrowserCollectedItem> = items.toList()

    /**
     * 当前已采集条目数。
     */
    fun size(): Int = items.size

    /**
     * 清空存储器。
     */
    fun clear() {
        items.clear()
    }

    /**
     * 将所有采集内容格式化为 Markdown 汇总块。
     */
    fun toMarkdown(): String = buildString {
        if (items.isEmpty()) {
            append("(无采集数据)")
            return@buildString
        }
        items.forEachIndexed { index, item ->
            append("### [${index + 1}] ${item.siteName.ifBlank { "Untitled" }}\n")
            if (item.url.isNotBlank()) {
                append("- 来源: ${item.url}\n\n")
            }
            append("${item.content.trim()}\n\n")
        }
    }
}
