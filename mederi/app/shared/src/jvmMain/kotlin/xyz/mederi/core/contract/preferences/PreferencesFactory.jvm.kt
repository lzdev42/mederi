package xyz.mederi.core.contract.preferences

import okio.FileSystem
import okio.Path.Companion.toPath

actual fun defaultPreferencesStore(): PreferencesStore =
    JsonFilePreferencesStore(
        file = (System.getProperty("user.home") + "/.mederi/preferences.json").toPath(),
        fileSystem = FileSystem.SYSTEM,
    )
