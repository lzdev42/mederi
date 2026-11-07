package xyz.mederi.provider.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Validate before using even the email address; decoding a JWT is not authentication. */
internal class ChatGptIdTokenVerifier(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun verify(token: String, jwks: String, issuer: String, clientId: String, nonce: String, nowSeconds: Long): IdTokenClaims {
        val pieces = token.split('.')
        require(pieces.size == 3) { "Invalid OpenAI identity token" }
        val header = json.decodeFromString<IdTokenHeader>(decode(pieces[0]).toString(Charsets.UTF_8))
        require(header.alg == "RS256") { "Unsupported OpenAI identity signature" }
        val key = json.decodeFromString<JsonWebKeySet>(jwks).keys.singleOrNull {
            it.kid == header.kid && it.kty == "RSA" && (it.alg == null || it.alg == "RS256") &&
                (it.use == null || it.use == "sig")
        } ?: error("OpenAI identity signing key was not found")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(requireNotNull(key.n))), BigInteger(1, decode(requireNotNull(key.e)))))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(pieces[2]))) { "Invalid OpenAI identity signature" }
        val claims = json.decodeFromString<IdTokenClaims>(decode(pieces[1]).toString(Charsets.UTF_8))
        require(claims.iss == issuer && claims.sub.isNotBlank() && claims.exp > nowSeconds) {
            "Invalid or expired OpenAI identity"
        }
        val audiences = when (val aud = claims.aud) {
            is JsonPrimitive -> listOf(aud.content)
            is JsonArray -> aud.map { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
        require(clientId in audiences) { "OpenAI identity was issued to a different client" }
        require(claims.nonce != null && MessageDigest.isEqual(claims.nonce.toByteArray(), nonce.toByteArray())) {
            "OpenAI identity nonce mismatch"
        }
        return claims
    }
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
