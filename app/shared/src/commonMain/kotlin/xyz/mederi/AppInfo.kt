package xyz.mederi

/**
 * 应用级常量与身份信息。唯一真理源，避免版本号 / 平台信息等值散落在各处。
 *
 * [VERSION] 源码不写版本字面量：唯一出处 = 仓库根 `version.json`（CI 基于 git tag 写入），
 * 构建脚本（`generateVersionSource`）读取后生成 `AppVersion.kt` 常量注入此处。运行期只读。
 */
object AppInfo {
    const val VERSION = MEDERI_APP_VERSION

    /**
     * 出站 HTTP 请求的 User-Agent 身份头，格式（RFC 9110 注释惯例）：
     * `Mederi/<version> (<os> <os-version>; <arch>)`
     *
     * 唯一真理源：所有 HTTP 客户端（koog 工厂 / ktor client）只从这里取值，
     * 禁止在调用处自行获取版本号 / OS / arch 再拼装。
     */
    val userAgent: String by lazy {
        val info = platformInfo()
        val version = VERSION.removePrefix("v").ifBlank { "dev" }
        "Mederi/$version (${info.osName} ${info.osVersion}; ${info.arch})"
    }
}

/**
 * 运行平台信息（UA 注释区使用的标准粒度：OS 名 / OS 版本 / 架构）。
 * 由各平台 [platformInfo] actual 提供，[normalizeOsName] / [normalizeArch] 统一映射。
 */
data class PlatformInfo(
    val osName: String,
    val osVersion: String,
    val arch: String,
)

/** 当前运行平台信息。expect 声明在 commonMain，各平台一个 actual，禁止调用处自行获取。 */
internal expect fun platformInfo(): PlatformInfo

/** OS 名标准化（浏览器 / 主流 SDK 的 UA 注释惯例）。 */
internal fun normalizeOsName(raw: String): String = when {
    raw.startsWith("Windows", ignoreCase = true) -> "Windows NT"
    raw.startsWith("Mac OS X", ignoreCase = true) || raw.equals("macOS", ignoreCase = true) -> "Mac OS X"
    raw.equals("Linux", ignoreCase = true) -> "Linux"
    raw.equals("Android", ignoreCase = true) -> "Android"
    raw.equals("iOS", ignoreCase = true) || raw.equals("iPhone OS", ignoreCase = true) -> "iOS"
    else -> raw
}

/** 架构标准化（aarch64→arm64、amd64→x86_64 等）。 */
internal fun normalizeArch(raw: String): String = when (raw.lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64", "x64" -> "x86_64"
    "i386", "i486", "i586", "i686", "x86" -> "x86"
    else -> raw
}
