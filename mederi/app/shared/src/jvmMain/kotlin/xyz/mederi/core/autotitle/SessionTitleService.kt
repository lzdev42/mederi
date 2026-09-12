package xyz.mederi.core.autotitle

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.mederi.Mederi
import xyz.mederi.debug.DebugLog
import xyz.mederi.domain.model.AIModel
import xyz.mederi.domain.model.EventType
import xyz.mederi.domain.model.MessageRole
import xyz.mederi.domain.model.MessagePart
import xyz.mederi.domain.model.Session
import xyz.mederi.domain.model.UI_HIDDEN_MARKER
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.infrastructure.koog.OneShotCompletion

/**
 * 会话自动命名服务（挂 [MederiAiCore.initialize]，全部宿主生效）。
 *
 * 每个会话第一条消息**发出时**（收到 SESSION_UPDATED——core 在用户消息落库后才广播该事件，
 * durable-first 保证此时回查历史必能看到本条用户消息），后台选一个模型生成标题并改名，
 * 用户全程无感知。
 *
 * 选模型策略（所有层都是：当前供应商优先，当前没有 → 其他已连接供应商）：
 * 1. 免费模型：models.dev 目录价格 input==0 && output==0
 * 2. 小模型：名字含 flash 或 lite（大小写不敏感），排除含 mini 的（有旗舰模型叫 mini）
 * 扫描范围是该供应商的**全部**模型（不过滤 isEnabled——是"这个供应商有"，不是"用户选择显示的"）。
 *
 * 请求：走 [OneShotCompletion] 复用该供应商的 baseUrl + API Key 与主对话同一条 Koog 链路；
 * **不注入任何推理参数**（服务器默认，推理型模型关思考普遍会报错）。
 * 请求报错 → 换下一个候选；候选耗尽 → 兜底直接用用户发信息用的模型再请求一次；
 * 仍失败 → 最终兜底 session_yyyy-MM-ddTHH:mm:ss（本地时间）。
 *
 * 触发条件：仍为默认标题且恰好一条 user 消息；生成过或用户手动改名过不再触发。
 */
class SessionTitleService(
    private val mederi: Mederi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** 改名成功后回调（桥层用它刷新 projects StateFlow，侧边栏即时变名）。 */
    private val onRenamed: suspend () -> Unit = {},
) {

    private val processedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val started = AtomicBoolean(false)

    /** 挂载事件流，开始监听第一条消息发出（幂等，重复调用 no-op）。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            mederi.sessionManager.events().collect { event ->
                if (event.type == EventType.SESSION_UPDATED) {
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
                val session = mederi.sessionManager.get(sessionId) ?: return@launch
                if (session.title != DEFAULT_TITLE) return@launch

                val messages = mederi.sessionManager.listMessages(sessionId)
                val userMessages = messages.filter { it.role == MessageRole.USER }
                // 发送时触发：恰好一条 user 消息（assistant 可能还没回复，不设数量条件）
                if (userMessages.size != 1) return@launch

                val firstUserText = userMessages
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .substringBefore(UI_HIDDEN_MARKER)
                    .trim()
                if (firstUserText.isEmpty()) return@launch

                val title = generateTitle(session, firstUserText)
                DebugLog.event(TAG, "会话自动命名: $sessionId -> $title")
                mederi.sessionManager.rename(sessionId, title)
                runCatching { onRenamed() }
                    .onFailure { DebugLog.error(TAG, "改名后刷新 UI 失败: ${it.message}", it) }
            } catch (e: Exception) {
                DebugLog.error(TAG, "会话 $sessionId 自动命名异常: ${e.message}", e)
            }
        }
    }

    /** 按优先级选模型请求标题；全部候选 + 兜底都失败返回时间戳兜底名。 */
    private suspend fun generateTitle(session: Session, userText: String): String {
        val prompt = "$PROMPT_PREFIX${userText.take(MAX_INPUT_CHARS)}"
        // 只从已连接（有默认 Key）供应商里选——请求需要能发出去
        val providers = mederi.providerManager.list().filter { it.defaultApiKey != null }

        // 当前供应商 = 会话最后一次用的模型所属供应商（TurnExecutor 每轮回写 session.aiModel）
        val currentProviderId = session.aiModel?.let { model ->
            providers.firstOrNull { p -> p.models.any { it.id == model.id } }?.id
        }

        for ((provider, model) in buildCandidates(providers, currentProviderId)) {
            val title = requestTitle(provider, model, prompt)
            if (!title.isNullOrBlank()) return title
        }

        // 兜底：直接用用户发信息用的模型（session.aiModel 的快照 id）再请求一次
        session.aiModel?.let { snapshot ->
            val provider = providers.firstOrNull { p -> p.models.any { it.id == snapshot.id } }
            val live = provider?.getModel(snapshot.id)
            if (provider != null && live != null) {
                val title = requestTitle(provider, live, prompt)
                if (!title.isNullOrBlank()) return title
            }
        }

        return "session_" + LocalDateTime.now().format(FALLBACK_FORMAT)
    }

    /**
     * 候选顺序：免费（当前 → 其他）→ 小模型（当前 → 其他）。
     * 同一个模型可重复进列表（兜底模型可能同时也是免费/小模型），请求失败自然推进到下一个。
     */
    private fun buildCandidates(
        providers: List<Provider>,
        currentProviderId: String?
    ): List<Pair<Provider, AIModel>> {
        val current = providers.firstOrNull { it.id == currentProviderId }
        val others = providers.filter { it.id != currentProviderId }

        val result = mutableListOf<Pair<Provider, AIModel>>()
        current?.let { cp -> result += cp.models.filter { isFree(cp, it) }.map { cp to it } }
        others.forEach { op -> result += op.models.filter { isFree(op, it) }.map { op to it } }
        current?.let { cp -> result += cp.models.filter { isSmall(it) }.map { cp to it } }
        others.forEach { op -> result += op.models.filter { isSmall(it) }.map { op to it } }
        return result
    }

    /** models.dev 目录价格：输入和输出都为 0 = 免费。目录查不到 = 不算免费。 */
    private fun isFree(provider: Provider, model: AIModel): Boolean {
        val meta = mederi.modelCatalog.getFor(provider.modelsDevKey, provider.baseUrl, model.providerModelId)
            ?: return false
        return meta.inputPricePerMillion == 0.0 && meta.outputPricePerMillion == 0.0
    }

    /** 小模型：名字含 flash 或 lite，且不含 mini（有旗舰模型叫 mini）。 */
    private fun isSmall(model: AIModel): Boolean {
        val name = model.name.lowercase()
        return (name.contains("flash") || name.contains("lite")) && !name.contains("mini")
    }

    /** 用该供应商的 baseUrl + Key 请求标题；任何异常/空返回都视为该候选失败。 */
    private suspend fun requestTitle(provider: Provider, model: AIModel, prompt: String): String? {
        val apiKey = mederi.providerManager.getDefaultKeyValue(provider.id) ?: return null
        val raw = runCatching {
            OneShotCompletion.execute(
                provider = provider,
                model = model,
                apiKey = apiKey,
                tag = "autotitle",
                prompt = prompt,
                maxTokens = MAX_TITLE_TOKENS
            )
        }.onFailure { DebugLog.event(TAG, "标题请求失败 ${provider.name}/${model.name}: ${it.message}") }
            .getOrNull()
        return raw?.trim()
            ?.trim('"', '\'', '`')
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_TITLE_CHARS)
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "AutoTitle"

        /** 与 core SessionManagerImpl.create 的默认标题保持一致。 */
        const val DEFAULT_TITLE = "New Session"

        const val MAX_INPUT_CHARS = 400
        const val MAX_TITLE_CHARS = 40
        const val MAX_TITLE_TOKENS = 512

        /** 全链路失败兜底名：session_yyyy-MM-ddTHH:mm:ss */
        val FALLBACK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        const val PROMPT_PREFIX =
            "根据以下用户消息生成一个不超过20字的会话标题。只输出标题本身，不要引号、句号或任何解释。\n用户消息："
    }
}