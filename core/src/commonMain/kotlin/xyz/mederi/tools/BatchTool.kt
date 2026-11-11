package xyz.mederi.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** 一轮模型请求收集多项独立操作；委托原工具，不另建权限或命令执行路径。 */
internal class BatchTool(
    private val operations: Map<String, suspend (JsonObject) -> String>,
) : SimpleTool<BatchTool.Args>(
    argsType = typeToken<Args>(),
    name = ToolName.BATCH.value,
    description = "Run independent operations together and return all results in input order. " +
        "Available members: ${operations.keys.joinToString()}. Each call uses the original tool's parameters. " +
        "Each call must nest the member tool's arguments under a \"parameters\" object, " +
        "e.g. {\"calls\":[{\"tool\":\"read_file\",\"parameters\":{\"path\":\"src/Main.kt\"}}," +
        "{\"tool\":\"bash\",\"parameters\":{\"command\":\"ls\"}}]}. " +
        "Send multiple independent operations in one batch to collect their results in a single round. " +
        "Members run concurrently; do not batch dependencies, overlapping writes, duplicate commands, or background commands. " +
        "Each member retains its original sandbox and approval checks. One member failure does not discard other results. " +
        "No recursive batch, plan, agent-spawn, or other unlisted tools.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Independent calls to run together. Use the original tool names and argument objects.")
        val calls: List<Call>,
    )

    @Serializable
    data class Call(val tool: String, val parameters: Parameters)

    /** 有限成员的参数超集，避免工具 schema 对任意 JsonElement 的递归多态展开。 */
    @Serializable
    data class Parameters(
        val path: String? = null,
        val offset: Int? = null,
        @SerialName("max_lines") val maxLines: Int? = null,
        val command: String? = null,
        val cwd: String? = null,
        @SerialName("timeout_seconds") val timeoutSeconds: Int? = null,
        val background: Boolean? = null,
        val compilation: Boolean? = null,
        val sandbox: String? = null,
    )

    @Serializable
    data class Result(val index: Int, val tool: String, val output: String? = null, val error: String? = null)

    override suspend fun execute(args: Args): String {
        require(args.calls.isNotEmpty()) { "batch requires at least one call" }
        val results = coroutineScope {
            args.calls.mapIndexed { index, call ->
                async(Dispatchers.IO) {
                    try {
                        val operation = operations[call.tool]
                            ?: error("Tool '${call.tool}' is not available in this batch. Available: ${operations.keys.joinToString()}")
                        Result(index, call.tool, output = operation(Json.encodeToJsonElement(call.parameters).jsonObject))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result(index, call.tool, error = "${failure::class.simpleName}: ${failure.message}")
                    }
                }
            }.awaitAll()
        }
        return Json.encodeToString(results)
    }
}
