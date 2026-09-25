package xyz.mederi.question

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MederiEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个问题的定义。
 */
@Serializable
data class Question(
    val id: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val allowCustom: Boolean = false,
    val multiSelect: Boolean = false
)

/**
 * 问题请求结果。
 *
 * @param answers 每个问题的答案列表（多选时有多个）。顺序与 questions 列表一致。
 * @param rejected 用户拒绝回答
 */
data class QuestionResult(
    val answers: List<List<String>>,
    val rejected: Boolean = false
)

/**
 * 问题请求器。
 *
 * 工具在需要向用户提问时，调用 [request] 挂起当前协程，
 * 通过事件流通知 UI，等待用户通过 [resolve] 方法回复。
 *
 * 典型流程：
 * 1. 工具调用 `requester.request(questions)`
 * 2. 发出 QUESTION_REQUESTED 事件
 * 3. 工具协程挂起等待
 * 4. 用户调用 `resolve(questionId, answers)`
 * 5. 发出 QUESTION_RESOLVED 事件
 * 6. 工具协程恢复，拿到 [QuestionResult]
 */
open class QuestionRequester(
    private val sessionId: String,
    private val eventBus: MutableSharedFlow<MederiEvent>
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<QuestionResult>>()
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    open suspend fun request(questions: List<Question>): QuestionResult {
        val questionId = "q_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<QuestionResult>()
        pending[questionId] = deferred

        val questionsJson = json.encodeToString(questions)

        eventBus.emit(MederiEvent(
            type = EventType.QUESTION_REQUESTED,
            sessionId = sessionId,
            payload = mapOf(
                "questionId" to questionId,
                "questions" to questionsJson
            ),
            timestamp = Instant.now().toString()
        ))

        return deferred.await()
    }

    open suspend fun resolve(questionId: String, answers: List<List<String>>): Boolean {
        val deferred = pending.remove(questionId) ?: return false
        deferred.complete(QuestionResult(answers = answers))

        val answersJson = json.encodeToString(answers)
        eventBus.emit(MederiEvent(
            type = EventType.QUESTION_RESOLVED,
            sessionId = sessionId,
            payload = mapOf(
                "questionId" to questionId,
                "answers" to answersJson
            ),
            timestamp = Instant.now().toString()
        ))
        return true
    }

    fun reject(questionId: String): Boolean {
        val deferred = pending.remove(questionId) ?: return false
        deferred.complete(QuestionResult(answers = emptyList(), rejected = true))
        // 与 [resolve] 对称：拒绝也补发 QUESTION_RESOLVED 事件，UI 据此清除问题卡片。
        // 契约层无独立 reject 通道，UI 拒绝当前走 resolveQuestion(emptyList)；
        // 此处的 rejected 标志供直接调用 reject() 的内部路径消费（防 UI 卡片悬挂）。
        eventBus.tryEmit(MederiEvent(
            type = EventType.QUESTION_RESOLVED,
            sessionId = sessionId,
            payload = mapOf(
                "questionId" to questionId,
                "answers" to "[]"
            ),
            timestamp = Instant.now().toString()
        ))
        return true
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun cancelAll() {
        pending.values.forEach {
            it.complete(QuestionResult(answers = emptyList(), rejected = true))
        }
        pending.clear()
    }
}
