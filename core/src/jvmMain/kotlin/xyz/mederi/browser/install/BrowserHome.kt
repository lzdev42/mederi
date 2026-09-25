package xyz.mederi.browser.install

import java.io.File

/**
 * 浏览器工作目录（强制设置，浏览器自动化的一切都放这里）。
 *
 * 目录布局：
 * ```
 * {browserHome}/
 * ├── camoufox/{version}/   ← 下载解压的 Camoufox（按版本号分目录）
 * ├── version.json          ← 当前已安装版本信息（启动检查更新用）
 * ├── skills/               ← 浏览器 skill（AI 自己写的 / 用户 copy 的）
 * ├── drills/               ← drill 脚本（AI 生成的 / 用户手写的）
 * ├── reports/              ← 自动化操作报告
 * ├── records/              ← 自动化操作记录
 * ├── profiles/             ← 浏览器 profile / 缓存（按任务/站点隔离）
 * └── config/               ← 浏览器配置（Camoufox 配置等）
 * ```
 *
 * 不提供时下载/使用浏览器会报错，引导用户先在设置里配置目录。
 */
class BrowserHome(root: File) {

    val rootDir: File = root

    val camoufoxDir: File get() = File(rootDir, "camoufox")
    val skillsDir: File get() = File(rootDir, "skills")
    val drillsDir: File get() = File(rootDir, "drills")
    val reportsDir: File get() = File(rootDir, "reports")
    val recordsDir: File get() = File(rootDir, "records")
    val profilesDir: File get() = File(rootDir, "profiles")
    val configDir: File get() = File(rootDir, "config")
    val versionFile: File get() = File(rootDir, "version.json")

    /** 创建全部子目录（幂等）。 */
    fun ensureDirectories(): BrowserHome {
        listOf(camoufoxDir, skillsDir, drillsDir, reportsDir, recordsDir, profilesDir, configDir)
            .forEach { it.mkdirs() }
        return this
    }

    /** Camoufox 某版本的安装目录：{root}/camoufox/{version}。 */
    fun camoufoxVersionDir(versionTag: String): File = File(camoufoxDir, versionTag)

    companion object {
        /** 从可空路径创建；null（未设置）返回 null。 */
        fun of(path: String?): BrowserHome? =
            path?.takeIf { it.isNotBlank() }?.let { BrowserHome(File(it)) }

        fun ofOrThrow(path: String?): BrowserHome =
            of(path) ?: error("浏览器工作目录未设置，请在设置中配置 browserHome 目录")
    }
}
