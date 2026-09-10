package xyz.mederi.config

/**
 * 旧版存储文件（JSON 时代遗留）的清理审批接口。
 *
 * 产品未发布，开发期允许破坏性数据结构变更——但删除用户的任何数据文件
 * 前必须先通知用户，经批准才能执行。
 *
 * 检测到旧版文件（config/providers/、config/projects.json、mcp-servers.json、data/mederi.db）时，
 * Mederi 启动流程会调用 [requestDeletion]；实现方呈现文件清单给用户，
 * 用户批准后执行删除并返回 true；拒绝返回 false（旧文件原样保留，新库空配置运行）。
 */
interface ConfigMigrationRequester {

    /**
     * 请求用户批准删除旧版存储文件。
     *
     * @param legacyFiles 检测到的旧版文件/目录绝对路径列表。
     * @return true = 用户批准删除（调用方负责实际删除）；false = 用户拒绝，保留旧文件。
     */
    suspend fun requestDeletion(legacyFiles: List<String>): Boolean
}
