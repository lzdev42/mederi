package xyz.mederi.config

import xyz.mederi.store.ApiKeyStore
import xyz.mederi.store.DiffStore
import xyz.mederi.store.HistoryStore
import xyz.mederi.store.McpServersStore
import xyz.mederi.store.ProjectStore
import xyz.mederi.store.ProviderStore
import xyz.mederi.store.SessionStore
import xyz.mederi.store.SettingsStore

/**
 * Mederi 核心配置。
 *
 * 所有可替换组件通过此配置注入。
 * 不传的组件使用默认值（通常是不持久化的内存模式）。
 */
class MederiConfig {

    /**
     * 配置根目录的完整路径。
     *
     * 指定后，Mederi 会在此目录下创建：
     * - config.db          配置库（providers / api_keys / projects / mcp_servers）
     * - data/data.db       数据库（sessions / message_history / diffs）
     *
     * 不指定（null）= 不持久化，所有数据仅存在于内存中。
     */
    var configDir: String? = null

    /**
     * 旧版存储文件清理审批器。
     *
     * 检测到 JSON 时代的遗留文件（config/providers/、config/projects.json、
     * mcp-servers.json、data/mederi.db）时，通过它请求用户批准删除。
     * null = 遇到旧文件仅打日志提示、保留不动（不擅自删除）。
     */
    var configMigrationRequester: ConfigMigrationRequester? = null

    /**
     * 供应商配置存储。
     *
     * 如果显式传入，优先使用（企业用 PostgreSQL/MySQL/MongoDB 等）。
     * 如果指定了 [configDir]，自动创建 SqliteProviderStore（config.db）。
     * 如果都未指定 = 不持久化（InMemoryProviderStore）。
     */
    var providerStore: ProviderStore? = null

    /**
     * 项目配置存储。
     *
     * 如果显式传入，优先使用。
     * 如果指定了 [configDir]，自动创建 SqliteProjectStore（config.db）。
     * 如果都未指定 = 不持久化（InMemoryProjectStore）。
     */
    var projectStore: ProjectStore? = null

    /**
     * 对话历史存储。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteHistoryStore（data.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化对话历史。
     */
    var historyStore: HistoryStore? = null

    /**
     * API 密钥存储。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteApiKeyStore（config.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化 API 密钥。
     */
    var apiKeyStore: ApiKeyStore? = null

    /**
     * Session 元数据存储。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteSessionStore（data.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化 Session 元数据。
     */
    var sessionStore: SessionStore? = null

    /**
     * Diff 存储。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteDiffStore（data.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化 diff。
     */
    var diffStore: DiffStore? = null

    /**
     * MCP server 配置存储。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteMcpServersStore（config.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化 MCP 配置。
     */
    var mcpServerStore: McpServersStore? = null

    /**
     * 通用 key-value 配置存储（如 skill 根目录）。
     *
     * 如果指定了 [configDir]，Mederi 会自动创建 SqliteSettingsStore（config.db）。
     * 如果手动传入此值，优先使用手动传入的。
     * 如果都未指定 = 不持久化（InMemorySettingsStore）。
     */
    var settingsStore: SettingsStore? = null

    /**
     * 出站 HTTP 请求的 User-Agent 身份头值。
     *
     * 装配层（app/shared 的 MederiAiCore / server）从 `AppInfo.userAgent` 注入；
     * 注入后统一经 [xyz.mederi.http.MederiHttpClientFactory] 应用到所有 Koog 链路请求。
     */
    var userAgent: String = "Mederi/dev"

    /**
     * Camoufox 浏览器二进制路径（内核浏览器自动化的默认实现）。
     *
     * 用户在设置中配置 Camoufox 所在目录，不提供下载能力。
     * 为 null 时浏览器任务报错（引导用户先配置）。
     */
    var camoufoxPath: String? = null

    // 预留扩展点，例如：
    // var eventStore: EventStore? = null
    // var clock: Clock = Clock.System
}
