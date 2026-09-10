package xyz.mederi.mcp.market.domain

import kotlinx.serialization.Serializable

/**
 * MCP 市场领域模型。
 *
 * 这些模型是官方 registry（server.schema.json）数据的"宽松镜像"：
 * registry 有什么字段就镜像什么，未知字段解析时直接丢弃；wild 数据里的脏形态
 * （如 argument 的 type 为空串）在解析层宽松处理，不在模型层区分。
 *
 * UI 拿这些模型直接渲染；[McpInstallOption] / [McpInputField] 是安装表单的完整原料。
 */

// ==================== 查询 ====================

/**
 * 搜索结果（一页）。官方 registry 为 cursor 翻页：[nextCursor] 非空表示还有下一页，
 * 翻页时原样传回 search 的 cursor 参数即可（服务端零状态，不缓存）。
 */
@Serializable
data class McpSearchResult(
    val items: List<McpServerSummary>,
    val nextCursor: String? = null
)

/**
 * 列表摘要。
 *
 * @param id 全局唯一标识（registry 的 name，反 DNS 格式如 "io.github.upstash/context7"）。
 *           detail / installConfig 都用这个 id。
 */
@Serializable
data class McpServerSummary(
    val id: String,
    val title: String? = null,
    val description: String = "",
    val version: String = "",
    /** 支持的传输类型（去重），如 ["stdio", "streamable-http"]。 */
    val transports: List<String> = emptyList(),
    /** 本地包类型（去重），如 ["npm", "oci"]；无本地包为空。 */
    val registryTypes: List<String> = emptyList(),
    /** registry 状态：active / deprecated / deleted。 */
    val status: String? = null,
    val updatedAt: String? = null
)

/**
 * 详情（registry 有什么输出什么）。
 *
 * 一个 server 可同时有 [packages]（本地安装）与 [remotes]（远程直连），如 context7。
 */
@Serializable
data class McpServerDetail(
    val id: String,
    val title: String? = null,
    val description: String = "",
    val version: String = "",
    val websiteUrl: String? = null,
    val repositoryUrl: String? = null,
    val iconUrl: String? = null,
    val packages: List<McpPackage> = emptyList(),
    val remotes: List<McpRemote> = emptyList(),
    val status: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null
)

// ==================== 包与远程 ====================

/**
 * 本地包（stdio 进程）。
 *
 * @param registryType npm / pypi / oci / nuget / cargo / mcpb
 * @param identifier   包名或下载地址（oci 含 registry 前缀与 tag）
 */
@Serializable
data class McpPackage(
    val registryType: String,
    val identifier: String,
    val version: String? = null,
    val registryBaseUrl: String? = null,
    /** 运行器提示（npx/uvx/docker/dnx）；缺失时按 registryType 推导。 */
    val runtimeHint: String? = null,
    /** 包的传输配置（绝大多数为 stdio）。 */
    val transport: McpTransport = McpTransport(type = "stdio"),
    val runtimeArguments: List<McpArgument> = emptyList(),
    val packageArguments: List<McpArgument> = emptyList(),
    val environmentVariables: List<McpKeyValue> = emptyList(),
    val fileSha256: String? = null
)

/**
 * 远程服务传输配置。
 *
 * @param type "streamable-http" 或 "sse"
 * @param url  端点 URL 模板，{变量} 在生成配置时替换
 */
@Serializable
data class McpRemote(
    val type: String,
    val url: String,
    val headers: List<McpKeyValue> = emptyList(),
    /** URL 模板 {变量} 的定义（描述/是否必填/默认值等，UI 表单原料）。 */
    val variables: Map<String, McpInputSpec> = emptyMap()
)

/**
 * 传输配置（包内与远程通用；包内绝大多数是 stdio）。
 */
@Serializable
data class McpTransport(
    val type: String,
    val url: String? = null,
    val headers: List<McpKeyValue> = emptyList(),
    val variables: Map<String, McpInputSpec> = emptyMap()
)

// ==================== 输入元数据（安装表单原料） ====================

/** 环境变量 / HTTP 请求头定义。 */
@Serializable
data class McpKeyValue(
    val name: String,
    /** 发布者预填的固定值；非空时无需用户输入。 */
    val value: String? = null,
    val description: String? = null,
    val isSecret: Boolean = false,
    val isRequired: Boolean = false,
    val default: String? = null,
    val placeholder: String? = null,
    /** string / number / boolean / filepath。 */
    val format: String? = null,
    val choices: List<String> = emptyList(),
    /** 值模板 {变量} 的定义。 */
    val variables: Map<String, McpInputSpec> = emptyMap()
)

/** 命令行参数定义（named：`--flag {value}`；positional：位置值）。 */
@Serializable
data class McpArgument(
    /** "named" / "positional"；wild 数据可能是空串，解析后以 name 是否以 "-" 开头兜底判断。 */
    val type: String? = null,
    /** 命名参数 flag（含前导横线，如 "--port"）。 */
    val name: String? = null,
    /** 位置参数标识（输入键，也用于展示）。 */
    val valueHint: String? = null,
    /** 发布者预填的固定值；非空时无需用户输入。 */
    val value: String? = null,
    val isRepeated: Boolean = false,
    val description: String? = null,
    val isSecret: Boolean = false,
    val isRequired: Boolean = false,
    val default: String? = null,
    val placeholder: String? = null,
    val format: String? = null,
    val choices: List<String> = emptyList(),
    val variables: Map<String, McpInputSpec> = emptyMap()
) {
    /** 是否命名参数：type 明示，或 name 以 "-" 开头（wild 数据 type 为空串时的兜底）。 */
    val isNamed: Boolean
        get() = type == "named" || (name != null && name.startsWith("-"))
}

/** URL 模板 {变量} 的定义。 */
@Serializable
data class McpInputSpec(
    val description: String? = null,
    /** 发布者预填值；非空时无需用户输入。 */
    val value: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val default: String? = null,
    val placeholder: String? = null,
    val format: String? = null,
    val choices: List<String> = emptyList()
)

// ==================== 安装形态 ====================

/**
 * 一种可安装形态（本地包或远程直连）。一个 server 可产出多个形态，UI 列出让用户选。
 */
@Serializable
data class McpInstallOption(
    /** 形态标识："package-N" / "remote-N"（N 为 detail 内的下标）。 */
    val id: String,
    val kind: Kind,
    /** 展示名，如 "npx: @upstash/context7-mcp@4.0.4" 或 "https://mcp.context7.com/mcp"。 */
    val label: String,
    val description: String? = null,
    /** 需要用户填写的输入字段（生成配置时按 key 从 inputs 取值）。 */
    val inputs: List<McpInputField> = emptyList()
) {
    @Serializable
    enum class Kind { LOCAL, REMOTE }
}

/**
 * 安装表单的单个输入字段。
 *
 * UI 规则（产品逻辑，由 UI 层执行）：
 * - [isRequired] 为 true 且未填 → 禁用安装按钮
 * - [isSecret] 为 true → 用密码框
 * - [choices] 非空 → 下拉选择
 */
@Serializable
data class McpInputField(
    /** 输入键，installConfig(inputs) 的 map 键。 */
    val key: String,
    val label: String,
    val description: String? = null,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val defaultValue: String? = null,
    val placeholder: String? = null,
    val choices: List<String> = emptyList(),
    val format: String? = null
)
