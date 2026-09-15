package xyz.mederi.browser.install

import java.io.File

/**
 * 当前系统对应的 Camoufox 发布平台。
 *
 * 命名规则（GitHub daijro/camoufox releases）：`camoufox-{version}-{os}.{arch}.zip`
 * - os: `mac` / `lin` / `win`
 * - arch: `arm64` / `x86_64` / `i686`
 *
 * 不保证每个版本都同步发布全平台（例如 mac 有最新版而 win 因 bug 没发）——
 * 下载时需在 release 列表里往回找第一个有本平台 asset 的版本。
 * 平台不支持（如 win.arm64 官方没有 asset）→ [supported]=false，直接放弃。
 */
object CamoufoxPlatform {

    /** Camoufox 发布 os 段：mac / lin / win。 */
    val os: String by lazy {
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("mac") -> "mac"
            osName.contains("win") -> "win"
            else -> "lin"
        }
    }

    /** Camoufox 发布 arch 段：arm64 / x86_64 / i686。 */
    val arch: String by lazy {
        val osArch = System.getProperty("os.arch").lowercase()
        when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            osArch.contains("x86_64") || osArch.contains("amd64") -> "x86_64"
            osArch.contains("x86") || osArch.contains("i386") || osArch.contains("i686") -> "i686"
            else -> osArch
        }
    }

    /** 官方是否为本平台提供 Camoufox asset（win.arm64 等无 → false）。 */
    val supported: Boolean by lazy {
        when (os) {
            "win" -> arch != "arm64"   // 官方 win 只有 i686 / x86_64
            else -> true               // mac / lin 有 arm64 + x86_64
        }
    }

    /** asset 文件名的平台段，如 "-mac.arm64.zip"。 */
    val assetSuffix: String get() = "-$os.$arch.zip"

    /** 人类可读平台描述（日志/报错用）。 */
    val displayName: String get() = "$os/$arch"

    /** 描述不支持的原因（[supported]=false 时用）。 */
    val unsupportedReason: String get() = "Camoufox 官方不为 $displayName 提供发布包"
}
