package xyz.mederi.api.impl

import xyz.mederi.api.BrowserSettingsApi
import xyz.mederi.api.BrowserStatusDto
import xyz.mederi.api.CamoufoxSettingsDto
import xyz.mederi.api.CamoufoxUpdateDto
import xyz.mederi.api.UpdateCamoufoxSettingsInput
import xyz.mederi.api.exception.MederiException
import xyz.mederi.api.exception.MederiStateException
import xyz.mederi.api.exception.mederiCall
import xyz.mederi.api.toDto
import xyz.mederi.api.toModel
import xyz.mederi.browser.BrowserSettingsManager
import xyz.mederi.browser.install.BrowserHome
import xyz.mederi.browser.install.CamoufoxInstaller
import xyz.mederi.browser.install.CamoufoxPlatform

/**
 * [BrowserSettingsApi] 实现（JVM：依赖 jvmMain 的 CamoufoxInstaller / BrowserHome）。
 *
 * [installerProvider] 为懒提供者（可能为 null）：installer 构造需要 BrowserHome，
 * 而 BrowserHome 来自 settings.browserHome——工厂构造时 home 可能尚未就绪，由调用方按需提供。
 */
class BrowserSettingsApiImpl(
    private val manager: BrowserSettingsManager,
    private val installerProvider: () -> CamoufoxInstaller?
) : BrowserSettingsApi {

    override suspend fun getSettings(): CamoufoxSettingsDto = mederiCall {
        manager.get().toDto()
    }

    override suspend fun updateSettings(input: UpdateCamoufoxSettingsInput): CamoufoxSettingsDto = mederiCall {
        manager.save(input.settings.toModel()).toDto()
    }

    override suspend fun getStatus(): BrowserStatusDto = mederiCall {
        val home = BrowserHome.of(manager.current().browserHome)
        if (home == null) {
            return@mederiCall BrowserStatusDto(
                configured = false,
                installedVersion = null,
                latestVersion = null,
                hasUpdate = false,
                supported = CamoufoxPlatform.supported,
                reason = "browserHome 未配置"
            )
        }
        if (!CamoufoxPlatform.supported) {
            return@mederiCall BrowserStatusDto(
                configured = true,
                installedVersion = null,
                latestVersion = null,
                hasUpdate = false,
                supported = false,
                reason = CamoufoxPlatform.unsupportedReason
            )
        }
        val installer = requireInstaller()
        BrowserStatusDto(
            configured = true,
            installedVersion = installer.readInstalledVersion()?.version,
            latestVersion = null,
            hasUpdate = false,
            supported = true,
            reason = ""
        )
    }

    override suspend fun checkUpdate(): CamoufoxUpdateDto = mederiCall {
        val home = BrowserHome.of(manager.current().browserHome)
        if (home == null) {
            return@mederiCall CamoufoxUpdateDto(
                configured = false,
                installedVersion = null,
                latestVersion = null,
                hasUpdate = false,
                supported = CamoufoxPlatform.supported,
                reason = "browserHome 未配置"
            )
        }
        if (!CamoufoxPlatform.supported) {
            return@mederiCall CamoufoxUpdateDto(
                configured = true,
                installedVersion = null,
                latestVersion = null,
                hasUpdate = false,
                supported = false,
                reason = CamoufoxPlatform.unsupportedReason
            )
        }
        val installer = requireInstaller()
        val info = installer.checkForUpdate()
        CamoufoxUpdateDto(
            configured = true,
            installedVersion = info.installedVersion,
            latestVersion = info.latestAvailableVersion,
            hasUpdate = info.hasUpdate,
            supported = info.supported,
            reason = info.reason
        )
    }

    override suspend fun install(versionTag: String?): Unit = mederiCall {
        requireHome()
        requireInstaller().install(versionTag)
    }

    override suspend fun listInstalledVersions(): List<String> = mederiCall {
        val home = BrowserHome.of(manager.current().browserHome)
        if (home == null) return@mederiCall emptyList()
        requireInstaller().listInstalledVersions()
    }

    private fun requireHome(): BrowserHome =
        BrowserHome.of(manager.current().browserHome)
            ?: throw MederiStateException("browserHome 未配置")

    private fun requireInstaller(): CamoufoxInstaller =
        installerProvider() ?: throw MederiStateException("browserHome 未配置")
}
