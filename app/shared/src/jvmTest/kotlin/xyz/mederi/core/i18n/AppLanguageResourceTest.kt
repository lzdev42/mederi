package xyz.mederi.core.i18n

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.auto_approve_title
import mederi.app.shared.generated.resources.settings_system_title
import xyz.mederi.theme.AppLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppLanguageResourceTest {

    @Test
    fun testAllLanguagesInEnum() {
        val expectedCodes = listOf(
            null, "zh", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "pt-BR", "id", "mnc"
        )
        val actualCodes = AppLanguage.entries.map { it.tag }
        assertEquals(expectedCodes, actualCodes)
    }

    @Test
    fun testFromStringResolution() {
        assertEquals(AppLanguage.ZH_TW, AppLanguage.fromString("zh-TW"))
        assertEquals(AppLanguage.JA, AppLanguage.fromString("ja"))
        assertEquals(AppLanguage.KO, AppLanguage.fromString("ko"))
        assertEquals(AppLanguage.FR, AppLanguage.fromString("fr"))
        assertEquals(AppLanguage.DE, AppLanguage.fromString("de"))
        assertEquals(AppLanguage.ES, AppLanguage.fromString("es"))
        assertEquals(AppLanguage.PT_BR, AppLanguage.fromString("pt-BR"))
        assertEquals(AppLanguage.ID, AppLanguage.fromString("id"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromString(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromString("unknown_code"))
    }

    @Test
    fun testResourceResolutionAcrossLocales() = runBlocking {
        val locales = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.ENGLISH,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.FRENCH,
            Locale.GERMAN,
            Locale.forLanguageTag("es"),
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("id"),
        )

        val originalDefault = Locale.getDefault()
        try {
            for (locale in locales) {
                Locale.setDefault(locale)
                val autoApprove = getString(Res.string.auto_approve_title)
                assertNotNull(autoApprove)
                assertTrue(autoApprove.isNotBlank(), "Auto approve title for $locale should not be blank")

                val systemTitle = getString(Res.string.settings_system_title)
                assertNotNull(systemTitle)
                assertTrue(systemTitle.isNotBlank(), "Title for $locale should not be blank")
            }
        } finally {
            Locale.setDefault(originalDefault)
        }
    }
}
