package xyz.mederi

import xyz.mederi.api.McpMarketApi
import xyz.mederi.api.McpServerApi
import xyz.mederi.api.ModelApi
import xyz.mederi.api.ProjectApi
import xyz.mederi.api.ProviderApi
import xyz.mederi.api.SessionApi
import xyz.mederi.api.impl.McpMarketApiImpl
import xyz.mederi.api.impl.McpServerApiImpl
import xyz.mederi.api.impl.ModelApiImpl
import xyz.mederi.api.impl.ProjectApiImpl
import xyz.mederi.api.impl.ProviderApiImpl
import xyz.mederi.api.impl.SessionApiImpl
import xyz.mederi.config.ConfigMigrationRequester
import xyz.mederi.config.MederiConfig
import xyz.mederi.config.MederiPaths
import xyz.mederi.mcp.market.McpMarketManagerImpl
import xyz.mederi.mcp.market.infrastructure.OfficialRegistrySource
import xyz.mederi.mcp.engine.McpConnector
import xyz.mederi.mcp.servers.McpServerManagerImpl
import xyz.mederi.project.ProjectManager
import xyz.mederi.project.ProjectManagerImpl
import xyz.mederi.provider.ProviderManager
import xyz.mederi.provider.ProviderManagerImpl
import xyz.mederi.session.SessionManager
import xyz.mederi.session.SessionManagerImpl
import xyz.mederi.store.ApiKeyStore
import xyz.mederi.store.DiffStore
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryDiffStore
import xyz.mederi.store.InMemoryHistoryStore
import xyz.mederi.store.InMemoryMcpServersStore
import xyz.mederi.store.InMemoryProjectStore
import xyz.mederi.store.InMemoryProviderStore
import xyz.mederi.store.InMemorySessionStore
import xyz.mederi.store.McpServersStore
import xyz.mederi.store.ProjectStore
import xyz.mederi.store.ProviderStore
import xyz.mederi.store.SessionStore
import xyz.mederi.db.config.MederiConfigDatabase
import xyz.mederi.db.data.MederiDataDatabase
import xyz.mederi.store.sqlite.SqliteApiKeyStore
import xyz.mederi.store.sqlite.SqliteDiffStore
import xyz.mederi.store.sqlite.SqliteHistoryStore
import xyz.mederi.store.sqlite.SqliteMcpServersStore
import xyz.mederi.store.sqlite.SqliteProjectStore
import xyz.mederi.store.sqlite.SqliteProviderStore
import xyz.mederi.store.sqlite.SqliteSessionStore

/**
 * Mederi 核心入口。
 *
 * 两种使用模式：
 * 1. [local]：本地应用模式，所有数据存在指定目录（SQLite + JSON）。
 * 2. [backend]：后端服务模式，调用方提供所有 Store（PostgreSQL/MySQL/MongoDB 等）。
 *
 * 两种模式创建后，都通过 [providers] / [projects] / [sessions] / [models] 子 API 操作，使用方式完全一致。
 *
 * 示例：
 * ```kotlin
 * // 本地应用
 * val mederi = Mederi.local(config = "/home/user/.mederi")
 *
 * // 后端服务
 * val mederi = Mederi.backend(
 *     historyStore = MyHistoryStore(db),
 *     sessionStore = MySessionStore(db),
 *     apiKeyStore = MyApiKeyStore(db),
 *     providerStore = MyProviderStore(db),
 *     projectStore = MyProjectStore(db)
 * )
 * ```
 */
class Mederi private constructor(
    private val paths: MederiPaths?,
    val historyStore: HistoryStore?,
    private val apiKeyStore: ApiKeyStore?,
    private val sessionStore: SessionStore?,
    val providers: ProviderApi,
    val projects: ProjectApi,
    val providerManager: ProviderManager,
    val projectManager: ProjectManager,
    val sessionManager: SessionManager,
    val sessions: SessionApi,
    val models: ModelApi,
    /**
     * MCP 市场：搜索 / 详情 / 生成安装配置（实时直连官方 registry，cursor 翻页，无状态）。
     */
    val mcpMarket: McpMarketApi,
    /**
     * MCP 配置管理：安装（标准 mcpServers JSON 进）/ 列表 / 编辑 / 启停 / 删除 / 可用性验证。
     */
    val mcpServers: McpServerApi,
    /**
     * models.dev 模型元数据目录（内存索引，启动后调用 [ModelCatalog.start] 开始
     * 立即拉取 + 每小时刷新；不 start 也可用，查询返回空）。
     */
    val modelCatalog: xyz.mederi.metadata.ModelCatalog
) {
    companion object {

        /**
         * 本地应用模式。
         *
         * 在指定目录下自动创建：
         * - `config/providers.json`  供应商配置
         * - `config/projects.json`   项目配置
         * - `data/mederi.db`         SQLite 数据库（对话历史 + API Key + 会话列表）
         *
         * @param config 配置目录路径。
         * @return Mederi 实例。
         */
        fun local(config: String): Mederi = create {
            configDir = expandUserHome(config)
        }

        /**
         * 后端服务模式。
         *
         * 调用方提供所有 Store，Mederi 不做任何本地文件/默认数据库假设。
         * 适用于把 Mederi 作为互联网后端 Agent 服务部署的场景。
         *
         * @param historyStore 对话历史存储。
         * @param sessionStore Session 元数据存储。
         * @param apiKeyStore API 密钥存储。
         * @param providerStore 供应商配置存储。
         * @param projectStore 项目配置存储。
         * @return Mederi 实例。
         */
        fun backend(
            historyStore: HistoryStore,
            sessionStore: SessionStore,
            apiKeyStore: ApiKeyStore,
            providerStore: ProviderStore,
            projectStore: ProjectStore
        ): Mederi = create {
            this.historyStore = historyStore
            this.sessionStore = sessionStore
            this.apiKeyStore = apiKeyStore
            this.providerStore = providerStore
            this.projectStore = projectStore
        }

        /**
         * 自定义模式。
         *
         * 通过 [MederiConfig] 显式配置所有组件。本地模式和后端模式最终都调用此方法。
         *
         * @param block 配置块。
         * @return Mederi 实例。
         */
        fun create(block: MederiConfig.() -> Unit): Mederi {
            val config = MederiConfig().apply(block)

            // 出站 HTTP User-Agent：装配层注入的版本 + 平台身份统一下发到 HTTP 工厂
            xyz.mederi.http.MederiHttpClientFactory.userAgent = config.userAgent

            val configDir = config.configDir?.let { expandUserHome(it) }

            val paths = configDir?.let { dirPath ->
                MederiPaths(dirPath).also { it.ensureDirectories() }
            }

            // === 旧版存储文件清理（须经用户批准，绝不擅删） ===
            if (paths != null) {
                handleLegacyFiles(paths, config.configMigrationRequester)
            }

            // === 双库 driver：config.db（配置）/ data.db（会话数据） ===
            // driver 由装配层创建并共享；store 实现只接收 driver。
            // Schema.create 必须在此处执行（store 不再自建连接），.sq 全部用 IF NOT EXISTS，重复调用安全。
            val configDriver = paths?.let {
                createDriver(it.configDatabaseFile.absolutePath)
                    .also { driver -> MederiConfigDatabase.Schema.create(driver) }
            }
            val dataDriver = paths?.let {
                createDriver(it.dataDatabaseFile.absolutePath)
                    .also { driver -> MederiDataDatabase.Schema.create(driver) }
            }

            // === 配置库 store（config.db） ===
            val apiKeyStore = config.apiKeyStore
                ?: configDriver?.let { SqliteApiKeyStore(it) }
                ?: InMemoryApiKeyStore()

            val providerStore = config.providerStore
                ?: configDriver?.let { SqliteProviderStore(it) }
                ?: InMemoryProviderStore()

            val projectStore = config.projectStore
                ?: configDriver?.let { SqliteProjectStore(it) }
                ?: InMemoryProjectStore()

            val mcpServerStore = config.mcpServerStore
                ?: configDriver?.let { SqliteMcpServersStore(it) }
                ?: InMemoryMcpServersStore()

            // === 数据库 store（data.db） ===
            val historyStore = config.historyStore
                ?: dataDriver?.let { SqliteHistoryStore(it) }
                ?: InMemoryHistoryStore()

            val sessionStore = config.sessionStore
                ?: dataDriver?.let { SqliteSessionStore(it) }
                ?: InMemorySessionStore()

            val diffStore = config.diffStore
                ?: dataDriver?.let { SqliteDiffStore(it) }
                ?: InMemoryDiffStore()

            // === 元数据目录（内存，UI 启动后调用 modelCatalog.start() 开始刷新） ===
            val modelCatalog = xyz.mederi.metadata.ModelCatalog()

            // === Manager 层装配 ===
            val mcpConnector = McpConnector(mcpServerStore)
            val providerManager = ProviderManagerImpl(providerStore, apiKeyStore)
            val projectManager = ProjectManagerImpl(projectStore, sessionStore, historyStore, diffStore)
            val sessionManager = SessionManagerImpl(
                sessionStore = sessionStore,
                historyStore = historyStore,
                projectManager = projectManager,
                providerManager = providerManager,
                diffStore = diffStore,
                mcpConnector = mcpConnector
            )
            val mcpServerManager = McpServerManagerImpl(mcpServerStore, mcpConnector)
            val mcpMarketManager = McpMarketManagerImpl(OfficialRegistrySource())

            // === API 层装配 ===
            val providerApi = ProviderApiImpl(providerManager, modelCatalog)
            val modelApi = ModelApiImpl(providerManager)
            val projectApi = ProjectApiImpl(projectManager)
            val sessionApi = SessionApiImpl(sessionManager)
            val mcpServerApi = McpServerApiImpl(mcpServerManager)
            val mcpMarketApi = McpMarketApiImpl(mcpMarketManager)

            return Mederi(
                paths = paths,
                historyStore = historyStore,
                apiKeyStore = apiKeyStore,
                sessionStore = sessionStore,
                providers = providerApi,
                projects = projectApi,
                providerManager = providerManager,
                projectManager = projectManager,
                sessionManager = sessionManager,
                sessions = sessionApi,
                models = modelApi,
                mcpMarket = mcpMarketApi,
                mcpServers = mcpServerApi,
                modelCatalog = modelCatalog
            )
        }

        private fun expandUserHome(path: String): String {
            return when {
                path == "~" -> System.getProperty("user.home")
                path.startsWith("~/") -> System.getProperty("user.home") + path.substring(1)
                else -> path
            }
        }

        /** 创建 SQLite driver（WAL + 外键约束），建表。 */
        private fun createDriver(dbPath: String): app.cash.sqldelight.db.SqlDriver {
            val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
                url = "jdbc:sqlite:$dbPath",
                properties = java.util.Properties().apply {
                    put("foreign_keys", "true")
                    put("journal_mode", "WAL")
                }
            )
            return driver
        }

        /**
         * 旧版存储文件（JSON 时代）检测与清理。
         *
         * 检测到旧文件时，只有 [ConfigMigrationRequester] 存在且用户批准后才删除；
         * 没有审批器 → 仅打日志提示、保留不动（绝不擅自删除用户数据）。
         * 未发布产品不做数据迁移——旧文件批准删除后，新库从空开始。
         */
        private fun handleLegacyFiles(paths: MederiPaths, requester: ConfigMigrationRequester?) {
            val candidates = listOf(
                java.io.File(paths.root, "config/providers"),
                java.io.File(paths.root, "config/projects.json"),
                java.io.File(paths.root, "config/mcp-servers.json"),
                java.io.File(paths.root, "config/providers.json"),
                java.io.File(paths.dataDir, "mederi.db"),
                java.io.File(paths.dataDir, "mederi.db-wal"),
                java.io.File(paths.dataDir, "mederi.db-shm")
            ).filter { it.exists() }
            if (candidates.isEmpty()) return

            val names = candidates.joinToString { it.name }
            if (requester == null) {
                println("[Mederi] 检测到旧版存储文件（$names），未配置审批器，保留不动。")
                return
            }

            kotlinx.coroutines.runBlocking {
                val approved = runCatching { requester.requestDeletion(candidates.map { it.absolutePath }) }
                    .getOrElse { e ->
                        println("[Mederi] 审批请求失败（$e），保留旧文件。")
                        false
                    }
                if (!approved) {
                    println("[Mederi] 用户拒绝删除旧版存储文件，保留不动。")
                    return@runBlocking
                }
                candidates.forEach { file ->
                    runCatching {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }.onFailure { e ->
                        println("[Mederi] 删除失败 ${file.name}: ${e.message}")
                    }
                }
                println("[Mederi] 旧版存储文件已清理。")
            }
        }
    }
}
