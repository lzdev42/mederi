package xyz.mederi.metadata

import xyz.mederi.http.MederiHttpClientFactory
import xyz.mederi.config.MederiIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mederi.debug.DebugLog
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * models.dev 模型元数据目录。
 *
 * 一次 GET 拉全量（207 家供应商、免 API Key），解析为内存索引，纯内存不落盘——
 * 供应商配置同步后会持久化到 providers.json，目录缓存没有恢复价值；
 * 而能用到这个目录的场景本身就需要联网。
 *
 * 用法（一行查询）：
 * ```kotlin
 * val meta = modelCatalog.get("https://apihub.agnes-ai.com/v1", "agnes-2.5-flash")
 * // → ModelMetadata(contextWindow=512000, inputPricePerMillion=0.0, supportsImages=true, ...)
 * ```
 *
 * 生命周期：UI 后端启动时调用 [start]（立即拉取 + 每小时静默刷新）；
 * 不 start 也能用（空索引，查询返回 null），便于测试和无网环境。
 */
class ModelCatalog(
    private val refreshInterval: Duration = 1.hours,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val CATALOG_BASE_URL = "https://models.dev"
        private const val CATALOG_PATH = "api.json"
    }

    @Volatile
    private var index: CatalogIndex = CatalogIndex.EMPTY

    private val mutex = Mutex()
    private val _version = MutableStateFlow(0)

    /** 每次成功刷新 +1。观察者可在版本变化后重新同步模型信息。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile
    private var started = false

    /**
     * 启动后台刷新：立即拉取一次，之后每 [refreshInterval] 静默刷新。
     * 幂等，重复调用无副作用。拉取失败只记日志，不影响启动流程。
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                refresh()
                delay(refreshInterval)
            }
        }
    }

    /**
     * 立即拉取并重建内存索引。
     *
     * @return 成功时为目录内模型总数；失败（网络/格式异常）时为 Result.failure，
     *         保留旧索引不回滚（旧数据总比没有强）。
     */
    suspend fun refresh(): Result<Int> {
        return try {
            val fetched = fetch()
            mutex.withLock { index = fetched }
            _version.value += 1
            DebugLog.event("ModelCatalog", "目录已刷新: ${fetched.size()} 个模型")
            Result.success(fetched.size())
        } catch (e: Exception) {
            DebugLog.error("ModelCatalog", "目录刷新失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 按端点 baseUrl 精确查询模型元数据。
     *
     * 自定义供应商无需任何注册——只要 baseUrl 与目录中任一供应商的 api 字段一致即命中。
     */
    fun get(baseUrl: String, modelId: String): ModelMetadata? =
        index.byApi[normalizeBaseUrl(baseUrl)]?.get(modelId)

    /** 按目录供应商 key 精确查询（api 字段缺失的条目用，如 google）。 */
    fun getByKey(providerKey: String, modelId: String): ModelMetadata? =
        index.byKey[providerKey]?.get(modelId)

    /**
     * 统一查询入口：显式 providerKey 优先，baseUrl 兜底。
     * 供应商 key 匹配不上时回退端点匹配，覆盖"同一家多端点"（如 Agnes SG/CN）。
     */
    fun getFor(providerKey: String?, baseUrl: String, modelId: String): ModelMetadata? {
        providerKey?.takeIf { it.isNotBlank() }?.let { key ->
            getByKey(key, modelId)?.let { return it }
        }
        return get(baseUrl, modelId)
    }

    /** 只读已加载目录，优先使用 OpenRouter 上同一模型的标准目录价。 */
    fun referencePricing(
        modelId: String,
        providerKey: String? = null,
        baseUrl: String? = null,
    ): ModelReferencePricing? = index.referencePricing(modelId, providerKey, baseUrl)

    /**
     * 只读已加载目录，查**该供应商自己目录条目**的价格（与 [referencePricing] 的标准价并列）。
     *
     * 优先级：providerKey 直查 → baseUrl 直查 → 命中条目的 canonicalModelId 在**同供应商**内反查。
     * 诚实原则：只查配置的供应商自己，绝不查 OpenRouter、绝不跨供应商兜底——
     * 该供应商目录没有就是 null（目录无价 ≠ OpenRouter 价）。
     */
    fun providerPricing(
        modelId: String,
        providerKey: String? = null,
        baseUrl: String? = null,
    ): ModelReferencePricing? = index.providerPricing(modelId, providerKey, baseUrl)

    private suspend fun fetch(): CatalogIndex {
        val httpClient = MederiHttpClientFactory.create(
            clientName = MederiIdentity.PRODUCT_NAME + "-model-catalog",
            baseUrl = CATALOG_BASE_URL,
        )
        try {
            val raw = httpClient.get<String>(path = CATALOG_PATH, responseType = String::class)
            return parseModelsDevResponse(raw)
        } finally {
            httpClient.close()
        }
    }
}

internal fun CatalogIndex.referencePricing(
    modelId: String,
    providerKey: String?,
    baseUrl: String?,
): ModelReferencePricing? {
    val officialProvider = when (baseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase()) {
        "api.openai.com" -> "openai"
        "generativelanguage.googleapis.com" -> "google"
        "openrouter.ai" -> "openrouter"
        else -> null
    }
    val actualModel = providerKey?.let { byKey[it]?.get(modelId) }
        ?: baseUrl?.let { byApi[normalizeBaseUrl(it)]?.get(modelId) }
        ?: officialProvider?.let { byKey[it]?.get(modelId) }
    val nativeNamespace = providerKey?.takeIf { it == "openai" || it == "google" }
        ?: officialProvider?.takeIf { it == "openai" || it == "google" }
    val openRouter = byKey["openrouter"]
    val standardIds = listOfNotNull(
        modelId,
        actualModel?.canonicalModelId,
        nativeNamespace?.takeUnless { '/' in modelId }?.let { "$it/$modelId" },
    )
    // ① standardIds 直查 OpenRouter（官方命名空间与 canonical 命中的既有优先级）
    // ② actualModel 自身价格（供应商自己收录时用自己的价，优先于 OR 参考价）
    // ③ actualModel 的 canonical 反查 OpenRouter
    // ④ 全局同名兜底：别家收录的同名条目 → canonical 反查 OR，或自身价格
    return standardIds.firstNotNullOfOrNull { openRouter?.get(it)?.referencePricing() }
        ?: actualModel?.referencePricing()
        ?: actualModel?.canonicalModelId?.let { byCanonical[it]?.referencePricing() }
        ?: byModelId[modelId]?.let { global ->
            global.canonicalModelId?.let { byCanonical[it]?.referencePricing() }
                ?: global.referencePricing()
        }
}

internal fun CatalogIndex.providerPricing(
    modelId: String,
    providerKey: String?,
    baseUrl: String?,
): ModelReferencePricing? {
    val providerModels = providerKey?.takeIf { it.isNotBlank() }?.let { byKey[it] }
        ?: baseUrl?.let { byApi[normalizeBaseUrl(it)] }
        ?: return null
    val entry = providerModels[modelId] ?: return null
    return entry.referencePricing()
        ?: entry.canonicalModelId?.let { canonical -> providerModels[canonical]?.referencePricing() }
}

private fun ModelMetadata.referencePricing(): ModelReferencePricing? {
    val input = inputPricePerMillion?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val output = outputPricePerMillion?.takeIf { it.isFinite() && it >= 0 } ?: return null
    return ModelReferencePricing(input, output, cacheReadPricePerMillion, cacheWritePricePerMillion)
}
