package xyz.mederi.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import xyz.mederi.infrastructure.koog.HistoryStoreChatHistoryProvider
import xyz.mederi.tools.estimateTokens

/**
 * 压缩规划器（纯函数模块）。
 *
 * 本模块不依赖 Koog 会话/LLM 调用，只根据 token 预算把一段消息列表规划成：
 * - [CompressionPlan.recentMessages]：保留原文的最近一段（按预算从尾部累积）；
 * - [CompressionPlan.olderBatches]：更早的消息，已按批预算切成若干批，供上层逐批压缩成小结；
 * - [CompressionPlan.droppedCount]：因单条超过硬上限而被丢弃的最旧消息条数。
 *
 * 由谁调用：上层压缩策略（MederiCompressionStrategy）拿到计划后，对 [CompressionPlan.olderBatches]
 * 逐批生成小结，再用 [combineBatchSummaries] 把多批小结合并成一条以 `TLDR:` 开头的总结。
 *
 * 为什么是纯函数：便于单测穷举边界（切批、截断、合并），并让压缩策略只关心编排、不关心预算算术。
 */

/**
 * 单批旧消息预算占 contextWindow 的比例。
 *
 * 一批旧消息压缩成一次 LLM 小结调用，批太大就单次请求过载；取窗口的一半作为单批上限，
 * 保证批内消息能整体塞进一次请求。
 */
const val COMPRESS_BATCH_BUDGET_RATIO = 0.5

/**
 * 保留最近原文的预算比例。
 *
 * 越近的消息越可能有未结清的上下文，按窗口的 30% 保留原文，剩下的预算留给系统提示与
 * 新发生的对话。
 */
const val RECENT_KEEP_BUDGET_RATIO = 0.3

/**
 * 单条消息硬上限比例。
 *
 * 单条超过该比例的消息即便单独发送也会挤爆窗口，无法作为请求的一部分送出，只能从最旧侧丢弃。
 */
const val COMPRESS_HARD_CAP_RATIO = 0.9

/**
 * contextWindow 缺失时的防御性兜底窗口。
 *
 * 正常路径 contextWindow 一定由调用方传入；仅当模型元数据缺失时用它兜底，避免除零/负数预算。
 * 实际压缩只在 contextWindow 非空时启用。
 */
const val DEFAULT_COMPRESS_WINDOW_TOKENS = 128_000

/**
 * 复用 [HistoryStoreChatHistoryProvider] 的 TLDR 前缀定义（唯一真理源，避免字符串重复）。
 *
 * 该前缀是压缩总结与系统识别的约定：总结首行必须是它，HistoryStoreChatHistoryProvider
 * 据此把总结标记为 SUMMARY。
 */
private val TLDR_PREFIX: String = HistoryStoreChatHistoryProvider.TLDR_PREFIX

/**
 * 压缩规划结果。
 *
 * @property recentMessages 保留原文的最近一段消息（未被压缩）。
 * @property olderBatches 待逐批压缩的更早消息，已按批预算切好；每个元素是条数 ≥ 1 的一批（保持原顺序）。
 * @property droppedCount 因单条消息超过硬上限而被丢弃的最旧消息条数。
 */
data class CompressionPlan(
    val recentMessages: List<Message>,
    val olderBatches: List<List<Message>>,
    val droppedCount: Int
)

/**
 * 按 token 预算规划压缩：切分「保留原文的最近段」与「待压缩的多批更早消息」，并丢弃无法发送的超大单条。
 *
 * @param messages 完整消息列表（可含 system；system 不参与压缩，由调用方单独保留）。
 * @param contextWindow 模型上下文窗口 token 数；为 null 时用 [DEFAULT_COMPRESS_WINDOW_TOKENS] 兜底。
 */
fun planCompression(messages: List<Message>, contextWindow: Int?): CompressionPlan {
    // a. 预算窗口：contextWindow 缺失时用防御性兜底窗口。
    val window = contextWindow ?: DEFAULT_COMPRESS_WINDOW_TOKENS

    // b. 由窗口派生三档预算：保留段 / 单批 / 单条硬上限。
    val recentBudget = (window * RECENT_KEEP_BUDGET_RATIO).toInt()
    val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt()
    val hardCap = (window * COMPRESS_HARD_CAP_RATIO).toInt()

    // c. system 消息不参与压缩（它是 Prompt 配置的一部分，由调用方单独保留）。
    val nonSystem = messages.filter { it !is Message.System }

    // d. 从尾部累积「保留段」：acc>0 保证至少保留最后 1 条；
    //    单条自身就超过 recentBudget 时也只保留它自己，不会退化成空 recent。
    var start = nonSystem.size
    var acc = 0
    while (start > 0) {
        val t = estimateTokens(listOf(nonSystem[start - 1]))
        if (acc > 0 && acc + t > recentBudget) break
        acc += t
        start--
    }

    // e. 工具配对守卫：recent 首条不能是孤立的 tool result，否则连同其前面的 tool call 一起留在 recent。
    //    注意：Koog 中 tool result 是 User 消息里的 part、tool call 是 Assistant 消息里的 part，
    //    并非独立的「消息类型」，因此这里检查消息的 parts 而非消息本身。
    while (start > 0 && nonSystem[start].parts.any { it is MessagePart.Tool.Result }) {
        start--
    }

    // f. 按边界切分：recent = 尾段，older = 更早的部分。
    val recent = nonSystem.subList(start, nonSystem.size)
    val older = nonSystem.subList(0, start)

    // g. 对 older 保持原顺序按 batchBudget 贪心切批；单条超过硬上限的从最旧侧丢弃。
    //    单条估计值落在 (batchBudget, hardCap] 的消息单独成一批（不丢弃）——它仍小于窗口，可单独发送。
    val batches = mutableListOf<List<Message>>()
    var cur = mutableListOf<Message>()
    var curTokens = 0
    var dropped = 0
    for (msg in older) {
        val t = estimateTokens(listOf(msg))
        if (t > hardCap) {
            dropped++
            continue
        }
        if (cur.isNotEmpty() && curTokens + t > batchBudget) {
            batches.add(cur)
            cur = mutableListOf()
            curTokens = 0
        }
        cur.add(msg)
        curTokens += t
    }
    if (cur.isNotEmpty()) batches.add(cur)

    // h.
    return CompressionPlan(recent, batches, dropped)
}

/**
 * 把多批小结文本合并成一条总结。
 *
 * 结果首行必须是 `TLDR:`——[HistoryStoreChatHistoryProvider] 据此识别 SUMMARY 标记：
 * - 空列表 → `""`；
 * - 单条：已以 `TLDR:` 开头则原样返回（trim 后），否则补上 `TLDR:` 首行；
 * - 多条：各批小结用 `---` 分隔，正文内原有的 `TLDR:` 前缀被剥掉，避免出现多条 `TLDR:` 行。
 */
fun combineBatchSummaries(summaries: List<String>): String {
    if (summaries.isEmpty()) return ""
    if (summaries.size == 1) {
        val only = summaries.first().trim()
        return if (only.startsWith(TLDR_PREFIX)) only else "$TLDR_PREFIX\n$only"
    }
    val body = summaries.map { it.trim().removePrefix(TLDR_PREFIX).trim() }
        .joinToString("\n\n---\n\n")
    return "$TLDR_PREFIX\n$body"
}
