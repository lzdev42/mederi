package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.prompt.message.Message

/**
 * Mederi 自定义历史压缩策略。
 *
 * 保留最近 30% 消息原文，将更早的消息用 LLM 总结成带"记忆"的 TLDR。
 * TLDR 包含：关键决策、用户讨论情况、未完成讨论、当前阶段、关键记忆。
 *
 * 与 Koog 的 FromLastNMessages 不同：
 * - FromLastNMessages：压缩最近 N 条，丢弃更早的
 * - MederiCompressionStrategy：保留最近 N 条原文，压缩更早的并生成带记忆的总结
 */
class MederiCompressionStrategy : HistoryCompressionStrategy() {

    override suspend fun compress(
        llmSession: AIAgentLLMWriteSession,
        memoryMessages: List<Message>
    ) {
        // 压缩源 = prompt.messages（ChatMemory preprocessor 在 compress 节点执行前
        // 把 load 的历史装进了 prompt）。memoryMessages 参数在此 Koog 版本里实测为空，
        // 不可用作压缩源。
        val originalMessages = llmSession.prompt.messages

        // 计算 n：保留约 30% 的消息，最少 5 条
        val n = (originalMessages.size * 0.3).toInt().coerceAtLeast(5)

        // 拆分：olderMessages 要压缩，recentMessages 保留原文
        val splitIndex = (originalMessages.size - n).coerceAtLeast(0)
        val olderMessages = originalMessages.take(splitIndex)
        val recentMessages = originalMessages.drop(splitIndex)

        // 没有旧消息可压缩，直接返回
        if (olderMessages.isEmpty()) return

        // 保留 system 消息（它们不参与压缩）
        val systemMessages = originalMessages.filter { it is Message.System }
        val olderNonSystem = olderMessages.filter { it !is Message.System }

        if (olderNonSystem.isEmpty()) return

        // 太少不值得压缩（olderNonSystem < 2）→ no-op。
        // 注意：历史条数不足时 compress 会静默返回——这是设计如此（不值得为 1-2 条消息
        // 花一次 LLM 调用）。测试需 ≥ 3 轮问答（≥ 8 条非 system 消息 → n=5 → older≥3）才能触发压缩。
        if (olderNonSystem.size < 2) return

        // 将 prompt 设置为只有旧消息（去掉 system），用于 LLM 压缩
        llmSession.prompt = llmSession.prompt.withMessages { _ -> olderNonSystem }

        // 添加自定义总结 prompt，让 LLM 生成带记忆的 TLDR
        llmSession.appendPrompt {
            user(SUMMARY_PROMPT)
        }

        // 调用 LLM 生成总结（不带工具，避免工具调用）
        val tldrMessage = llmSession.requestLLMWithoutTools()

        // 组合最终消息列表：system + TLDR + 最近 N 条原文
        val compressedMessages = buildList {
            addAll(systemMessages)
            add(tldrMessage)
            addAll(recentMessages.filter { it !is Message.System })
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
