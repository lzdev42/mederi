package xyz.mederi.core.contract.preferences

interface PreferencesStore {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun remove(key: String)

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default
    suspend fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
}
