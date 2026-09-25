package xyz.mederi.core.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mederi.core.contract.dto.ChatPromptInput
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.*
import xyz.mederi.currentTimeMillis
import kotlin.random.Random

object MockScenarios {

    suspend fun runScript(
        conversationId: String,
        input: ChatPromptInput,
        sf: MutableStateFlow<ConversationSnapshot>,
        idGen: MockIdGenerator,
        questionAnswers: MutableMap<String, CompletableDeferred<List<List<String>>?>>,
    ) {
        val scenario = when {
            conversationId == "conv_error" -> "A"
            Random.nextFloat() < 0.60 -> "A"
            Random.nextFloat() < 0.41 -> "B"
            else -> "C"
        }

        // 找到 MockAiCore 已创建的 streaming assistant message
        val assistantMsg = sf.value.messages.lastOrNull { it.role == ChatRole.Assistant && it.isStreaming }
            ?: return
        val assistantMsgId = assistantMsg.id

        when (scenario) {
            "A" -> runScenarioA(sf, idGen, assistantMsgId)
            "B" -> runScenarioB(sf, idGen, assistantMsgId)
            "C" -> runScenarioC(sf, idGen, assistantMsgId, questionAnswers)
        }

        // 完成：标记 isStreaming=false
        sf.value = sf.value.copy(
            messages = sf.value.messages.map { msg ->
                if (msg.id == assistantMsgId) msg.copy(isStreaming = false, completedAt = currentTimeMillis())
                else msg
            },
            conversation = sf.value.conversation.copy(status = ConversationStatus.Idle, updatedAt = currentTimeMillis()),
        )
    }

    private fun updateAssistant(sf: MutableStateFlow<ConversationSnapshot>, assistantMsgId: String, block: (ChatMessage) -> ChatMessage) {
        sf.value = sf.value.copy(
            messages = sf.value.messages.map { msg ->
                if (msg.id == assistantMsgId) block(msg) else msg
            }
        )
    }

    private suspend fun runScenarioA(sf: MutableStateFlow<ConversationSnapshot>, idGen: MockIdGenerator, assistantMsgId: String) {
        // 1. 流式 Reasoning
        var reasoning1 = ""
        val reasoning1Id = idGen.next()
        for (i in 1..30) {
            delay(Random.nextLong(20, 40))
            reasoning1 += "思考步骤 $i：分析用户请求的内容结构。"
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != reasoning1Id } + ChatBlock.Reasoning(reasoning1Id, reasoning1)
                msg.copy(blocks = newBlocks)
            }
        }

        // 2. Tool call
        val toolCallId = idGen.next()
        updateAssistant(sf, assistantMsgId) { it.copy(blocks = it.blocks + ChatBlock.ToolCall(toolCallId, "bash", ToolCallState.Pending)) }
        delay(300)
        updateAssistant(sf, assistantMsgId) { msg ->
            val newBlocks = msg.blocks.map { if (it.id == toolCallId) ChatBlock.ToolCall(toolCallId, "bash", ToolCallState.Running(mapOf("command" to "ls docs/"))) else it }
            msg.copy(blocks = newBlocks)
        }
        delay(400)
        updateAssistant(sf, assistantMsgId) { msg ->
            val newBlocks = msg.blocks.map { if (it.id == toolCallId) ChatBlock.ToolCall(toolCallId, "bash", ToolCallState.Completed(mapOf("command" to "ls docs/"), "01-design-principles.md\n02-architecture.md\n09-file-layout.md")) else it }
            msg.copy(blocks = newBlocks)
        }

        // 3. 第二段 Reasoning
        var reasoning2 = ""
        val reasoning2Id = idGen.next()
        for (i in 1..20) {
            delay(Random.nextLong(20, 40))
            reasoning2 += "结论 $i：需要重构对话引擎。"
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != reasoning2Id } + ChatBlock.Reasoning(reasoning2Id, reasoning2)
                msg.copy(blocks = newBlocks)
            }
        }

        // 4. 流式 Text
        var text = ""
        val textId = idGen.next()
        for (i in 1..50) {
            delay(Random.nextLong(30, 60))
            text += "第 $i 行输出内容：这是 AI 的回复。"
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != textId } + ChatBlock.Text(textId, text)
                msg.copy(blocks = newBlocks)
            }
        }

        // 5. Todo (50% 概率)
        if (Random.nextFloat() < 0.5f) {
            sf.value = sf.value.copy(
                todos = listOf(
                    TodoItem("t1", "列出当前 merge 逻辑的所有调用点", TodoStatus.Completed),
                    TodoItem("t2", "起草新 ConversationSnapshot 契约", TodoStatus.InProgress),
                    TodoItem("t3", "重写 WorkspaceViewModel", TodoStatus.Pending),
                )
            )
        }

        // 6. Token 增长
        val prev = sf.value.tokenUsage
        sf.value = sf.value.copy(
            tokenUsage = prev.copy(
                reasoning = prev.reasoning + 300,
                output = prev.output + 200,
                input = prev.input + 50,
            ),
            cost = sf.value.cost.copy(total = sf.value.cost.total + 0.001),
        )
    }

    private suspend fun runScenarioB(
        sf: MutableStateFlow<ConversationSnapshot>, idGen: MockIdGenerator, assistantMsgId: String,
    ) {
        // 1. 短 Reasoning
        var reasoning = ""
        val reasoningId = idGen.next()
        for (i in 1..15) {
            delay(Random.nextLong(20, 40))
            reasoning += "步骤 $i 推理中..."
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != reasoningId } + ChatBlock.Reasoning(reasoningId, reasoning)
                msg.copy(blocks = newBlocks)
            }
        }

        // 2. Tool call 执行演示
        val permToolId = idGen.next()
        updateAssistant(sf, assistantMsgId) { it.copy(blocks = it.blocks + ChatBlock.ToolCall(permToolId, "bash", ToolCallState.Pending)) }
        delay(400)
        updateAssistant(sf, assistantMsgId) { msg ->
            msg.copy(blocks = msg.blocks.map { if (it.id == permToolId) ChatBlock.ToolCall(permToolId, "bash", ToolCallState.Running(mapOf("command" to "rm -rf build/"))) else it })
        }
        delay(600)
        updateAssistant(sf, assistantMsgId) { msg ->
            msg.copy(blocks = msg.blocks.map { if (it.id == permToolId) ChatBlock.ToolCall(permToolId, "bash", ToolCallState.Completed(mapOf("command" to "rm -rf build/"), "Done")) else it })
        }

        // 3. 继续输出 Text
        delay(300)
        val textId = idGen.next()
        var text = ""
        for (i in 1..20) {
            delay(Random.nextLong(30, 60))
            text += "清理完成。$i "
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != textId } + ChatBlock.Text(textId, text)
                msg.copy(blocks = newBlocks)
            }
        }
    }

    private suspend fun runScenarioC(
        sf: MutableStateFlow<ConversationSnapshot>, idGen: MockIdGenerator, assistantMsgId: String,
        questionAnswers: MutableMap<String, CompletableDeferred<List<List<String>>?>>,
    ) {
        // 1. 短 Reasoning
        var reasoning = ""
        val reasoningId = idGen.next()
        for (i in 1..10) {
            delay(Random.nextLong(20, 40))
            reasoning += "分析中..."
            updateAssistant(sf, assistantMsgId) { msg ->
                val newBlocks = msg.blocks.filter { it.id != reasoningId } + ChatBlock.Reasoning(reasoningId, reasoning)
                msg.copy(blocks = newBlocks)
            }
        }

        // 2. Question 请求
        val reqId = idGen.next()
        sf.value = sf.value.copy(
            pendingQuestion = QuestionRequest(
                reqId, sf.value.conversation.id,
                listOf(QuestionRequest.Question("q1", "你想把 warning 改成 error 还是静默?", listOf("改error", "静默"), allowCustom = true))
            ),
        )

        // 3. 等待用户答复
        val deferred = CompletableDeferred<List<List<String>>?>()
        questionAnswers[reqId] = deferred
        val answers = withTimeoutOrNull(60_000L) { deferred.await() } ?: emptyList()

        // 4. 根据答复输出 Text
        sf.value = sf.value.copy(pendingQuestion = null)
        val textId = idGen.next()
        val replyText = if (answers.isEmpty()) "好的，不做修改" else "已按选择处理: ${answers.flatten().joinToString(", ")}"
        updateAssistant(sf, assistantMsgId) { msg ->
            msg.copy(blocks = msg.blocks + ChatBlock.Text(textId, replyText))
        }
    }
}
