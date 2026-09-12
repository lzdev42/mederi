package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.ext.agent.HistoryCompressionConfig
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.toList

/**
 * 把流式帧重建为 Assistant 消息，带 ToolCallComplete args 解析容错。
 *
 * Koog 的 toMessageResponse 对 ToolCallComplete.content 直接
 * `Json.parseToJsonElement(...)` 且无 try-catch——兼容端点返回截断/非法 JSON 参数时
 * 抛 SerializationException 杀死整个流（症状=正文到一半戛然而止）。
 * 这里对解析失败的调用降级为 args="{}" 的 Tool.Call，执行层会报
 * "invalid arguments" 给模型自纠，turn 不中断。
 */
/**
 * 合并被流式协议切碎的工具调用碎片。
 *
 * 部分端点（deepseek）把一次工具调用拆成多片：首片带 id+name，后续片 id/name 空、
 * 只有 args 增量（`{`、`"path": "calc.py"}`）。Koog StreamFrameFlowBuilder 是
 * "过渡即冲刷"语义（text/reasoning delta 与工具调用挤兑同一个 pending 槽），
 * 中间插着的 text delta 会把进行中的调用提前冲成 Complete——模型明明只调了
 * read_file 一次，重建出来却是 [Call(read_file,{}), Call({}), Call({})] 三个空壳，
 * 空壳进消息历史后下一轮模型调空工具、结果污染上下文（sess_9d484d21 实测）。
 *
 * 合并规则：相邻的 ToolCallComplete，若后片的 id/name 均空（纯 args 续片），
 * 并入前片（args 拼接、name 补缺）。id 非空 = 新调用，不合并。
 */
private fun List<StreamFrame>.mergeFragmentedToolCalls(): List<StreamFrame> {
    if (none { it is StreamFrame.ToolCallComplete }) return this
    val out = mutableListOf<StreamFrame>()
    // 上一个命名 ToolCallComplete 在 out 中的下标；续片（id/name 空）的 args 并入它。
    // TextDelta/TextComplete 可以穿插在续片之间（Koog builder 挤兑 pending 槽的副产品），
    // 所以不能只看 out.lastOrNull()——要跨非 TC 帧记住位置。
    var lastNamedCallIdx: Int? = null
    for (frame in this) {
        if (frame is StreamFrame.TextComplete && frame.text.isBlank()) continue  // 丢空 Text
        val isContinuation = frame is StreamFrame.ToolCallComplete &&
            frame.id.isNullOrBlank() && frame.name.isNullOrBlank() &&
            lastNamedCallIdx != null
        if (isContinuation) {
            val last = out[lastNamedCallIdx!!] as StreamFrame.ToolCallComplete
            out[lastNamedCallIdx] = last.copy(content = (last.content ?: "") + (frame.content ?: ""))
        } else {
            if (frame is StreamFrame.ToolCallComplete) lastNamedCallIdx = out.size
            else if (frame is StreamFrame.End) lastNamedCallIdx = null
            out.add(frame)
        }
    }
    return out
}

private fun List<StreamFrame>.toAssistantMessageSafe(): Message.Assistant {
    val start = System.nanoTime()
    val rebuilt = try {
        mergeFragmentedToolCalls().toMessageResponse()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        xyz.mederi.debug.DebugLog.error(
            "StreamRebuild",
            "toMessageResponse FAILED (${e::class.simpleName}: ${e.message}) — salvaging frames, toolCalls=" +
                "${filterIsInstance<StreamFrame.ToolCallComplete>().size}, textComplete=${filterIsInstance<StreamFrame.TextComplete>().size}",
            e
        )
        val salvaged = filterIsInstance<StreamFrame.TextComplete>()
            .map { MessagePart.Text(it.text) } +
            filterIsInstance<StreamFrame.ToolCallComplete>()
                .filter { frame ->
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(frame.content) }.isFailure
                }
                .map {
                    MessagePart.Tool.Call(id = it.id, tool = it.name ?: "", args = kotlinx.serialization.json.buildJsonObject { })
                }
        if (salvaged.isEmpty()) throw e
        Message.Assistant(
            parts = salvaged,
            finishReason = filterIsInstance<StreamFrame.End>().firstOrNull()?.finishReason,
            metaInfo = filterIsInstance<StreamFrame.End>().firstOrNull()?.metaInfo ?: ai.koog.prompt.message.ResponseMetaInfo.Empty
        )
    }
    val toolCalls = rebuilt.parts.filterIsInstance<MessagePart.Tool.Call>()
    xyz.mederi.debug.DebugLog.event(
        "StreamRebuild",
        "rebuilt assistant: parts=${rebuilt.parts.map { it::class.simpleName }}, " +
            "finishReason=${rebuilt.finishReason}, toolCalls=${toolCalls.map { "${it.tool}(${it.args.toString().take(80)})" }}, " +
            "tookMs=${(System.nanoTime() - start) / 1_000_000}"
    )
    return rebuilt
}

/**
 * 哨兵输入：用户消息已由 TurnExecutor 在 turn 启动前落库（durable-first），
 * ChatMemory load 时它会随历史恢复进 prompt。LLM 节点检测到哨兵时
 * 不再 append user()——否则 LLM 会收到重复的用户消息。
 */
const val MEDERI_INPUT_PERSISTED = "\u0000mederi_input_persisted\u0000"

/**
 * Mederi 自定义单轮 Agent 策略（流式版）。
 *
 * 与 Koog 的 [ai.koog.agents.core.agent.singleRunStrategy] 行为一致，区别有二：
 * 1. LLM 节点用 [ai.koog.prompt.streaming] 的流式请求（requestLLMStreaming）：
 *    - 逐帧消费产生 MESSAGE_DELTA 的输入，UI 实时可见；
 *    - 完整帧通过 [toAssistantMessageSafe] 重建 [Message.Assistant]，继续工具循环。
 * 2. ToolCallComplete args 解析容错：Koog 原版对非法 JSON 参数整条流直接炸
 *    （症状=正文播到一半戛然而止、无任何错误）。解析失败的调用转为 args="{}"，
 *    让工具执行报 "invalid arguments" 给模型自纠，而不是杀死整个 turn。
 *
 * 输入为 [MEDERI_INPUT_PERSISTED] 时跳过 user() 追加（消息已落库、随历史恢复）。
 */
/**
 * Koog nodeLLMSendToolResults 的可持久化变体：tool results 落库后再请求下一轮 LLM。
 *
 * 背景：turn 中途崩溃（限流耗尽/断流）时 ChatMemory 的 strategy 级 store 不执行，
 * tool results（user 消息）会丢——下一轮模型看不到自己上一轮工具调用的结果。
 * 先落库再请求（persist 内部吞异常，不影响业务流）。
 */
private fun nodeLLMSendToolResultsPersistable(
    persister: TurnIncrementalPersister?
): AIAgentNodeDelegate<ReceivedToolResults, Message.Assistant> =
    node("send_tool_results") {
        persister?.persistToolResults(it)
        llm.writeSession {
            appendPrompt {
                user {
                    it.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                }
            }
            // 流式与 nodeCallLLM 对齐：requestLLM() 是非流式——工具轮之后的
            // LLM 响应（含 turn 的最终文本回复）从不产生 MESSAGE_DELTA，
            // UI 只能在 MESSAGE_COMPLETED 后靠快照刷新看到整段文字（端到端实测）。
            // 显式计时：Koog metaInfo.timestamp 是流结束时刻，推算 durationMs≈0，
            // 故围绕 requestLLMStreaming 前后测量真实流耗时（含推理+正文）。
            val startNs = System.nanoTime()
            val response = requestLLMStreaming().toList().toAssistantMessageSafe()
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            // requestLLMStreaming() 不自动追加响应到 prompt（与 requestLLM() 不同），手动补
            appendPrompt { message(response) }
            persister?.persistAssistant(response, durationMs)
            response
        }
    }

fun mederiSingleRunStrategy(
    persister: TurnIncrementalPersister? = null
): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("mederi_single_run") {
        val nodeCallLLM by node<String, Message.Assistant>("call_llm_streaming") { message ->
            llm.writeSession {
                if (message != MEDERI_INPUT_PERSISTED) appendPrompt { user(message) }
                // 显式计时：Koog metaInfo.timestamp=流结束时刻，推算 durationMs≈0，必须实测
                val startNs = System.nanoTime()
                val response = requestLLMStreaming().toList().toAssistantMessageSafe()
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                // requestLLMStreaming() 不会自动把响应追加到 prompt（与 requestLLM() 不同），
                // 必须手动追加，否则 ChatMemory.store 时 assistant 消息会丢失。
                appendPrompt { message(response) }
                // 增量持久化：LLM 响应到达即落库。turn 中途崩溃时本轮对话不丢
                // （ChatMemory 的 strategy 级 store 只在 turn 正常结束时执行）
                persister?.persistAssistant(response, durationMs)
                response
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResultsPersistable(persister)

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 边选择按注册顺序取第一条命中（AIAgentNode.resolveEdge）。模型常把解释文本
        // 与工具调用混排在同一条消息（parts = [Text, Call, ...]）——此时 onTextMessage
        // 与 onToolCalls 都命中，若 onTextMessage 先注册，turn 会以"文本即最终答案"
        // 结束，工具调用被静默丢弃（端到端实测抓到：edit_file 报成功但从未执行）。
        // 所以"执行 vs 结束"的每个分支点必须 onToolCalls 先注册。
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    }

/**
 * Mederi 流式 + 自动压缩策略。
 *
 * 基于 Koog 的 singleRunStrategyWithHistoryCompression，但 LLM 节点改用流式
 * （requestLLMStreaming），保持 UI 实时输出。
 * 压缩节点（nodeLLMCompressHistory）保持 Koog 原版（阻塞调用，对用户透明）。
 */
fun mederiSingleRunStrategyWithCompression(
    config: HistoryCompressionConfig,
    persister: TurnIncrementalPersister? = null
): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("mederi_single_run_with_compression") {
        // 流式 LLM 节点
        val nodeCallLLM by node<String, Message.Assistant>("call_llm_streaming") { message ->
            llm.writeSession {
                if (message != MEDERI_INPUT_PERSISTED) appendPrompt { user(message) }
                // 显式计时：Koog metaInfo.timestamp=流结束时刻，推算 durationMs≈0，必须实测
                val startNs = System.nanoTime()
                val response = requestLLMStreaming().toList().toAssistantMessageSafe()
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                appendPrompt { message(response) }
                // 增量持久化（同 mederiSingleRunStrategy）
                persister?.persistAssistant(response, durationMs)
                response
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResultsPersistable(persister)

        // 压缩节点（Koog 原版，阻塞）
        val nodeCompressHistory by nodeLLMCompressHistory<ReceivedToolResults>(
            strategy = config.compressionStrategy,
            retrievalModel = config.retrievalModel
        )

        // 压缩后发送工具结果给 LLM（流式对齐 nodeSendToolResult，见其注释）
        val nodeSendCompressedHistory by node<ReceivedToolResults, Message.Assistant>("send_compressed") {
            llm.writeSession {
                // 显式计时：Koog metaInfo.timestamp=流结束时刻，推算 durationMs≈0，必须实测
                val startNs = System.nanoTime()
                val response = requestLLMStreaming().toList().toAssistantMessageSafe()
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                appendPrompt { message(response) }
                // 增量持久化（与 nodeCallLLM / nodeSendToolResult 一致）
                persister?.persistAssistant(response, durationMs)
                response
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })

        edge(nodeExecuteTool forwardTo nodeCompressHistory onCondition {
            llm.readSession { config.isHistoryTooBig(prompt) }
        })
        edge(nodeExecuteTool forwardTo nodeSendToolResult onCondition {
            llm.readSession { !config.isHistoryTooBig(prompt) }
        })
        edge(nodeCompressHistory forwardTo nodeSendCompressedHistory)

        // 同 mederiSingleRunStrategy：onToolCalls 必须先于 onTextMessage 注册，
        // 否则 Text+Call 混排消息会直接结束 turn、工具调用被静默丢弃。
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeSendCompressedHistory forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendCompressedHistory forwardTo nodeFinish onTextMessage { true })
    }

/**
 * 手动压缩专用策略：单节点，只做 replaceHistoryWithTLDR。
 *
 * 在 mini agent 中使用：ChatMemory 加载历史 → 压缩 → ChatMemory 存储压缩后的历史。
 */
fun compressOnlyStrategy(
    compressionStrategy: HistoryCompressionStrategy
): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("compress_only") {
        val nodeCompress by nodeLLMCompressHistory<String>(strategy = compressionStrategy)
        edge(nodeStart forwardTo nodeCompress)
        edge(nodeCompress forwardTo nodeFinish)
    }