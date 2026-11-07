package xyz.mederi.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.mederi.core.contract.ToolArgParser
import xyz.mederi.core.contract.dto.ConversationSnapshot
import xyz.mederi.core.contract.models.ToolCallState
import xyz.mederi.core.contract.models.ToolCallUi

private val batchProjectionJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class BatchDisplayCall(val tool: String, val parameters: JsonObject)

@Serializable
private data class BatchDisplayResult(
    val index: Int,
    val tool: String,
    val output: String? = null,
    val error: String? = null,
)

/** 展示层展开批量成员；不伪造执行事件、不修改模型历史或增加请求。 */
internal fun expandBatchToolCall(
    call: ToolCallUi,
    snapshot: ConversationSnapshot? = null,
): List<ToolCallUi> {
    if (call.name != "batch") return listOf(call)
    // 参数尚在流入时不短暂暴露 batch 外壳；终态解析失败仍保留原始诊断。
    fun fallback(): List<ToolCallUi> = when (call.state) {
        is ToolCallState.Pending, is ToolCallState.Running -> emptyList()
        else -> listOf(call)
    }
    val input = when (val state = call.state) {
        is ToolCallState.Pending -> state.input
        is ToolCallState.Running -> state.input
        is ToolCallState.Completed -> state.input
        is ToolCallState.Failed -> state.input
    }
    val members = runCatching {
        batchProjectionJson.decodeFromString<List<BatchDisplayCall>>(input["calls"] ?: return fallback())
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return fallback()

    val results = (call.state as? ToolCallState.Completed)?.let { completed ->
        val decoded = runCatching {
            batchProjectionJson.decodeFromString<List<BatchDisplayResult>>(completed.output)
        }.getOrNull() ?: return listOf(call)
        // 损坏/不完整结果不能被投影成成功；保留原工具行及原始诊断供检查。
        if (decoded.size != members.size || decoded.map { it.index }.distinct().size != decoded.size ||
            decoded.any { result ->
                result.index !in members.indices || members[result.index].tool != result.tool ||
                    (result.output == null && result.error == null)
            }) return listOf(call)
        decoded.associateBy { it.index }
    }

    return members.mapIndexed { index, member ->
        val memberInput = ToolArgParser.parse(batchProjectionJson.encodeToString(member.parameters))
        val result = results?.get(index)
        val state = when (val parent = call.state) {
            is ToolCallState.Pending -> ToolCallState.Pending(memberInput)
            is ToolCallState.Running -> ToolCallState.Running(memberInput)
            is ToolCallState.Failed -> ToolCallState.Failed(memberInput, parent.error)
            is ToolCallState.Completed -> if (result?.error != null) {
                ToolCallState.Failed(memberInput, result.error)
            } else {
                ToolCallState.Completed(memberInput, result?.output ?: return listOf(call))
            }
        }
        ToolCallUi(
            id = "${call.id}/batch/$index",
            name = member.tool,
            state = state,
            target = probeToolTarget(member.tool, memberInput, snapshot),
            isFailed = state is ToolCallState.Failed,
        )
    }
}
