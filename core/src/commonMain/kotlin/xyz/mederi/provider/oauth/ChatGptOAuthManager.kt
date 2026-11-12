package xyz.mederi.provider.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.mederi.config.MederiIdentity
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Native SIWC lifecycle. All credential handling stays in the JVM host, never the UI or server DTO. */
class ChatGptOAuthManager internal constructor(
    private val store: ChatGptCredentialStore,
    private val transport: ChatGptOAuthTransport,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AutoCloseable {
    constructor(directory: File? = null) : this(ChatGptCredentialStore(directory), OpenAiOAuthTransport())

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val failures = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, MederiIdentity.PRODUCT_NAME + "-oauth-callback").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    private data class Attempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val previous: ChatGptCredential?,
        val server: HttpServer,
        val authUrl: String,
        var timeout: Job? = null,
    )

    suspend fun start(providerId: String): ChatGptAuthState = mutex(providerId).withLock {
        check(!closed) { "ChatGPT authorization is closed" }
        cancelAttempt(providerId)
        failures.remove(providerId)
        val previous = withContext(Dispatchers.IO) { store.read(providerId) }
        val hostId = withContext(Dispatchers.IO) { store.hostId() }
        val state = randomValue()
        val nonce = randomValue()
        val verifier = randomValue()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirect = "http://127.0.0.1:${server.address.port}/auth/callback"
        val clientId = previous?.clientId ?: "dynamic_agent_client"
        val parameters = linkedMapOf(
            "client_id" to clientId,
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirect,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
        )
        if (previous == null) parameters["agent_name_hint"] = MederiIdentity.displayName
        previous?.email?.let { parameters["login_hint"] = it }
        // Deliberately omit id_token_hint: authorization URLs cross the UI boundary and must contain no token.
        val attempt = Attempt(state, nonce, verifier, redirect, previous, server,
            "$AUTHORIZE?${encodeForm(parameters)}")
        server.executor = executor
        server.createContext("/auth/callback") { exchange ->
            scope.launch { complete(providerId, attempt, exchange) }
        }
        attempts[providerId] = attempt
        try {
            server.start()
            attempt.timeout = scope.launch {
                delay(10 * 60 * 1000L)
                if (synchronized(attempts) { attempts.remove(providerId, attempt) }) {
                    failures[providerId] = "ChatGPT authorization timed out"
                    server.stop(0)
                }
            }
        } catch (failure: Exception) {
            attempts.remove(providerId, attempt)
            server.stop(0)
            throw IllegalStateException("Cannot start the ChatGPT authorization callback")
        }
        publicState(providerId)
    }

    suspend fun state(providerId: String): ChatGptAuthState = withContext(Dispatchers.IO) { publicState(providerId) }

    suspend fun cancel(providerId: String) {
        cancelAttempt(providerId)
        failures.remove(providerId)
    }

    fun isConnected(providerId: String): Boolean = runCatching { store.read(providerId)?.refreshToken != null }.getOrDefault(false)

    suspend fun accessToken(providerId: String): String = try { accessTokenLocked(providerId) }
    catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Cannot renew ChatGPT authorization; please sign in again") }

    private suspend fun accessTokenLocked(providerId: String): String = mutex(providerId).withLock {
        withContext(Dispatchers.IO) {
            store.locked(providerId) {
                // Reload under the process lock: another host process may have rotated the refresh token.
                runBlocking {
                    val credential = store.read(providerId) ?: error("ChatGPT is not connected")
                    val refreshToken = credential.refreshToken ?: error("ChatGPT is signed out")
                    if (credential.accessToken != null && credential.expiresAt > nowSeconds() + 60) {
                        credential.accessToken
                    } else {
                        val response = json.decodeFromString<OAuthTokenResponse>(transport.postForm(TOKEN, mapOf(
                            "grant_type" to "refresh_token", "client_id" to credential.clientId,
                            "refresh_token" to refreshToken, "resource" to RESOURCE,
                        )))
                        validateTokens(response)
                        val scopes = response.scope?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: credential.scopes
                        require(PLAN_SCOPE in scopes) { "ChatGPT plan permission is missing" }
                        val replacement = credential.copy(
                            accessToken = response.accessToken,
                            refreshToken = response.refreshToken ?: credential.refreshToken,
                            // A renewal need not issue an ID token; identity stays from validated sign-in.
                            idToken = response.idToken ?: credential.idToken,
                            expiresAt = expiry(response.expiresIn), scopes = scopes,
                        )
                        store.write(providerId, replacement)
                        replacement.accessToken!!
                    }
                }
            }
        }
    }

    suspend fun disconnect(providerId: String) {
        cancelAttempt(providerId)
        failures.remove(providerId)
        mutex(providerId).withLock {
            withContext(Dispatchers.IO) {
                store.locked(providerId) {
                    runBlocking {
                        val credential = store.read(providerId) ?: return@runBlocking
                        var revoked = credential.refreshToken == null
                        try {
                            credential.refreshToken?.let { token ->
                                val endpoint = discovery().revocationEndpoint
                                    ?: error("OpenAI did not provide a revocation endpoint")
                                requireOpenAiAuthUrl(endpoint)
                                transport.postForm(endpoint, mapOf("token" to token,
                                    "token_type_hint" to "refresh_token", "client_id" to credential.clientId))
                                revoked = true
                            }
                        } finally {
                            // Keep registration and identity, clear every token even when network revocation fails.
                            store.write(providerId, credential.copy(accessToken = null, refreshToken = null,
                                idToken = null, expiresAt = 0, scopes = emptySet()))
                            if (!revoked) failures[providerId] = "Signed out locally; OpenAI session revocation was not confirmed"
                        }
                    }
                }
            }
        }
    }

    /** Called only when deleting this explicitly selected provider. */
    suspend fun delete(providerId: String) {
        try { disconnect(providerId) } finally {
            withContext(Dispatchers.IO) { store.locked(providerId) { store.delete(providerId) } }
        }
    }

    private suspend fun complete(providerId: String, attempt: Attempt, exchange: HttpExchange) {
        try {
            require(exchange.requestMethod == "GET" && exchange.requestURI.path == "/auth/callback") {
                "Invalid authorization callback"
            }
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val actualState = query["state"]
            if (actualState == null || !MessageDigest.isEqual(actualState.toByteArray(), attempt.state.toByteArray())) {
                respond(exchange, 400, "Invalid authorization state. Return to Mederi and try again.")
                return // Unrelated local request must not consume the pending login.
            }
            mutex(providerId).withLock {
                check(attempts[providerId] === attempt) { "Authorization is no longer pending" }
                require(query["error"] == null) { "ChatGPT authorization was declined" }
                val code = query["code"]?.takeIf { it.isNotBlank() } ?: error("Authorization code is missing")
                val issuedClient = query["client_id"] ?: attempt.previous?.clientId
                require(!issuedClient.isNullOrBlank() && issuedClient != "dynamic_agent_client") {
                    "OpenAI client registration is incomplete"
                }
                require(attempt.previous == null || issuedClient == attempt.previous.clientId) {
                    "OpenAI returned a different registered client"
                }
                val response = json.decodeFromString<OAuthTokenResponse>(transport.postForm(TOKEN, mapOf(
                    "grant_type" to "authorization_code", "client_id" to issuedClient,
                    "code" to code, "code_verifier" to attempt.verifier,
                    "redirect_uri" to attempt.redirectUri, "resource" to RESOURCE,
                )))
                validateTokens(response)
                require(!response.refreshToken.isNullOrBlank()) { "OpenAI did not grant a renewable session" }
                val scopes = response.scope?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
                require(PLAN_SCOPE in scopes) { "ChatGPT plan permission was not granted" }
                val configuration = discovery()
                val idToken = response.idToken ?: error("OpenAI identity token is missing")
                val claims = ChatGptIdTokenVerifier(json).verify(idToken, transport.get(configuration.jwksUri),
                    configuration.issuer, issuedClient, attempt.nonce, nowSeconds())
                require(attempt.previous == null || claims.sub == attempt.previous.subject) {
                    "The authorized ChatGPT account does not match this connection"
                }
                // cancel/timeout removes the attempt before waiting for mutex; never restore cancelled tokens.
                check(attempts[providerId] === attempt) { "Authorization was cancelled" }
                withContext(Dispatchers.IO) {
                    store.locked(providerId) {
                        synchronized(attempts) {
                            check(attempts[providerId] === attempt) { "Authorization was cancelled" }
                            store.write(providerId, ChatGptCredential(issuedClient, claims.sub, claims.email,
                                response.accessToken, response.refreshToken, idToken, expiry(response.expiresIn), scopes))
                            attempts.remove(providerId, attempt)
                        }
                    }
                }
                failures.remove(providerId)
            }
            respond(exchange, 200, "ChatGPT authorization completed. Return to Mederi.")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Fixed text avoids leaking authorization codes, JWTs or server-returned secrets.
            if (attempts.remove(providerId, attempt)) failures[providerId] = "ChatGPT authorization failed; please sign in again"
            runCatching { respond(exchange, 400, "Authorization failed. Return to Mederi and try again.") }
        } finally {
            exchange.close()
            if (attempts[providerId] !== attempt) {
                attempt.timeout?.cancel()
                attempt.server.stop(0)
            }
        }
    }

    private fun publicState(providerId: String): ChatGptAuthState {
        attempts[providerId]?.let { return ChatGptAuthState(providerId, "PENDING", it.authUrl) }
        val credential = try { store.read(providerId) } catch (_: Exception) {
            return ChatGptAuthState(providerId, "FAILED", error = "Cannot read ChatGPT credential storage")
        }
        val failure = failures[providerId]
        return ChatGptAuthState(providerId,
            if (credential?.refreshToken != null) "CONNECTED" else if (failure != null) "FAILED" else "SIGNED_OUT",
            email = credential?.email, error = failure)
    }

    private suspend fun discovery(): OpenIdConfiguration {
        val result = json.decodeFromString<OpenIdConfiguration>(transport.get(DISCOVERY))
        require(result.issuer == ISSUER) { "Invalid OpenAI identity issuer" }
        requireOpenAiAuthUrl(result.jwksUri)
        result.revocationEndpoint?.let(::requireOpenAiAuthUrl)
        return result
    }
    private fun cancelAttempt(providerId: String) {
        val attempt = synchronized(attempts) { attempts.remove(providerId) } ?: return
        attempt.timeout?.cancel()
        attempt.server.stop(0)
    }
    private fun mutex(providerId: String) = mutexes.computeIfAbsent(providerId) { Mutex() }
    private fun expiry(expiresIn: Long): Long = Math.addExact(nowSeconds(), expiresIn)
    private fun validateTokens(response: OAuthTokenResponse) {
        require(response.tokenType.equals("Bearer", true) && response.accessToken.isNotBlank() && response.expiresIn > 0) {
            "OpenAI returned an invalid token grant"
        }
    }
    override fun close() {
        closed = true
        attempts.keys.toList().forEach(::cancelAttempt)
        scope.cancel()
        executor.shutdownNow()
    }
    companion object {
        internal const val ISSUER = "https://auth.openai.com"
        internal const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
        internal const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
        internal const val TOKEN = "$ISSUER/api/accounts/oauth/token"
        internal const val RESOURCE = "https://api.openai.com/v1"
        internal const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private val random = SecureRandom()
        private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        private fun parseQuery(raw: String): Map<String, String> {
            require(raw.length <= 32_768) { "Authorization callback is too large" }
            val pairs = raw.split('&').filter { it.isNotBlank() }.map {
                val split = it.split('=', limit = 2)
                URLDecoder.decode(split[0], "UTF-8") to URLDecoder.decode(split.getOrElse(1) { "" }, "UTF-8")
            }
            require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate authorization callback field" }
            return pairs.toMap()
        }
        private fun respond(exchange: HttpExchange, code: Int, message: String) {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            val bytes = message.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
