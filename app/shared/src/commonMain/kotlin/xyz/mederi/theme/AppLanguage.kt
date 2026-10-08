package xyz.mederi.theme

/**
 * 应用语言设置（设置页-通用/外观）。tag 是 BCP 47 语言标签，直接喂给 LocalAppLocale。
 *
 * - SYSTEM：跟随系统（tag = null，不覆盖资源环境）
 * - MNC：满语（ISO 639-3 = mnc，**不是** mnc-Latn）。语言代码唯一真理源，
 *   满语 UI 翻译尚未产出（无 values-mnc/ 目录，选择后回落默认语言），
 *   保留选项是为了语言清单完整性（InkCompose 已支持满文竖排渲染）。
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM(null, ""),
    ZH("zh", "简体中文"),
    ZH_TW("zh-TW", "繁體中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    FR("fr", "Français"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    PT_BR("pt-BR", "Português (Brasil)"),
    ID("id", "Bahasa Indonesia"),
    MNC("mnc", "Manju");

    companion object {
        fun fromString(value: String?): AppLanguage =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.tag.equals(value, ignoreCase = true) }
                ?: SYSTEM
    }
}