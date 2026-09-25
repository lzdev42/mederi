package xyz.mederi.core.contract.preferences

import android.content.Context

class SharedPrefsPreferencesStore(private val context: Context) : PreferencesStore {
    // 与 PreferenceManager.getDefaultSharedPreferences 行为一致（包名命名的默认配置文件），
    // 但不引入 androidx.preference 依赖
    private val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    override suspend fun getString(key: String): String? = prefs.getString(key, null)
    override suspend fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override suspend fun remove(key: String) = prefs.edit().remove(key).apply()
}
