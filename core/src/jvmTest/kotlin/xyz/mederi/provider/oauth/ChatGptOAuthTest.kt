package xyz.mederi.provider.oauth

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import xyz.mederi.provider.ApiKeyResolver
import xyz.mederi.provider.ProviderManagerImpl
import xyz.mederi.provider.domain.model.Provider
import xyz.mederi.provider.domain.model.ProviderAuthentication
import xyz.mederi.provider.domain.model.ProviderType
import xyz.mederi.store.InMemoryApiKeyStore
import xyz.mederi.store.InMemoryProviderStore
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ChatGptOAuthTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_900_000_000L
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private fun encoded(bytes: ByteArray) = encoder.encodeToString(bytes)
    private fun jwks(): String {
        val publicKey = key.public as RSAPublicKey
        return json.encodeToString(JsonWebKeySet(listOf(JsonWebKey("test", "RSA", "RS256", "sig",
            encoded(publicKey.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()),
            encoded(publicKey.publicExponent.toByteArray())))))
    }
    private fun token(nonce: String, issuer: String = ChatGptOAuthManager.ISSUER,
        audience: String = "oaiapp_test", expires: Long = now + 1000): String {
        val header = encoded(json.encodeToString(IdTokenHeader("RS256", "test")).toByteArray())
        val body = encoded(json.encodeToString(IdTokenClaims(issuer, "user-1", JsonPrimitive(audience), expires, nonce,
            "test@example.invalid")).toByteArray())
        val message = "$header.$body"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key.private); update(message.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$message.${encoded(signature)}"
    }
    private inner class Transport : ChatGptOAuthTransport {
        var nonce = ""
        var wrongNonce = false
        var rejectRevocation = false
        val refreshes = AtomicInteger()
        var exchangeEntered: CompletableDeferred<Unit>? = null
        var exchangeRelease: CompletableDeferred<Unit>? = null
        override suspend fun get(url: String): String = when {
            url == ChatGptOAuthManager.DISCOVERY -> json.encodeToString(OpenIdConfiguration(
                ChatGptOAuthManager.ISSUER, "https://auth.openai.com/jwks", "https://auth.openai.com/revoke"))
            url.endsWith("/jwks") -> jwks()
            else -> error("Unexpected URL")
        }
        override suspend fun postForm(url: String, fields: Map<String, String>): String {
            if (url.endsWith("/revoke")) {
                if (rejectRevocation) error("Network unavailable")
                return ""
            }
            if (fields["grant_type"] == "authorization_code") {
                exchangeEntered?.complete(Unit)
                exchangeRelease?.await()
            }
            if (fields["grant_type"] == "refresh_token") { refreshes.incrementAndGet(); delay(20) }
            return json.encodeToString(OAuthTokenResponse("secret-access", "secret-refresh-rotated",
                token(if (wrongNonce) "wrong" else nonce), "Bearer", 3600,
                "openid offline_access chatgpt.tokens.use.direct"))
        }
    }
    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    private suspend fun callback(url: String, overrides: Map<String, String> = emptyMap()): Int = withContext(Dispatchers.IO) {
        val params = query(url)
        val callback = params.getValue("redirect_uri") + "?" + encodeForm(mapOf(
            "state" to params.getValue("state"), "code" to "single-use-code", "client_id" to "oaiapp_test") + overrides)
        (URI(callback).toURL().openConnection() as HttpURLConnection).let {
            try { it.connectTimeout = 2000; it.readTimeout = 5000; it.responseCode } finally { it.disconnect() }
        }
    }
    private suspend fun login(manager: ChatGptOAuthManager, transport: Transport): ChatGptAuthState {
        val pending = manager.start("provider")
        transport.nonce = query(pending.authUrl!!).getValue("nonce")
        assertEquals(200, callback(pending.authUrl))
        return manager.state("provider")
    }

    @Test fun `OIDC requires signature audience issuer nonce and expiry`() {
        val verifier = ChatGptIdTokenVerifier()
        assertEquals("user-1", verifier.verify(token("nonce"), jwks(), ChatGptOAuthManager.ISSUER, "oaiapp_test", "nonce", now).sub)
        for (bad in listOf(token("bad"), token("nonce", issuer = "https://attacker.invalid"),
            token("nonce", audience = "other-client"), token("nonce", expires = now - 1))) {
            assertFails { verifier.verify(bad, jwks(), ChatGptOAuthManager.ISSUER, "oaiapp_test", "nonce", now) }
        }
        val original = token("nonce").split('.')
        val tampered = original[0] + "." + encoded("{}".toByteArray()) + "." + original[2]
        assertFails { verifier.verify(tampered, jwks(), ChatGptOAuthManager.ISSUER, "oaiapp_test", "nonce", now) }
    }

    @Test fun `login checks state issues isolated connection and never exposes token`() = runBlocking {
        val transport = Transport()
        val manager = ChatGptOAuthManager(ChatGptCredentialStore(null), transport) { now }
        try {
            val pending = manager.start("provider")
            val params = query(pending.authUrl!!)
            assertEquals("dynamic_agent_client", params["client_id"])
            assertEquals("S256", params["code_challenge_method"])
            assertTrue(params.getValue("ext_agent_host_id").startsWith("urn:uuid:"))
            assertFalse("id_token_hint" in params)
            transport.nonce = params.getValue("nonce")
            assertEquals(400, callback(pending.authUrl, mapOf("state" to "wrong")))
            assertEquals("PENDING", manager.state("provider").status)
            assertEquals(200, callback(pending.authUrl))
            val state = manager.state("provider")
            assertEquals("CONNECTED", state.status)
            assertEquals("test@example.invalid", state.email)
            assertFalse(state.toString().contains("secret"))
            assertNull(state.authUrl)
            assertEquals("secret-access", manager.accessToken("provider"))
        } finally { manager.close() }
    }

    @Test fun `cancel and invalid nonce never persist credentials`() = runBlocking {
        val store = ChatGptCredentialStore(null)
        val transport = Transport()
        val manager = ChatGptOAuthManager(store, transport) { now }
        try {
            manager.start("cancelled")
            manager.cancel("cancelled")
            assertEquals("SIGNED_OUT", manager.state("cancelled").status)
            transport.wrongNonce = true
            val pending = manager.start("provider")
            transport.nonce = query(pending.authUrl!!).getValue("nonce")
            assertEquals(400, callback(pending.authUrl))
            assertEquals("FAILED", manager.state("provider").status)
            assertNull(store.read("provider"))
        } finally { manager.close() }
    }

    @Test fun `cancelling during token exchange cannot restore credentials`() = runBlocking {
        val store = ChatGptCredentialStore(null)
        val transport = Transport().apply {
            exchangeEntered = CompletableDeferred()
            exchangeRelease = CompletableDeferred()
        }
        val manager = ChatGptOAuthManager(store, transport) { now }
        try {
            val pending = manager.start("provider")
            transport.nonce = query(pending.authUrl!!).getValue("nonce")
            val request = async { runCatching { callback(pending.authUrl) } }
            withTimeout(5000) { transport.exchangeEntered!!.await() }
            manager.cancel("provider")
            transport.exchangeRelease!!.complete(Unit)
            // accessToken takes the same mutex held by callback processing. Acquiring it proves
            // the in-flight exchange has passed its save guard, without timing-dependent sleeps.
            assertFails { manager.accessToken("provider") }
            request.await()
            assertNull(store.read("provider"))
            assertEquals("SIGNED_OUT", manager.state("provider").status)
        } finally { manager.close() }
    }

    @Test fun `refresh is serialized and revocation failure still clears every token`() = runBlocking {
        val store = ChatGptCredentialStore(null)
        store.write("provider", ChatGptCredential("oaiapp_test", "user-1", accessToken = "old", refreshToken = "refresh",
            idToken = "old-id", expiresAt = now - 1, scopes = setOf(ChatGptOAuthManager.PLAN_SCOPE)))
        val transport = Transport()
        val manager = ChatGptOAuthManager(store, transport) { now }
        try {
            val values = (1..20).map { async(Dispatchers.Default) { manager.accessToken("provider") } }.awaitAll()
            assertTrue(values.all { it == "secret-access" })
            assertEquals(1, transport.refreshes.get())
            assertEquals("secret-refresh-rotated", store.read("provider")!!.refreshToken)
            transport.rejectRevocation = true
            assertFails { manager.disconnect("provider") }
            assertFalse(manager.isConnected("provider"))
            val cleared = store.read("provider")!!
            assertNull(cleared.accessToken); assertNull(cleared.refreshToken); assertNull(cleared.idToken)
            assertEquals("oaiapp_test", cleared.clientId)
            assertTrue(manager.state("provider").error!!.contains("not confirmed"))
        } finally { manager.close() }
    }

    @Test fun `separate managers reload rotated tokens under shared file lock`() = runBlocking {
        val directory = Files.createTempDirectory("mederi-chatgpt-lock-test").toFile()
        val transport = Transport()
        val store = ChatGptCredentialStore(directory)
        store.write("provider", ChatGptCredential("client", "subject", accessToken = "old",
            refreshToken = "old-refresh", expiresAt = now - 1, scopes = setOf(ChatGptOAuthManager.PLAN_SCOPE)))
        val first = ChatGptOAuthManager(store, transport) { now }
        val second = ChatGptOAuthManager(ChatGptCredentialStore(directory), transport) { now }
        try {
            val results = listOf(first, second).map { manager ->
                async(Dispatchers.Default) { manager.accessToken("provider") }
            }.awaitAll()
            assertEquals(listOf("secret-access", "secret-access"), results)
            assertEquals(1, transport.refreshes.get())
        } finally {
            first.close(); second.close(); directory.deleteRecursively()
        }
    }

    @Test fun `protected storage reloads preserves host and excludes tokens from provider JSON`() = runBlocking {
        val directory = Files.createTempDirectory("mederi-chatgpt-test").toFile()
        try {
            val store = ChatGptCredentialStore(directory)
            val host = store.hostId()
            store.write("provider", ChatGptCredential("client", "subject", refreshToken = "secret"))
            val reloaded = ChatGptCredentialStore(directory)
            assertEquals(host, reloaded.hostId())
            assertEquals("secret", reloaded.read("provider")!!.refreshToken)
            if (Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix")) {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(directory.resolve("provider.json").toPath())))
            } else {
                for (path in listOf(directory, directory.resolve("provider.json"), directory.resolve("host.json"),
                    directory.resolve("host.lock"))) {
                    val view = Files.getFileAttributeView(path.toPath(), java.nio.file.attribute.AclFileAttributeView::class.java)
                    val entry = view.acl.single()
                    assertEquals(view.owner, entry.principal(), "only the owner may access ${path.name}")
                    assertEquals(java.nio.file.attribute.AclEntryType.ALLOW, entry.type())
                }
            }
            val provider = Provider("id", "ChatGPT", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1",
                authentication = ProviderAuthentication.CHATGPT_OAUTH, accessTokenProvider = { "secret" })
            assertFalse(json.encodeToString(provider).contains("secret"))
            directory.resolve("provider.json").writeText("{\"refreshToken\":\"leak-me\"")
            val error = assertFails { reloaded.read("provider") }
            assertFalse(error.toString().contains("leak-me"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `OAuth providers reject key rotation arbitrary endpoints and coexist with API keys`() = runBlocking {
        val transport = Transport()
        val store = ChatGptCredentialStore(null)
        val auth = ChatGptOAuthManager(store, transport) { now }
        val manager = ProviderManagerImpl(InMemoryProviderStore(), InMemoryApiKeyStore(), auth)
        try {
            val provider = manager.createChatGptProvider()
            assertEquals(ProviderAuthentication.CHATGPT_OAUTH, provider.authentication)
            assertTrue(manager.listKeys(provider.id).isEmpty())
            assertFails { manager.addKey(provider.id, "bad", "key", true) }
            assertFails { manager.update(provider.id, null, "https://attacker.invalid", null) }
            store.write(provider.id, ChatGptCredential("client", "subject", accessToken = "oauth-token", refreshToken = "refresh",
                expiresAt = now + 3600))
            assertEquals("oauth-token", ApiKeyResolver(manager).resolve(provider.id, "__rotate__").value)
            val ordinary = manager.create("Other", ProviderType.OPENAI_CHAT, "https://other.invalid/v1", null, false)
            manager.addKey(ordinary.id, "ordinary", "api-key", true)
            assertEquals("api-key", ApiKeyResolver(manager).resolve(ordinary.id).value)
            assertEquals(2, manager.list().size)
        } finally { auth.close() }
    }
}
