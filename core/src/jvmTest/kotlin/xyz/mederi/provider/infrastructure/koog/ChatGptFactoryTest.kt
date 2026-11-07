package xyz.mederi.provider.infrastructure.koog

import kotlin.test.*
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderAuthentication
import xyz.mederi.provider.domain.model.ProviderType

class ChatGptFactoryTest {
    private fun provider(url: String, type: ProviderType = ProviderType.OPENAI_RESPONSES,
                         auth: ProviderAuthentication = ProviderAuthentication.CHATGPT_OAUTH) = Provider(
        id = "oauth-test", name = "ChatGPT", type = type, baseUrl = url,
        authentication = auth, accessTokenProvider = { error("Validation must run before credential access") },
    )

    @Test
    fun `OAuth refuses untrusted origin scheme path and protocol before constructing client`() {
        listOf(
            "https://malicious.example/v1", "http://api.openai.com/v1",
            "https://api.openai.com/v1/extra", "https://api.openai.com/v1?redirect=example",
            "https://api.openai.com.evil.example/v1", "https://user@api.openai.com/v1",
            "https://api.openai.com:444/v1", "https://api.openai.com/v1//",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { KoogClientFactory.create(provider(url), "test-token") }
            assertFailsWith<IllegalArgumentException>(url) { KoogClientFactory.createOpenAIClient(provider(url), "test-token") }
        }
        listOf(ProviderType.OPENAI_CHAT, ProviderType.GOOGLE).forEach { type ->
            assertFailsWith<IllegalArgumentException> { KoogClientFactory.create(provider("https://api.openai.com/v1", type), "test-token") }
        }
        assertFailsWith<IllegalArgumentException> { KoogClientFactory.createGoogleClient(provider("https://api.openai.com/v1"), "test-token") }
    }

    @Test
    fun `OAuth accepts exact endpoint and a single trailing slash while API keys retain custom endpoints`() {
        listOf("https://api.openai.com/v1", "https://api.openai.com/v1/").forEach { url ->
            KoogClientFactory.create(provider(url), "test-token").close()
        }
        KoogClientFactory.create(provider("https://custom.example/v1", auth = ProviderAuthentication.API_KEY), "test-key").close()
    }
}
