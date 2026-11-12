package xyz.mederi.plan

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * PLAN_PROGRESS / PLAN_APPROVAL_REQUESTED 事件的子任务瘦身 wire。
 *
 * 领域模型全量不进事件 payload：spec/planDetail/acceptanceCases 全文的真理源在盘上
 * （plan.md / plan.json），消费方需要时经 getPlanDocument 按需读取。wire 只携带 UI
 * 投影所需的最小事实（index/name/status/hasSpec/acceptanceCases id+manual）。
 *
 * 序列化字段名与契约 [xyz.mederi.core.contract.models.PlanSubtaskItem] 严格一致——
 * 客户端 reducer 直接用 PlanSubtaskItem.serializer() 解码 payload["subtasks"]，无需变换。
 */
@Serializable
internal data class SubtaskWire(
    val index: Int,
    val name: String,
    val status: String,
    val hasSpec: Boolean,
    val acceptanceCases: List<AcceptanceCaseWire> = emptyList(),
)

@Serializable
internal data class AcceptanceCaseWire(
    val id: String,
    val manual: Boolean = false,
)

/** encodeDefaults：manual=false / 空 acceptanceCases 也显式输出，wire 形态确定，与契约序列化名严格对齐。 */
private val wireJson = Json { encodeDefaults = true }

/** 把领域子任务列表编码为瘦身 wire JSON。hasSpec = spec 非空；status 用枚举名（与旧全量 wire 一致）。 */
fun encodeSubtasksWire(subtasks: List<Subtask>): String = wireJson.encodeToString(
    subtasks.map { st ->
        SubtaskWire(
            index = st.index,
            name = st.name,
            status = st.status.name,
            hasSpec = !st.spec.isNullOrBlank(),
            acceptanceCases = st.acceptanceCases.map { AcceptanceCaseWire(id = it.id, manual = it.manual) },
        )
    }
)