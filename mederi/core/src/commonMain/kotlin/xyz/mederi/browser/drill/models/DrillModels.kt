package xyz.mederi.browser.drill.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object LooseMapStringSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), JsonElement.serializer())
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val map = value.mapValues { JsonPrimitive(it.value) }
        encoder.encodeSerializableValue(delegate, map)
    }

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: return emptyMap()
        val jsonMap = input.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return jsonMap.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
    }
}

/**
 * 兼容 content_from 字段的单值/数组双模式反序列化。
 * - JSON: "content_from": "jd_content"         → 反序列化为 listOf("jd_content")
 * - JSON: "content_from": ["a", "b"]            → 反序列化为 listOf("a", "b")
 * - JSON: 无 content_from 字段                  → 反序列化为 null
 * 序列化时：单元素列表输出为字符串，多元素列表输出为数组（保持旧脚本可读性）
 */
object ContentFromSerializer : KSerializer<List<String>?> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(String.serializer())
    override val descriptor = listSerializer.descriptor

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: List<String>?) {
        if (value == null) {
            encoder.encodeNull()
        } else if (value.size == 1) {
            // 单元素列表序列化为字符串，保持旧脚本格式可读
            encoder.encodeString(value[0])
        } else {
            encoder.encodeSerializableValue(listSerializer, value)
        }
    }

    override fun deserialize(decoder: Decoder): List<String>? {
        val input = decoder as? JsonDecoder ?: return null
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) listOf(element.content) else null
            is JsonArray -> element.map { (it as? JsonPrimitive)?.content ?: it.toString() }
            else -> null
        }
    }
}

@Serializable
data class ImageAsset(
    val path: String,
    val desc: String
)

@Serializable
data class DrillScript(
    val task_name: String,      // 整体任务名称
    val loop_count: Int = 1,    // 循环次数
    val repeat_interval_ms: Long = 0L, // 轮询时间间隔 (0L 表示单次执行不轮询)
    val image_assets: Map<String, ImageAsset>? = null, // 图片资源映射 (名称 -> 路径+描述)
    val steps: List<DrillStep>,
    val item_steps: List<DrillStep>? = null // 新增：阶段二子流程
)

@Serializable
data class DrillBranch(
    val condition: String,           // 分支条件: exists:selector 或 {key} == value
    val steps: List<DrillStep>       // 条件满足时执行的子步骤
)

@Serializable
data class DrillStep(
    val step_name: String,      // 这一步的名称
    val playwright_api: String? = null,  // 这一步用playwright的哪个api (例: "getByRole")
    val selector_type: String? = null,  // 选择器名称/类型 (例: "button")
    val selector_value: String? = null, // 主定位值 (例: "用户名", ".btn")

    @Serializable(with = LooseMapStringSerializer::class)
    val selector_options: Map<String, String>? = null, // 额外参数 (例: {"name": "提交", "exact": "true"})

    val filter_has_text: String? = null, // filter({ hasText: '...' })
    val first: Boolean? = null,          // first()
    val last: Boolean? = null,           // last()

    val action: String,         // 执行的操作 (例: "click", "fill", "hover")
    val action_value: String? = null, // 动作所需数据
    val record_result: Boolean = false, // 哪一步的结果需要记录并返回

    // ── 新增字段 ──
    val record_fields: Map<String, String>? = null, // extract_list: 字段映射 ("outputKey": "selector")
    val url: String? = null,          // navigate: 静态 URL
    val url_from: String? = null,     // navigate: itemContext key
    val url_prefix: String? = null,   // navigate: 前缀
    val selector_fallbacks: List<String>? = null, // fetch_content: 降级选择器
    val store_as: String? = null,     // fetch_content: 结果存入 itemContext 的 key
    val instruction: String? = null,  // ask_ai: 判定指令
    @Serializable(with = ContentFromSerializer::class)
    val content_from: List<String>? = null, // ask_ai: 需判定的 itemContext 内容 key（支持多 key）
    val ai_result_key: String? = null, // ask_ai: 结果前缀
    val condition: String? = null,    // abort_item: 判断条件
    val then: String? = null,         // abort_item: 条件成立收尾动作 ("close_tab")
    val file_path: String? = null,   // write_file/append_file: 文件路径（相对于 output 目录）

    // ── branch 分支字段 ──
    val branches: List<DrillBranch>? = null,   // branch: 条件分支列表
    val default_steps: List<DrillStep>? = null, // branch: 所有条件都不满足时的默认分支
    val timeout_ms: Long? = null,              // branch: 轮询等待超时(ms)，null或0=不等待
    val poll_interval_ms: Long? = null         // branch: 轮询间隔(ms)，默认500
)

@Serializable
data class DrillResult(
    val task_name: String,
    val success: Boolean,
    val drill_results: List<Map<String, String>> = emptyList(),
    val error_message: String? = null,
    val error_type: String? = null
)