package xyz.mederi.browser

/**
 * 浏览器运行时装配点（JVM 专属）。
 *
 * core 默认用 Camoufox（[BiDiBrowserControl]，路径来自 [xyz.mederi.config.MederiConfig.camoufoxPath]）。
 * 桌面版如需 JCEF 可视化浏览器，app 层设置 [browserControlFactory]，BrowserTaskManager 优先用它。
 */
object BrowserRuntime {

    /** 浏览器工作目录（强制设置，见 [xyz.mederi.browser.install.BrowserHome]）。
     *  所有浏览器相关配置（camoufox/skills/drills/reports/records/profiles/config/version.json）都在这里。 */
    @Volatile
    var browserHome: String? = null

    /** Camoufox 二进制路径（手动指定，覆盖用；优先用 browserHome 里下载的）。 */
    @Volatile
    var camoufoxPath: String? = null
}
