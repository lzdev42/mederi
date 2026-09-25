package xyz.mederi.core.contract.preferences

actual fun defaultPreferencesStore(): PreferencesStore = WasmJsPreferencesStore()
