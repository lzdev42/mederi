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
    description = describeCalls(operations.keys),
) {
    @Serializable
    data class Args(
        @LLMDescription(
            "Required JSON array of call objects. Pass the array directly, without surrounding quotes. " +
                "Every item requires both tool (a tool name) and parameters (a nested JSON object)."
        )
        val calls: List<Call>,
    )

    @Serializable
    data class Call(
        @LLMDescription("Required. Exact name of one of the available member tools.")
        val tool: String,
        @LLMDescription(
            "Required JSON object containing the selected tool's arguments. " +
                "Put fields such as command or path inside this object."
        )
        val parameters: Parameters,
    )

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

    companion object {
        private fun describeCalls(available: Set<String>): String = buildString {
            appendLine("Run independent tool calls concurrently and return their results in input order.")
            appendLine("Available member tools: ${available.joinToString()}.")
            appendLine("Pass calls as a JSON array, never as a string containing JSON.")
            appendLine("Each array item must include tool and parameters. Put command, path, and other member arguments inside the parameters object.")
            val examples = listOf(
                Call(ToolName.BASH.value, Parameters(command = "pwd")),
                Call(ToolName.READ_FILE.value, Parameters(path = "src/Main.kt")),
                Call(ToolName.LIST_DIRECTORY.value, Parameters(path = "src")),
            ).filter { it.tool in available }
            if (examples.isNotEmpty()) appendLine("Example arguments: ${Json.encodeToString(Args(examples))}")
            appendLine("Use this for independent reads, directory listings, or foreground commands. Run dependencies, overlapping writes, duplicate commands, and background commands separately.")
            append("Each call retains its sandbox and approval checks. Results include individual failures.")
        }
    }

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
