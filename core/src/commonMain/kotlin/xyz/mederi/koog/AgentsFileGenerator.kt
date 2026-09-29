package xyz.mederi.infrastructure.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import xyz.mederi.domain.model.AIModel
import xyz.mederi.project.AgentsFileLoader
import xyz.mederi.project.ProjectManager
import xyz.mederi.provider.ApiKeyResolver
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.domain.model.ReasoningLevel
import xyz.mederi.provider.infrastructure.koog.KoogClientFactory
import xyz.mederi.provider.infrastructure.koog.KoogModelBuilder
import xyz.mederi.provider.infrastructure.koog.KoogParamsBuilder
import xyz.mederi.session.SessionManager
import xyz.mederi.tools.FileSystemTools
import java.io.File

/** 防御：模型无视"裸输出"指令时，剥掉首尾包裹的代码围栏。 */
internal fun stripCodeFence(text: String): String {
    if (!text.startsWith("```")) return text
    val lines = text.lines()
    val last = lines.lastOrNull() ?: return text
    if (!last.startsWith("```")) return text
    return lines.drop(1).dropLast(1).joinToString("\n").trim()
}

/**
 * AGENTS.md 生成服务（API 形态，暂无命令/UI 入口——用不用以后再说）。
 *
 * mini agent 模式（对照 [TurnExecutor.compressOnce]）：read_file / list_directory
 * 两个只读工具限定在项目目录内扫仓库，最终回复即完整 AGENTS.md markdown，
 * 由本类代码写盘到项目根。已存在时原地改进：现有内容喂给模型，
 * 保真用户已写内容、只修错补缺。
 *
 * 模型解析顺序：显式 modelId → 项目最近一次会话选用的模型；都没有则 failure。
 */
class AgentsFileGenerator(
    private val projectManager: ProjectManager,
    private val sessionManager: SessionManager,
    private val providerManager: ProviderManager
) {

    // API Key 唯一真理源解析器（记忆为进程级静态共享，与 TurnExecutor 的实例共享同一份记忆）
    private val apiKeyResolver = ApiKeyResolver(providerManager)

    /**
     * 扫描项目并生成（或改进）项目根的 AGENTS.md。
     *
     * @param projectId 项目 ID。
     * @param modelId 指定模型 ID；null 时用项目最近会话的模型。
     * @return 成功时返回写入的 AGENTS.md 内容。
     */
    suspend fun generate(projectId: String, modelId: String? = null): Result<String> = runCatching {
        val project = projectManager.require(projectId)

        // ── 模型解析 ──
        val aiModel = resolveModel(projectId, modelId)
            ?: error(
                "No AI model available: pass modelId explicitly, or select a model in a " +
                    "session of this project first (its latest model is reused)"
            )
        val provider = providerManager.listWithoutKeys()
            .firstOrNull { p -> p.models.any { it.id == aiModel.id } }
            ?: error("Provider for model ${aiModel.id} not found")
        // resolve 无 key 时抛 IllegalStateException（文案与原先的 error 一致），
        // 外层 runCatching 捕获为 Result.failure，行为一致。
        val apiKey = apiKeyResolver.resolve(provider.id, null).value

        // ── mini agent：只读工具扫仓库 ──
        val client = KoogClientFactory.create(provider, apiKey)
        val executor = PromptExecutorBuilder()
            .addClient(client)
            .build()

        val koogModel = KoogModelBuilder.build(aiModel, provider.type)
        val params = KoogParamsBuilder.build(
            type = provider.type,
            reasoningLevel = ReasoningLevel.NONE,
            reasoningParameter = provider.reasoningParameter,
            maxTokens = aiModel.maxTokens
        )

        val registry = ToolRegistry {
            val fsTools = FileSystemTools(listOf(project.directory))
            tool(fsTools.ReadFileTool())
            tool(fsTools.ListDirectoryTool())
        }

        val existing = File(project.directory, AgentsFileLoader.FILE_NAME)
            .takeIf { it.isFile }?.readText()

        val koogPrompt = prompt("agents_file_generation", params = params) {
            system(GENERATION_SYSTEM_PROMPT)
            user(userPrompt(project.directory, existing))
        }

        val agentConfig = AIAgentConfig.builder()
            .model(koogModel)
            .prompt(koogPrompt)
            // 扫仓库要多次 read/list（每次工具循环耗 2+ 迭代），给足余量
            .maxAgentIterations(100)
            .serializer(mederiToolSerializer)
            .build()

        val agent = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(agentConfig)
            .graphStrategy(mederiSingleRunStrategy())
            .build()

        val output = agent.run("", sessionId = "agents_gen_${projectId}")
        val content = stripCodeFence(output.trim())
        check(content.isNotBlank()) { "Generation produced empty content" }

        File(project.directory, AgentsFileLoader.FILE_NAME).writeText(content)
        content
    }

    /** 显式 modelId → 项目最近会话的模型；都没有返回 null。 */
    private suspend fun resolveModel(projectId: String, modelId: String?): AIModel? {
        if (modelId != null) {
            val provider = providerManager.listWithoutKeys()
                .firstOrNull { p -> p.models.any { it.id == modelId } }
                ?: return null
            return provider.getModel(modelId)
        }
        return sessionManager.listByProject(projectId)
            .filter { it.aiModel != null }
            .maxByOrNull { it.updatedAt }
            ?.aiModel
    }

    private fun userPrompt(projectDirectory: String, existing: String?): String = buildString {
        appendLine("Project root: $projectDirectory")
        appendLine()
        if (existing == null) {
            appendLine("Generate the AGENTS.md for the project at the root above.")
        } else {
            appendLine("An AGENTS.md already exists at the project root. Improve it in place:")
            appendLine("keep accurate content and user-authored intent, fix what is wrong,")
            appendLine("add what is missing. Do not drop user content.")
            appendLine()
            appendLine("<existing>")
            appendLine(existing)
            appendLine("</existing>")
        }
    }

    private companion object {
        val GENERATION_SYSTEM_PROMPT = """
You are generating an AGENTS.md file for a software project. AGENTS.md is a standard
instruction file that AI coding agents read automatically to work effectively in a
repository.

Explore the repository with list_directory and read_file:
- Identify the tech stack, build system, and module layout
- Read build files (build.gradle.kts, settings.gradle.kts, package.json, Cargo.toml,
  pyproject.toml...), README, and key configs
- Look at test sources to find how tests are run and with what command
- Sample a few representative source files to detect the conventions actually in use

Then produce the AGENTS.md with sections like:
- Project overview (what it is, tech stack, key dependencies)
- Module/directory layout (what lives where)
- Build / run / test commands (exact commands, verified from build files)
- Code conventions (naming, formatting, patterns in actual use)
- Hard rules (anything critical not to break)

HARD REQUIREMENTS:
- Ground every statement in what you actually read — never invent commands or paths.
- Be concise: instructions an agent needs, not documentation for humans.
- Your FINAL message must be ONLY the raw markdown content of the AGENTS.md —
  no preamble, no explanation, no surrounding code fences.
"""
    }
}
