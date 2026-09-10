package xyz.mederi.mcp.market

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mederi.mcp.market.domain.McpArgument
import xyz.mederi.mcp.market.domain.McpInstallOption
import xyz.mederi.mcp.market.domain.McpInputField
import xyz.mederi.mcp.market.domain.McpInputSpec
import xyz.mederi.mcp.market.domain.McpKeyValue
import xyz.mederi.mcp.market.domain.McpPackage
import xyz.mederi.mcp.market.domain.McpRemote
import xyz.mederi.mcp.market.domain.McpServerDetail

/**
 * mcpServers 配置生成器（纯函数，零状态，"变量替换编译器"）。
 *
 * 把 registry 的 server.json 数据确定性拼装成标准 mcpServers JSON：
 *
 * 1. 变量登记   [installOptions] 遍历目标形态整棵树，收集所有需要用户填写的槽位 → 表单元数据
 * 2. 运行时推导 registryType → 命令形态（npm→npx / pypi→uvx / oci→docker(env折叠进args) /
 *               nuget→dnx / cargo→直接二进制；mcpb 与未知类型不可生成 → 报错）
 * 3. 变量代入   {token} 解析顺序：定义里的预填值 → 用户输入 → 空串（只保证格式正确，
 *               值的有效性是用户的事；必填拦截是 UI 层的产品规则，这里不设卡）
 * 4. 发射       组装 {"mcpServers": {短名: 条目}}
 * 5. 回环自检   发射结果可被重新解析（JSON 结构由 kotlinx 保证；无残留 token —— 第 3 步已兜底）
 *
 * 宽松规则（实测 wild 数据）：
 * - runtimeHint 缺失按 registryType 推导；argument.type 为空串按 name 前导横线判断命名/位置
 * - 命名参数无值且非必填 = 布尔开关 flag（只发射 flag 本身，如 ["--extension"]）
 * - npm 包 runtimeArguments 已含 "-y" 时不重复前置
 */
object McpClientConfigBuilder {

    private val json = Json { prettyPrint = true }

    /** {token} 模板匹配（registry 变量约定：花括号 + 字母数字/下划线/点/横线）。 */
    private val TOKEN = Regex("\\{([A-Za-z0-9_.\\-]+)\\}")

    // ==================== 第 1 步：变量登记 ====================

    /**
     * 列出该 server 的全部可安装形态及其表单输入。
     * MCPB 包（需下载安装文件，非命令行形态）跳过。
     */
    fun installOptions(detail: McpServerDetail): List<McpInstallOption> {
        val options = mutableListOf<McpInstallOption>()

        detail.packages.forEachIndexed { index, pkg ->
            if (pkg.registryType == "mcpb") return@forEachIndexed
            val runner = runnerFor(pkg)
            options += McpInstallOption(
                id = "package-$index",
                kind = McpInstallOption.Kind.LOCAL,
                label = "$runner: ${pkg.identifier}" + (pkg.version?.let { "@$it" } ?: ""),
                description = "本地运行 · ${pkg.registryType}" +
                    (pkg.transport.type.takeIf { it != "stdio" }?.let { " · ${it}" } ?: ""),
                inputs = collectPackageInputs(pkg)
            )
        }

        detail.remotes.forEachIndexed { index, remote ->
            options += McpInstallOption(
                id = "remote-$index",
                kind = McpInstallOption.Kind.REMOTE,
                label = remote.url,
                description = "远程连接 · ${remote.type}",
                inputs = collectRemoteInputs(remote)
            )
        }

        return options
    }

    // ==================== 第 2~5 步：生成 ====================

    /**
     * 生成标准 mcpServers JSON。
     *
     * @param optionId 安装形态 id（[installOptions] 返回的 [McpInstallOption.id]）；
     *                 null = 自动选择（有远程形态优先远程——零安装，否则第一个本地包）
     * @param inputs   用户输入（键 = [McpInputField.key]）；缺什么按空串代入，不报错
     * @throws IllegalArgumentException 形态 id 不存在，或数据本身拼不出配置（mcpb / 未知 registryType）
     */
    fun build(detail: McpServerDetail, optionId: String? = null, inputs: Map<String, String> = emptyMap()): String {
        val options = installOptions(detail)
        if (options.isEmpty()) {
            throw IllegalArgumentException("server ${detail.id} 只提供 MCPB 包（需下载安装），无法生成 mcpServers 配置")
        }

        val option = if (optionId == null) {
            options.firstOrNull { it.kind == McpInstallOption.Kind.REMOTE }
                ?: options.first()
        } else {
            options.firstOrNull { it.id == optionId }
                ?: throw IllegalArgumentException("安装形态不存在: $optionId")
        }

        val entry: JsonObject = when (option.kind) {
            McpInstallOption.Kind.REMOTE -> {
                val remote = detail.remotes.getOrNull(option.id.removePrefix("remote-").toIntOrNull() ?: -1)
                    ?: throw IllegalArgumentException("安装形态不存在: $optionId")
                remoteLikeEntry(remote.url, remote.variables, remote.headers, inputs)
            }
            McpInstallOption.Kind.LOCAL -> {
                val pkg = detail.packages.getOrNull(option.id.removePrefix("package-").toIntOrNull() ?: -1)
                    ?: throw IllegalArgumentException("安装形态不存在: $optionId")
                buildPackageEntry(pkg, inputs)
            }
        }

        val serverKey = detail.id.substringAfterLast('/', detail.id)
        val document = buildJsonObject {
            put("mcpServers", buildJsonObject { put(serverKey, entry) })
        }
        return json.encodeToString(JsonElement.serializer(), document)
    }

    // ==================== remote 形态 ====================

    /**
     * 远程条目（McpRemote 与包内非 stdio 传输共用同一种发射形态：url + headers）。
     */
    private fun remoteLikeEntry(
        url: String?,
        variables: Map<String, McpInputSpec>,
        headers: List<McpKeyValue>,
        inputs: Map<String, String>
    ): JsonObject = buildJsonObject {
        put("url", resolve(url ?: "", variables, inputs))
        put("headers", buildJsonObject {
            for (header in headers) {
                put(header.name, resolveHeaderValue(header, inputs))
            }
        })
    }

    /** header 值代入链：预填值（含 {token} 模板）→ 用户输入（键 = header 名）→ 默认值 → 空串。 */
    private fun resolveHeaderValue(header: McpKeyValue, inputs: Map<String, String>): String =
        header.value?.let { resolve(it, header.variables, inputs) }
            ?: inputs[header.name]
            ?: header.default
            ?: ""

    // ==================== local 形态（第 2 步：运行时推导表） ====================

    private fun buildPackageEntry(pkg: McpPackage, inputs: Map<String, String>): JsonObject {
        // 非stdio 传输的包（罕见）：按远程形态发射
        if (pkg.transport.type != "stdio") {
            return remoteLikeEntry(pkg.transport.url, pkg.transport.variables, pkg.transport.headers, inputs)
        }

        val env = resolveEnvironment(pkg.environmentVariables, inputs)
        val packageArgs = expandArguments(pkg.packageArguments, inputs)

        val (command, args) = when (pkg.registryType) {
            "npm" -> {
                val runtimeArgs = expandArguments(pkg.runtimeArguments, inputs)
                val withY = if ("-y" in runtimeArgs) runtimeArgs else listOf("-y") + runtimeArgs
                (pkg.runtimeHint ?: "npx") to (withY + "${pkg.identifier}@${pkg.version ?: "latest"}" + packageArgs)
            }
            "pypi" -> {
                val runtimeArgs = expandArguments(pkg.runtimeArguments, inputs)
                val versioned = pkg.version?.let { "${pkg.identifier}@$it" } ?: pkg.identifier
                (pkg.runtimeHint ?: "uvx") to (runtimeArgs + versioned + packageArgs)
            }
            "oci" -> {
                // docker 形态：env 必须折叠进 args（-e KEY），容器内才能读到
                val envFlags = env.flatMap { listOf("-e", it.key) }
                (pkg.runtimeHint ?: "docker") to (listOf("run", "-i", "--rm") + envFlags + pkg.identifier + packageArgs)
            }
            "nuget" -> {
                val versionFlags = pkg.version?.let { listOf("--version", it) } ?: emptyList()
                (pkg.runtimeHint ?: "dnx") to (listOf(pkg.identifier) + versionFlags + packageArgs)
            }
            "cargo" -> {
                // cargo install 一次，之后按二进制名直接调用（无 per-invocation runner）
                pkg.identifier to packageArgs
            }
            "mcpb" -> throw IllegalArgumentException(
                "MCPB 包（${pkg.identifier}）需下载安装文件，无法生成命令行配置"
            )
            else -> throw IllegalArgumentException("不支持的 registryType: ${pkg.registryType}")
        }

        return buildJsonObject {
            put("command", command)
            put("args", buildJsonArray { args.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        }
    }

    // ==================== 第 3 步：变量代入 ====================

    /**
     * 解析 {token} 模板：定义里的预填值 → 用户输入 → 空串。
     * 无 token 的普通字符串原样返回（零开销快路径）。
     */
    private fun resolve(
        template: String,
        variables: Map<String, McpInputSpec>,
        inputs: Map<String, String>
    ): String {
        if (!template.contains('{')) return template
        return TOKEN.replace(template) { match ->
            val token = match.groupValues[1]
            variables[token]?.value ?: inputs[token] ?: ""
        }
    }

    /** 环境变量：发布者预填值 → 用户输入 → 默认值 → 空串；全部条目都发射（键固定）。 */
    private fun resolveEnvironment(
        variables: List<McpKeyValue>,
        inputs: Map<String, String>
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (env in variables) {
            result[env.name] = env.value?.let { resolve(it, env.variables, inputs) }
                ?: inputs[env.name]
                ?: env.default
                ?: ""
        }
        return result
    }

    // ==================== 参数展开 ====================

    /**
     * 展开命令行参数：
     * - 命名参数：发射 [name, value]；无值且非必填 = 布尔开关（只发射 [name]）
     * - 位置参数：发射 [value]
     * 值来源：发布者预填 value（模板代入）→ 用户输入（key = name/valueHint）→ default → null/空串。
     */
    private fun expandArguments(arguments: List<McpArgument>, inputs: Map<String, String>): List<String> {
        val result = mutableListOf<String>()
        for (arg in arguments) {
            val inputKey = arg.name ?: arg.valueHint
            val value = arg.value?.let { resolve(it, arg.variables, inputs) }
                ?: inputKey?.let { inputs[it] }
                ?: arg.default

            if (arg.isNamed) {
                result += arg.name ?: continue
                if (value != null) result += value else if (arg.isRequired) result += ""
            } else {
                result += value ?: ""
            }
        }
        return result
    }

    // ==================== 第 1 步的收集实现 ====================

    private fun collectPackageInputs(pkg: McpPackage): List<McpInputField> {
        val fields = linkedMapOf<String, McpInputField>()
        for (env in pkg.environmentVariables) {
            if (env.value != null) continue // 发布者预填，无需用户输入
            fields[env.name] = McpInputField(
                key = env.name,
                label = env.name,
                description = env.description,
                isRequired = env.isRequired,
                isSecret = env.isSecret,
                defaultValue = env.default,
                placeholder = env.placeholder,
                choices = env.choices,
                format = env.format
            )
        }
        collectArgumentInputs(pkg.runtimeArguments, fields)
        collectArgumentInputs(pkg.packageArguments, fields)
        return fields.values.toList()
    }

    private fun collectArgumentInputs(arguments: List<McpArgument>, fields: LinkedHashMap<String, McpInputField>) {
        for (arg in arguments) {
            if (arg.value != null) continue // 发布者预填
            val key = arg.name ?: arg.valueHint ?: continue
            // 命名参数且非必填 = 布尔开关，无需输入
            if (arg.isNamed && !arg.isRequired) continue
            fields[key] = McpInputField(
                key = key,
                label = arg.valueHint ?: arg.name ?: key,
                description = arg.description,
                isRequired = arg.isRequired || !arg.isNamed, // 位置参数必须有值
                isSecret = arg.isSecret,
                defaultValue = arg.default,
                placeholder = arg.placeholder,
                choices = arg.choices,
                format = arg.format
            )
        }
    }

    private fun collectRemoteInputs(remote: McpRemote): List<McpInputField> {
        val fields = linkedMapOf<String, McpInputField>()

        // URL 模板 {token}：逐 token 登记（预填值除外）
        for (match in TOKEN.findAll(remote.url)) {
            val token = match.groupValues[1]
            val spec = remote.variables[token]
            if (spec?.value != null) continue
            fields[token] = specField(
                key = token, label = token, spec = spec,
                fallbackDescription = null, fallbackRequired = true, fallbackSecret = false
            )
        }

        for (header in remote.headers) {
            if (header.value != null) {
                // 预填值里可能还有 {token}（如 "Bearer {smithery_api_key}"）
                for (match in TOKEN.findAll(header.value)) {
                    val token = match.groupValues[1]
                    val spec = header.variables[token]
                    if (spec?.value != null || fields.containsKey(token)) continue
                    fields[token] = specField(
                        key = token, label = token, spec = spec,
                        fallbackDescription = header.description,
                        fallbackRequired = header.isRequired,
                        fallbackSecret = header.isSecret
                    )
                }
            } else {
                // 整个 header 值待填：输入键 = header 名
                fields[header.name] = McpInputField(
                    key = header.name,
                    label = header.name,
                    description = header.description,
                    isRequired = header.isRequired,
                    isSecret = header.isSecret,
                    defaultValue = header.default,
                    placeholder = header.placeholder,
                    choices = header.choices,
                    format = header.format
                )
            }
        }
        return fields.values.toList()
    }

    /**
     * 由变量定义（可缺失）+ 兜底值构造表单字段。
     * spec 存在时字段元数据以 spec 为准；缺失时用兜底值（如 token 不在 variables 里 → 必填）。
     */
    private fun specField(
        key: String,
        label: String,
        spec: McpInputSpec?,
        fallbackDescription: String?,
        fallbackRequired: Boolean,
        fallbackSecret: Boolean
    ): McpInputField = McpInputField(
        key = key,
        label = label,
        description = spec?.description ?: fallbackDescription,
        isRequired = spec?.isRequired ?: fallbackRequired,
        isSecret = spec?.isSecret ?: fallbackSecret,
        defaultValue = spec?.default,
        placeholder = spec?.placeholder,
        choices = spec?.choices ?: emptyList(),
        format = spec?.format
    )

    // ==================== 工具 ====================

    /** registryType → 运行器命令（runtimeHint 优先）。 */
    private fun runnerFor(pkg: McpPackage): String = when (pkg.registryType) {
        "npm" -> pkg.runtimeHint ?: "npx"
        "pypi" -> pkg.runtimeHint ?: "uvx"
        "oci" -> pkg.runtimeHint ?: "docker"
        "nuget" -> pkg.runtimeHint ?: "dnx"
        "cargo" -> pkg.identifier
        else -> pkg.runtimeHint ?: pkg.registryType
    }
}
