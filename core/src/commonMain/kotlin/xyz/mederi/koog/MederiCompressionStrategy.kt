package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import xyz.mederi.koog.CompressionPlan
import xyz.mederi.koog.combineBatchSummaries
import xyz.mederi.koog.planCompression

/**
 * Mederi 自定义历史压缩策略（token 预算驱动）。
 *
 * 与旧实现（"按条数保留最近 30%"）不同，本策略按模型上下文窗口的 token 预算规划：
 * - 保留段：保留最近原文，预算 ≤ 窗口 × 0.3（至少 1 条），不再固定条数；
 * - 旧消息：更早的消息按窗口 × 0.5 的批预算切成若干批，逐批 LLM 压缩成小结，
 *   避免把"更早的旧消息"一次性整发导致单次请求过载；
 * - 硬上限：单条超过窗口 × 0.9 的消息即便单独发送也会挤爆窗口，从最旧侧丢弃；
 * - 多批小结合并为一条以 `TLDR:` 开头的总结（首行保持 `TLDR:`，维持
 *   HistoryStoreChatHistoryProvider 的单 SUMMARY 标记语义）。
 *
 * 与 Koog 的 FromLastNMessages 不同：
 * - FromLastNMessages：压缩最近 N 条，丢弃更早的
 * - MederiCompressionStrategy：保留最近若干原文，压缩更早的并生成带记忆的总结
 *
 * @param contextWindow 模型上下文窗口 token 数；为 null 时由规划器用防御性兜底窗口
 *   （默认窗口按条数不可知时也能工作）。既有 `MederiCompressionStrategy()` 调用点仍可编译。
 * @param keepLastMessages 两种压缩模式的唯一开关：
 *   - **非 null = 手动压缩模式**：recent = 最后 K 条原文，其余消息全部压成 TLDR，不看 token 预算
 *     （保留多少由用户意图决定——"最后两三句别动"）。
 *   - **null = 自动压缩模式**（默认）：token 预算驱动，保留段 ≤ 窗口 × `RECENT_KEEP_BUDGET_RATIO`。
 *
 *   自动路径（TurnExecutor.buildTurnAgent）不传该参数，行为与本参数引入前完全一致；
 *   手动路径由用户点「压缩」时以 `MANUAL_KEEP_LAST_MESSAGES` 构造本类。
 */
class MederiCompressionStrategy(
    private val contextWindow: Int? = null,
    private val keepLastMessages: Int? = null
) : HistoryCompressionStrategy() {

    /**
     * 供单测验证 contextWindow / keepLastMessages 是否正确流入规划（构造函数 → 规划器接线点）。
     */
    internal fun planFor(messages: List<Message>): CompressionPlan =
        planCompression(messages, contextWindow, keepLastMessages)

    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        // 压缩源 = prompt.messages（ChatMemory preprocessor 在 compress 节点执行前
        // 把 load 的历史装进了 prompt）。memoryMessages 参数在此 Koog 版本里实测为空，
        // 不可用作压缩源。
        val originalMessages = llmSession.prompt.messages
        // 保留 system 消息（它们不参与压缩）
        val systemMessages = originalMessages.filter { it is Message.System }

        // 按 token 预算规划：保留段 + 旧消息多批 + 丢弃计数
        val plan = planFor(originalMessages)

        // 没有可压缩的旧消息（都落在保留段）→ no-op
        if (plan.olderBatches.isEmpty()) return
        // 旧消息太少不值得花一次 LLM（沿用旧行为：< 2 条不压）
        if (plan.olderBatches.sumOf { it.size } < 2) return

        // 逐批压缩：每批把 prompt 换成该批 + 追加 SUMMARY_PROMPT，调 LLM 取文本
        val summaries = plan.olderBatches.map { batch ->
            llmSession.prompt = llmSession.prompt.withMessages { _ -> batch }
            llmSession.appendPrompt { user(SUMMARY_PROMPT) }
            llmSession.requestLLMWithoutTools().textContent()
        }

        // 多批小结合并为一条（保证首行以 TLDR: 开头）
        val tldrText = combineBatchSummaries(summaries)
        val tldrMessage = Message.Assistant(tldrText, ResponseMetaInfo.Empty)

        // 最终 = system + TLDR + 保留的最近原文
        val compressedMessages = buildList {
            addAll(systemMessages)
            add(tldrMessage)
            addAll(plan.recentMessages)
        }
        llmSession.prompt = llmSession.prompt.withMessages { _ -> compressedMessages }
    }

    companion object {
        val SUMMARY_PROMPT = """
直接总结以上对话，不要任何开场白、客套语或说明性文字，直接进入正文。

格式要求：
1. 第一行必须是 `TLDR:` 开头的一句话总览，总览后直接进入正文，不要空行铺垫或过渡句。
2. 正文包含以下五个部分，各部分之间用 `---` 分割线隔开：

## 关键决策
列出所有已做出的重要决定及其理由。包括技术选型、方案选择、用户偏好等。

---

## 用户讨论情况
记录用户的偏好、要求、反馈和约束条件。

---

## 未完成讨论
列出尚未解决的问题、待确认的事项、悬而未决的问题点及原因。这部分最重要，确保不遗漏任何待办事项。

---

## 当前阶段
说明讨论/任务进行到什么阶段，下一步是什么。

---

## 关键记忆
列出不能丢失的硬事实：代码结构、文件路径、技术约束、API 接口、数据结构等。

3. 每个部分用标题 + 简洁列表输出，信息完整、便于检索，排版清晰美观。
        """.trimIndent()
    }
}
