package xyz.mederi.provider.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Only this public projection may cross the host boundary; credentials are deliberately separate. */
data class ChatGptAuthState(
    val providerId: String,
    val status: String,
    val authUrl: String? = null,
    val email: String? = null,
    val error: String? = null,
)

@Serializable
internal data class ChatGptCredential(
    val clientId: String,
    val subject: String,
    val email: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAt: Long = 0,
    val scopes: Set<String> = emptySet(),
) { override fun toString(): String = "ChatGptCredential(redacted)" }

@Serializable
internal data class ChatGptHost(val hostId: String)

@Serializable
internal data class OpenIdConfiguration(
    val issuer: String,
    @SerialName("jwks_uri") val jwksUri: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
)

@Serializable
internal data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    val scope: String? = null,
) { override fun toString(): String = "OAuthTokenResponse(redacted)" }

@Serializable
internal data class IdTokenHeader(val alg: String, val kid: String)

@Serializable
internal data class IdTokenClaims(
    val iss: String,
    val sub: String,
    val aud: JsonElement,
    val exp: Long,
    val nonce: String? = null,
    val email: String? = null,
)

@Serializable
internal data class JsonWebKeySet(val keys: List<JsonWebKey>)

@Serializable
internal data class JsonWebKey(
    val kid: String,
    val kty: String,
    val alg: String? = null,
    val use: String? = null,
    val n: String? = null,
    val e: String? = null,
)
