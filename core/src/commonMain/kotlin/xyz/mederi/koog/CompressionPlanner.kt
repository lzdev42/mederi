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
 * 手动压缩的绝对最小可压缩尺寸（token）。
 *
 * 用户主动点「压缩」时，唯一能当场回答的问题是「这次到底值不值花一次 LLM」。
 * 低于这个量级时，压缩产出的 TLDR 本身可能就和原文一样长，压缩只会白白丢掉细节。
 * 该门槛是**绝对值**（与 contextWindow 无关）：小的对话不管窗口多大都不值得压，
 * 大的对话就算窗口很大也值得压——这与自动压缩按窗口比例触发的逻辑刻意不同。
 */
const val MIN_COMPRESSIBLE_TOKENS = 16_000

/**
 * 手动压缩时保留原文的最近消息条数（"最后两三句"）。
 *
 * 手动压缩的语义是「把长篇历史收成一段总结，但最近这几轮别动」：
 * recent = 最后 K 条原文，其余全部进 [CompressionPlan.olderBatches] 走压缩。
 */
const val MANUAL_KEEP_LAST_MESSAGES = 3

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
 * 两种切分模式：
 * - [keepLastMessages] 为 null（默认，自动压缩路径）：从尾部按 token 预算（[RECENT_KEEP_BUDGET_RATIO]）
 *   累积保留段，装满即止——保留多少由窗口大小决定。
 * - [keepLastMessages] 非 null（手动压缩路径）：**按条数**切分，recent = 最后 K 条原文，
 *   其余消息**全部**进 olderBatches 走压缩（不设 token 保留预算）——保留多少由用户意图决定。
 *
 * 两种模式共用工具配对守卫、older 分批与硬上限丢弃逻辑，只有「start 怎么算」不同。
 *
 * @param messages 完整消息列表（可含 system；system 不参与压缩，由调用方单独保留）。
 * @param contextWindow 模型上下文窗口 token 数；为 null 时用 [DEFAULT_COMPRESS_WINDOW_TOKENS] 兜底。
 * @param keepLastMessages 手动压缩模式：保留原文的最近消息条数；null 表示按 token 预算自动决定（见上）。
 */
fun planCompression(
    messages: List<Message>,
    contextWindow: Int?,
    keepLastMessages: Int? = null
): CompressionPlan {
    // a. 预算窗口：contextWindow 缺失时用防御性兜底窗口。
    val window = contextWindow ?: DEFAULT_COMPRESS_WINDOW_TOKENS

    // b. 由窗口派生三档预算：保留段 / 单批 / 单条硬上限。
    val recentBudget = (window * RECENT_KEEP_BUDGET_RATIO).toInt()
    val batchBudget = (window * COMPRESS_BATCH_BUDGET_RATIO).toInt()
    val hardCap = (window * COMPRESS_HARD_CAP_RATIO).toInt()

    // c. system 消息不参与压缩（它是 Prompt 配置的一部分，由调用方单独保留）。
    val nonSystem = messages.filter { it !is Message.System }

    // d. 计算保留段起点 start：两种模式在此分叉。
    //    d1. keepLastMessages != null（手动压缩）：按条数切，recent = 最后 K 条原文，其余全压。
    //    d2. keepLastMessages == null（自动压缩）：从尾部累积「保留段」，acc>0 保证至少保留最后 1 条；
    //        单条自身就超过 recentBudget 时也只保留它自己，不会退化成空 recent。
    var start: Int
    if (keepLastMessages != null) {
        start = (nonSystem.size - keepLastMessages).coerceAtLeast(0)
    } else {
        start = nonSystem.size
        var acc = 0
        while (start > 0) {
            val t = estimateTokens(listOf(nonSystem[start - 1]))
            if (acc > 0 && acc + t > recentBudget) break
            acc += t
            start--
        }
    }

    // e. 工具配对守卫：recent 首条不能是孤立的 tool result，否则连同其前面的 tool call 一起留在 recent。
    //    两种切分模式共用（放在 start 计算之后）：按条数切分同样可能把 tool result 切在 recent 首条。
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
 * 判定「这次压缩是否注定空转」，即 [planCompression] 的结果里根本没有值得花一次 LLM 的旧消息。
 *
 * 判定与 `MederiCompressionStrategy.compress` 里的两条早退判定字面同源
 * （`olderBatches` 为空 → 没有可压缩的旧消息；旧消息总条数 < 2 → 不值得花一次 LLM），
 * 这里只是把它抽成纯函数，供调用方在真正发起压缩**之前**预检：
 * - 返回 `null` → 值得压，正常继续；
 * - 返回 `"low_tokens"` → 消息量太小，全落在保留段里，没有可压缩的旧消息；
 * - 返回 `"too_few"` → 可压缩的旧消息只有 1 条，压缩收益不值得一次 LLM 调用。
 *
 * 为什么与策略共用 [planCompression]：预检和实际压缩必须对「什么是可压缩内容」有同一个答案，
 * 否则预检放行而策略空转（或反之）。两者都走同一个规划器，改预算阈值（[RECENT_KEEP_BUDGET_RATIO] 等）
 * 两处自动一致。
 *
 * @param messages 准备送入压缩的消息列表（应与实际压缩所见一致，含 system）。
 * @param contextWindow 模型上下文窗口 token 数；为 null 时按 [DEFAULT_COMPRESS_WINDOW_TOKENS] 兜底。
 */
fun compactionSkipReason(messages: List<Message>, contextWindow: Int?): String? {
    val plan = planCompression(messages, contextWindow)
    // 没有可压缩的旧消息（都落在保留段）→ 空转
    if (plan.olderBatches.isEmpty()) return "low_tokens"
    // 旧消息太少不值得花一次 LLM（与策略同阈值：< 2 条不压）
    if (plan.olderBatches.sumOf { it.size } < 2) return "too_few"
    return null
}

/**
 * 判定「用户手动点的这次压缩是否注定空转」，供 UI 在**发起之前**预检、给出可解释的反馈。
 *
 * 与 [compactionSkipReason] 的区别：自动压缩按窗口比例触发，剩余量本身就是收益；手动压缩是
 * 用户主动行为，唯一能当场回答的问题是「这次到底值不值花一次 LLM」。因此这里加一条与窗口无关的
 * **绝对门槛** [MIN_COMPRESSIBLE_TOKENS]：总量高于它才可能值得压（"高于 16k 即可压"），
 * 低于它一律判 `low_tokens`，不进入按条数切分的规划。
 *
 * `too_few` 守卫的由来：压缩本身要花一次 LLM 产出 TLDR，若可压缩的旧内容只剩 1 条，
 * 生成的总结极可能比那一条原文还长——压完上下文反而变大，用户会认为"压缩把内容搞坏了"。
 * 与自动路径的 < 2 条守卫同源，只是手动模式更容易撞上（按条数切分不看 token 预算）。
 *
 * 与手动策略共用 [planCompression]：预检必须和实际压缩对「什么是可压缩内容」有同一个答案
 * （同一 [MANUAL_KEEP_LAST_MESSAGES] 口径），否则预检放行而策略空转（或反之）。
 *
 * @param messages 准备送入压缩的消息列表（应与实际压缩所见一致，含 system）。
 * @param contextWindow 模型上下文窗口 token 数；为 null 时按 [DEFAULT_COMPRESS_WINDOW_TOKENS] 兜底。
 * @return `null` → 值得压；`"low_tokens"` → 总量不足 [MIN_COMPRESSIBLE_TOKENS] 或没有可压缩的旧消息；
 *         `"too_few"` → 可压缩的旧消息只有 1 条，不值得一次 LLM。
 */
fun manualCompactionSkipReason(messages: List<Message>, contextWindow: Int?): String? {
    // 绝对门槛先判：与窗口无关的小对话（哪怕窗口巨大）压了也是负收益。
    if (estimateTokens(messages) < MIN_COMPRESSIBLE_TOKENS) return "low_tokens"
    // 手动模式口径：recent = 最后 K 条原文，其余全压。
    val plan = planCompression(messages, contextWindow, keepLastMessages = MANUAL_KEEP_LAST_MESSAGES)
    // 消息条数不足以切出任何 older 段（全被 K 条 recent 吃掉了）→ 空转
    if (plan.olderBatches.isEmpty()) return "low_tokens"
    // 可压缩的旧消息只有 1 条 → 压完反而可能更大，不值得花一次 LLM
    if (plan.olderBatches.sumOf { it.size } < 2) return "too_few"
    return null
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
