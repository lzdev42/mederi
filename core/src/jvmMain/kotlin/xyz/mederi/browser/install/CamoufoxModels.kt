package xyz.mederi.browser.install

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** GitHub release 的 asset。 */
@Serializable
data class CamoufoxReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    val size: Long = 0
)

/** GitHub release（只取我们需要的字段）。 */
@Serializable
data class CamoufoxRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("published_at")
    val publishedAt: String = "",
    val assets: List<CamoufoxReleaseAsset> = emptyList(),
    /** 非 API 字段：本 release 是否有当前平台的 asset（本地匹配后回填）。 */
    val hasPlatformAsset: Boolean = false
) {
    /** 匹配当前平台的 asset；没有返回 null。 */
    fun platformAsset(): CamoufoxReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(CamoufoxPlatform.assetSuffix) }
}

/** version.json 内容：当前已安装的 Camoufox 版本信息。 */
@Serializable
data class CamoufoxVersionInfo(
    val version: String = "",          // tag，如 "v152.0.4-beta.30"
    val os: String = "",
    val arch: String = "",
    val assetName: String = "",
    val binaryPath: String = "",
    val installedAt: String = ""
)

/** 安装/更新检查结果。 */
@Serializable
data class CamoufoxUpdateInfo(
    val installedVersion: String? = null,    // 当前已安装版本（无 = null）
    val latestAvailableVersion: String? = null, // 本平台可用的最新版本
    val hasUpdate: Boolean = false,
    val supported: Boolean = true,
    val reason: String = ""
)

object CamoufoxJson {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }
}
