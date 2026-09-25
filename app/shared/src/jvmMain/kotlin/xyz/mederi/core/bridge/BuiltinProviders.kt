package xyz.mederi.core.bridge

import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.domain.model.ReasoningParameter

/**
 * 内置模型定义。
 */
data class BuiltinModelDef(
    val providerModelId: String,
    val name: String,
    val supportsReasoning: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.NONE,
    val supportsImages: Boolean = false,
    val reasoningLevels: List<ReasoningLevel> = emptyList()
)

/**
 * 供应商端点定义。
 *
 * @param displayName 显示名称（如 "SG"、"CN"），用于拼接完整供应商名。
 * @param url 端点完整 URL。
 */
data class EndpointDef(val displayName: String, val url: String)

/**
 * 内置供应商定义。
 *
 * 在代码中静态结构化定义，启动时与本地配置校验同步。
 * 一个供应商可能有多个端点（如 Agnes SG/CN），每个端点在 UI 上展示为独立的供应商条目。
 *
 * @param name 供应商基础名（不含端点后缀）。
 * @param type 供应商类型。
 * @param endpoints 可用的端点列表。
 * @param models 内置模型定义（可留空，由用户从远端拉取）。
 * @param responseSanitization 是否需要响应清洗。
 * @param reasoningParameter 该供应商的推理参数配置。null 表示用 [ReasoningParameter.forType] 按 [type] 自动选择。
 * @param modelsDevKey models.dev 目录的供应商 key（元数据同步用）。
 *   端点与目录 api 字段一致时可省略（自动按 baseUrl 匹配）；目录 api 字段缺失
 *   （如 google）或多端点共用一个目录条目（如 Agnes SG/CN）时必须显式指定。
 */
data class BuiltinProviderDef(
    val name: String,
    val type: ProviderType,
    val endpoints: List<EndpointDef>,
    val models: List<BuiltinModelDef> = emptyList(),
    val responseSanitization: Boolean = false,
    val reasoningParameter: ReasoningParameter? = null,
    val modelsDevKey: String? = null
) {
    /**
     * 获取该端点对应的完整显示名（如 "Agnes SG"、"Agnes CN"）。
     */
    fun displayName(endpoint: EndpointDef): String =
        if (endpoint.displayName.isBlank()) name else "$name ${endpoint.displayName}"
}

/**
 * 展开后的供应商条目，包含端点信息。
 *
 * @param def 原始预设定义。
 * @param endpoint 具体端点。
 */
data class BuiltinEntry(
    val def: BuiltinProviderDef,
    val endpoint: EndpointDef,
    val name: String = def.displayName(endpoint),
    val baseUrl: String = endpoint.url
)

/**
 * 内置预设供应商清单。
 *
 * 内置供应商是预设模板，不启动时自动写入配置。
 * 用户通过 [MederiAiCore.addBuiltinProvider] 主动添加（必须填 API Key）。
 * 模型列表不写死：添加后由用户从远端拉取或手动添加。
 */
object BuiltinProviders {
    val ALL = listOf(
        BuiltinProviderDef(
            name = "Google Gemini",
            type = ProviderType.GOOGLE,
            // 原生 Generative Language API 的版本路径是 /v1beta（非 OpenAI 兼容模式的 /v1beta/openai/）
            endpoints = listOf(EndpointDef("", "https://generativelanguage.googleapis.com/v1beta")),
            // 模型不内置：添加后由 fetchRemoteModels 从原生 models.list 拉取并保存（含上下文窗口等元数据）
            // 目录条目 api 字段缺失，必须显式指定 key
            modelsDevKey = "google"
        ),
        BuiltinProviderDef(
            name = "Agnes",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(
                EndpointDef("SG", "https://apihub.agnes-ai.com/v1"),
                EndpointDef("CN", "https://apihub.agnes-ai.cn/v1")
            ),
            responseSanitization = true,
            // 开关型端点（Qwen3 无 effort 概念）：NONE = 显式关闭，HIGH = 开启（"高"即开）
            reasoningParameter = ReasoningParameter(
                levels = mapOf(
                    ReasoningLevel.NONE to """{"chat_template_kwargs":{"enable_thinking":false}}""",
                    ReasoningLevel.HIGH to """{"chat_template_kwargs":{"enable_thinking":true}}"""
                )
            ),
            // SG/CN 两个端点共用目录里一个 agnes 条目（api 字段只写了 SG），显式指定
            modelsDevKey = "agnes"
        ),
        BuiltinProviderDef(
            name = "Hetzner",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(EndpointDef("", "https://inference.hetzner.com/api/v1")),
            responseSanitization = true,
            modelsDevKey = "hetzner"
        ),
        BuiltinProviderDef(
            name = "Empero",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(EndpointDef("", "https://free.empero.org/v1"))
        ),
        BuiltinProviderDef(
            name = "OpenCode Zen",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(EndpointDef("", "https://opencode.ai/zen/v1")),
            modelsDevKey = "opencode"
        ),
        BuiltinProviderDef(
            name = "OpenRouter",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(EndpointDef("", "https://openrouter.ai/api/v1")),
            modelsDevKey = "openrouter"
        ),
        BuiltinProviderDef(
            name = "商汤 SenseNova",
            type = ProviderType.OPENAI_CHAT,
            endpoints = listOf(EndpointDef("", "https://token.sensenova.cn/v1")),
            responseSanitization = true,
            // 商汤必须显式指定 reasoning_effort: "none" 才能关闭推理
            reasoningParameter = ReasoningParameter(
                levels = mapOf(
                    ReasoningLevel.NONE to """{"reasoning_effort":"none"}""",
                    ReasoningLevel.LOW to """{"reasoning_effort":"low"}""",
                    ReasoningLevel.MEDIUM to """{"reasoning_effort":"medium"}""",
                    ReasoningLevel.HIGH to """{"reasoning_effort":"high"}""",
                    ReasoningLevel.MAX to """{"reasoning_effort":"max"}"""
                )
            ),
            modelsDevKey = "sensenova"
        )
    )

    /**
     * 展开所有端点条目。
     */
    fun allEntries(): List<BuiltinEntry> = ALL.flatMap { def ->
        def.endpoints.map { endpoint ->
            BuiltinEntry(
                def = def,
                endpoint = endpoint,
                name = def.displayName(endpoint),
                baseUrl = endpoint.url
            )
        }
    }

    fun isBuiltinName(name: String): Boolean = allEntries().any { it.name.equals(name, ignoreCase = true) }
}
