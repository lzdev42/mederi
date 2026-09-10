package xyz.mederi.core.autotitle

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import xyz.mederi.core.contract.AiCore
import xyz.mederi.core.contract.models.ChatBlock
import xyz.mederi.core.contract.models.ChatRole
import xyz.mederi.core.contract.models.CoreEventType
import xyz.mederi.debug.DebugLog

/**
 * 会话自动命名服务（UI 层特性，不触碰 core）。
 *
 * 每个会话第一条消息**发出时**（收到 SESSION_UPDATED——core 在用户消息落库后才广播该事件，
 * durable-first 保证此时回查快照必能看到本条用户消息），后台把用户第一条消息发给
 * OpenCode Zen 免费模型——无 Key 直连、关推理——用返回的标题 rename 会话，用户全程无感知。
 *
 * - 免费端点强制要求 `X-Session-ID` 请求头（2026-09 起，缺失返回 400 MissingSessionID：
 *   "OpenCode's free tier can only be used in OpenCode"），每次请求带随机 UUID 即可通过。
 * - 仅第一次：内存已处理集合 + "仍是默认标题且恰好一条 user 消息"双重判定；
 *   生成过或用户手动改名过（标题非默认）都不会再触发。
 * - 降级：免费模型链依次尝试，全部失败（免费额度用尽 / 限流 / 网络不通 / 模型下架）
 *   时把会话命名为 session_yyyy-MM-dd_HH-mm-ss（本地时间）。一律静默，不重试不报错。
 * - 改名走 [AiCore.renameConversation]，其内部刷新 projects StateFlow，侧边栏即时变名。
 *
 * 免费模型轮换频繁，此链 2026-09 实测可用（需带 session 头），需按 https://opencode.ai/zen/v1/models 定期核对。
 */
class SessionTitleService(
    private val aiCore: AiCore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val processedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val started = AtomicBoolean(false)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 挂载事件流，开始监听第一条消息发出（幂等，重复调用 no-op）。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            aiCore.events().collect { event ->
                if (event.type == CoreEventType.SESSION_UPDATED) {
                    renameIfNeeded(event.sessionId)
                }
            }
        }
    }

    private fun renameIfNeeded(sessionId: String) {
        scope.launch {
            // 已处理集合保证每个会话整个生命周期只判定一次
            if (!processedSessions.add(sessionId)) return@launch
            try {
                val snapshot = aiCore.getSnapshot(sessionId).getOrNull() ?: return@launch
                val conversation = snapshot.conversation
                if (conversation.title != DEFAULT_TITLE) return@launch

                val messages = snapshot.messages
                val userMessages = messages.filter { it.role == ChatRole.User }
                // 发送时触发：恰好一条 user 消息（assistant 可能还没回复，不设数量条件）
                if (userMessages.size != 1) return@launch

                val firstUserText = userMessages
                    .flatMap { it.blocks }
                    .filterIsInstance<ChatBlock.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (firstUserText.isEmpty()) return@launch

                val generated = generateTitle(firstUserText)
                val title = generated ?: fallbackTitle()
                DebugLog.event(TAG, "会话自动命名: $sessionId -> $title${if (generated == null) " (降级)" else ""}")
                aiCore.renameConversation(sessionId, title)
            } catch (e: Exception) {
                DebugLog.error(TAG, "会话 $sessionId 自动命名异常: ${e.message}", e)
            }
        }
    }

    /** 依次尝试免费模型链；全部失败返回 null，由调用方走时间戳兜底。 */
    private suspend fun generateTitle(userText: String): String? {
        val prompt = "$PROMPT_PREFIX${userText.take(MAX_INPUT_CHARS)}"
        for (model in FREE_MODELS) {
            val title = runCatching { requestTitle(model, prompt) }
                .onFailure { DebugLog.event(TAG, "标题模型 $model 失败: ${it.message}") }
                .getOrNull()
            if (!title.isNullOrBlank()) return title
        }
        return null
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun requestTitle(model: String, prompt: String): String? {
        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            client.post("$ZEN_BASE_URL/chat/completions") {
                // 免费档强制要求 session 身份头，缺失直接 400 MissingSessionID；随机 UUID 即可通过
                header("X-Session-ID", Uuid.random().toString())
                contentType(ContentType.Application.Json)
                setBody(
                    ChatCompletionRequest(
                        model = model,
                        messages = listOf(Message(role = "user", content = prompt))
                    )
                )
            }
        }
        if (!response.status.isSuccess()) return null
        val body = json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
        return body.choices.firstOrNull()?.message?.content
            ?.trim()
            ?.trim('"', '\'', '`')
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_TITLE_CHARS)
            ?.takeIf { it.isNotBlank() }
    }

    /** 免费额度用尽 / 模型全挂时的兜底名。 */
    private fun fallbackTitle(): String =
        "session_" + LocalDateTime.now().format(FALLBACK_FORMAT)

    private companion object {
        const val TAG = "AutoTitle"
        const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"

        /** 依次降级的免费模型（均为免 Key 直连；顺序按隐私风险与可用性权衡）。 */
        val FREE_MODELS = listOf(
            "mimo-v2.5-free",
            "ling-3.0-flash-fin-free",
            "nemotron-3.5-lightning-free",
        )

        /** 与 core SessionManagerImpl.create 的默认标题保持一致。 */
        const val DEFAULT_TITLE = "New Session"

        /** 显式关推理：免费推理模型默认输出全在 reasoning_content，content 为空。 */
        const val REASONING_EFFORT_NONE = "none"

        const val REQUEST_TIMEOUT_MS = 20_000L
        const val MAX_INPUT_CHARS = 400
        const val MAX_TITLE_CHARS = 40

        val FALLBACK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        const val PROMPT_PREFIX =
            "根据以下用户消息生成一个不超过20字的会话标题。只输出标题本身，不要引号、句号或任何解释。\n用户消息："
    }

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<Message>,
        @SerialName("reasoning_effort") val reasoningEffort: String = REASONING_EFFORT_NONE,
        // 推理模型在 reasoning_effort 未被端点尊重时会把 token 花在 reasoning 上，
        // 64 会先耗尽（finish_reason=length，content=null）——给足余量保证有正文输出
        @SerialName("max_tokens") val maxTokens: Int = 512,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatCompletionResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ResponseMessage? = null)

    @Serializable
    private data class ResponseMessage(val content: String? = null)
}
