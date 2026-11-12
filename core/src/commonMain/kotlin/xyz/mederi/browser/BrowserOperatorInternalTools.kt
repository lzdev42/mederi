package xyz.mederi.browser

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.mederi.browser.drill.DrillExecutor

/**
 * 浏览器操作员 Core 内部工具集（非页面物理操作）：
 * - [DoneTool]：完成任务并汇报最终简要结果
 * - [UpdateMemoryTool]：BrowserPilot 风格的操作员记忆自管理更新
 * - [CollectInfoTool]：多网站信息收集暂存器（URL、网站名、Markdown 内容）
 * - [JudgeTool]：调用 BrowserBrain 判定页面内容
 * - [ExecuteDrillTool]：调用 DrillExecutor 执行确定性脚本
 */

@Serializable
data class DoneArgs(
    @LLMDescription("Final summary message when task is complete")
    val message: String = ""
)

class DoneTool(
    private val onDone: ((String) -> Unit)? = null
) : SimpleTool<DoneArgs>(
    argsType = typeToken<DoneArgs>(),
    name = BrowserConstants.ToolNames.DONE,
    description = "Signal that the task is complete, with a final summary message."
) {
    override suspend fun execute(args: DoneArgs): String {
        onDone?.invoke(args.message)
        return "DONE: ${args.message}"
    }
}

@Serializable
data class MemoryArgs(
    @LLMDescription("Summary of progress and remaining sub-tasks to remember for next steps")
    val memory: String = ""
)

class UpdateMemoryTool(
    private val onMemoryUpdate: (String) -> Unit
) : SimpleTool<MemoryArgs>(
    argsType = typeToken<MemoryArgs>(),
    name = BrowserConstants.ToolNames.UPDATE_MEMORY,
    description = "Store an updated summary of progress and remaining sub-tasks into task memory."
) {
    override suspend fun execute(args: MemoryArgs): String {
        onMemoryUpdate(args.memory)
        return "memory=ok: updated"
    }
}

@Serializable
data class CollectInfoArgs(
    @LLMDescription("The URL of the webpage where this info was found. If empty, the current page URL is used automatically.")
    val url: String = "",
    @LLMDescription("The site name or page title. If empty, the current page title is used automatically.")
    val site_name: String = "",
    @LLMDescription("Specific research findings, data, or notes in Markdown format. May include image links in ![alt](url) format.")
    val content: String = ""
)

class CollectInfoTool(
    private val collector: BrowserCollector,
    private val control: BrowserControl
) : SimpleTool<CollectInfoArgs>(
    argsType = typeToken<CollectInfoArgs>(),
    name = BrowserConstants.ToolNames.COLLECT_INFO,
    description = "Store a research finding, snippet, or data point (in Markdown format, optionally including image URLs) into the temporary research scratchpad for multi-site information gathering. Saved items are preserved and summarized upon task completion."
) {
    override suspend fun execute(args: CollectInfoArgs): String {
        if (args.content.isBlank()) return "collect=FAIL: content must not be blank"
        val effectiveUrl = args.url.ifBlank {
            runCatching { control.getCurrentUrl() }.getOrNull().orEmpty()
        }
        val effectiveTitle = args.site_name.ifBlank {
            runCatching { control.getTitle() }.getOrNull().orEmpty()
        }
        val item = BrowserCollectedItem(
            url = effectiveUrl,
            siteName = effectiveTitle,
            content = args.content
        )
        val count = collector.add(item)
        return "collect=ok: total $count items collected"
    }
}

@Serializable
data class JudgeArgs(
    @LLMDescription("Page content to evaluate")
    val content: String = "",
    @LLMDescription("Instruction for judging the content")
    val instruction: String = ""
)

class JudgeTool(
    private val brain: BrowserBrain?,
    private val recipe: BrowserRecipe?,
    private val collector: BrowserCollector? = null
) : SimpleTool<JudgeArgs>(
    argsType = typeToken<JudgeArgs>(),
    name = BrowserConstants.ToolNames.JUDGE,
    description = "Evaluate page content against an instruction using the browser brain, returning structured fields or a boolean verdict."
) {
    override suspend fun execute(args: JudgeArgs): String {
        val b = brain ?: return "judge=FAIL: BrowserBrain not configured"
        return try {
            val res = b.judge(
                content = args.content,
                instruction = args.instruction,
                recipeRules = recipe?.judgeRules.orEmpty(),
                collectedItems = collector?.list().orEmpty()
            )
            "judge=${if (res.success) "ok" else "FAIL"}: ${res.toCompactString()}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "judge=FAIL: ${e.message}"
        }
    }
}

object LenientJsonStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientJsonString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) {
            return decoder.decodeString()
        }
        val element = decoder.decodeJsonElement()
        if (element is JsonPrimitive && element.isString) {
            return element.content
        }
        throw SerializationException("'script' 必须是 JSON 字符串（内容为 DrillScript 对象），收到: $element")
    }
}

@Serializable
data class DrillArgs(
    @Serializable(with = LenientJsonStringSerializer::class)
    @LLMDescription("Drill script JSON string: the content must be a DrillScript object (see tool description for the schema)")
    val script: String = ""
)

class ExecuteDrillTool(
    private val drillExecutor: DrillExecutor?,
    private val brain: BrowserBrain?,
    private val recipe: BrowserRecipe?,
    private val mountedScript: String? = null
) : SimpleTool<DrillArgs>(
    argsType = typeToken<DrillArgs>(),
    name = BrowserConstants.ToolNames.DRILL,
    description = buildDescription(mountedScript)
) {
    override suspend fun execute(args: DrillArgs): String {
        val executor = drillExecutor ?: return "drill=FAIL: DrillExecutor not configured"
        val onJudgeContent: (suspend (String, String) -> BrainResult)? = brain?.let { b ->
            { c: String, i: String -> b.judge(c, i, recipe?.judgeRules.orEmpty()) }
        }
        val rawScript = args.script.takeIf { it.isNotBlank() }
            ?: mountedScript?.takeIf { it.isNotBlank() }
            ?: return "drill=FAIL: no drill script provided and none mounted"
        val scriptElement = try {
            Json.parseToJsonElement(rawScript)
        } catch (e: Exception) {
            return "drill=FAIL: script is not valid JSON: ${e.message}"
        }
        return try {
            val r = executor.executeScript(scriptElement, onJudgeContent)
            "drill=${if (r.success) "ok" else "FAIL"}: ${r.drill_results.joinToString(";") { it.toString() }.take(200)}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "drill=FAIL: ${e.message}"
        }
    }

    companion object {
        /**
         * 动态生成工具描述：存在挂载脚本时，提示 LLM 可用空 script 参数执行挂载脚本。
         */
        fun buildDescription(mountedScript: String?): String = buildString {
            append("Execute a deterministic browser automation drill script (DrillScript JSON string). ")
            if (!mountedScript.isNullOrBlank()) {
                append("A drill script is MOUNTED for the current task: call this tool with an EMPTY 'script' argument to run the mounted script. ")
            }
            append("To override, pass the DrillScript as a JSON *string* in 'script' (the object must NOT be passed directly). ")
            append(
                "DrillScript fields: task_name:string, loop_count:number=1, repeat_interval_ms:number=0, " +
                    "image_assets:{\"name\":{\"path\":string,\"desc\":string}}|null, " +
                    "steps:[DrillStep], item_steps:[DrillStep]|null. "
            )
            append(
                "DrillStep fields: step_name:string, playwright_api:string|null, selector_type:string|null, " +
                    "selector_value:string|null, selector_options:{string:scalar}|null, filter_has_text:string|null, " +
                    "first:boolean|null, last:boolean|null, action:string, action_value:string|null, " +
                    "record_result:boolean=false, record_fields:{string:string}|null, url:string|null, url_from:string|null, " +
                    "url_prefix:string|null, selector_fallbacks:[string]|null, store_as:string|null, instruction:string|null, " +
                    "content_from:string|[string]|null, ai_result_key:string|null, condition:string|null, then:string|null, " +
                    "file_path:string|null, branches:[{condition:string, steps:[DrillStep]}]|null, " +
                    "default_steps:[DrillStep]|null, timeout_ms:number|null, poll_interval_ms:number|null. "
            )
            append(
                "selector_options values must be scalars (string/number/boolean); " +
                    "nested objects or arrays in selector_options are invalid."
            )
        }
    }
}
