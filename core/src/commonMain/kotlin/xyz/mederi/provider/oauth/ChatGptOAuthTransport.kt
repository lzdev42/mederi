package xyz.mederi.provider.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mederi.http.MederiHttpClientFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** OAuth has bounded, non-streaming calls and never includes server bodies in errors. */
internal interface ChatGptOAuthTransport {
    suspend fun get(url: String): String
    suspend fun postForm(url: String, fields: Map<String, String>): String
}

internal class OpenAiOAuthTransport : ChatGptOAuthTransport {
    override suspend fun get(url: String): String = request(url, null)
    override suspend fun postForm(url: String, fields: Map<String, String>): String =
        request(url, encodeForm(fields))

    private suspend fun request(url: String, form: String?): String = withContext(Dispatchers.IO) {
        requireOpenAiAuthUrl(url)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", MederiHttpClientFactory.userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "OpenAI authorization request failed (HTTP $code)" }
            connection.inputStream.use {
                val bytes = it.readNBytes(1_048_577)
                check(bytes.size <= 1_048_576) { "OpenAI authorization response is too large" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun requireOpenAiAuthUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.fragment == null) { "Invalid OpenAI authorization endpoint" }
}

internal fun encodeForm(fields: Map<String, String>): String = fields.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}
