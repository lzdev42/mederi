package xyz.mederi.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException

/**
 * 调研工具独立的只读 HTTP 入口，不借 shell 绕过命令网络授权。
 * GET / http(s)、限制重定向/总时长/响应字节；不执行网页返回内容、不携带本机凭据。
 */
internal object WebHttpReader {
    private const val MAX_BYTES = 2 * 1024 * 1024

    suspend fun get(url: String, timeoutSeconds: Int, userAgent: String): String {
        require(timeoutSeconds in 1..300) { "Web timeout must be between 1 and 300 seconds" }
        val context = currentCoroutineContext()
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        return runInterruptible(Dispatchers.IO) {
            fun remaining(): Int {
                context.ensureActive()
                val millis = (deadline - System.nanoTime()) / 1_000_000
                if (millis <= 0) throw SocketTimeoutException("Web request timed out")
                return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            }
            var target = URI(url)
            var result: String? = null
            for (redirect in 0..5) {
                require(target.scheme?.lowercase() in listOf("http", "https")) { "Only http/https URLs are allowed" }
                require(target.userInfo == null) { "Credentials in URLs are not supported" }
                val connection = target.toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = remaining()
                    connection.readTimeout = remaining()
                    connection.setRequestProperty("User-Agent", userAgent)
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        require(redirect < 5) { "Too many HTTP redirects" }
                        val location = connection.getHeaderField("Location") ?: error("Redirect has no Location")
                        target = target.resolve(location)
                        continue
                    }
                    require(status in 200..299) { "HTTP $status" }
                    val captured = ByteArrayOutputStream()
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        while (captured.size() < MAX_BYTES) {
                            connection.readTimeout = minOf(remaining(), 1000)
                            val count = try { stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES - captured.size())) }
                            catch (_: SocketTimeoutException) { remaining(); continue }
                            if (count < 0) break
                            captured.write(buffer, 0, count)
                        }
                    }
                    result = captured.toString(Charsets.UTF_8.name())
                    break
                } finally { connection.disconnect() }
            }
            result ?: error("No HTTP response")
        }
    }
}
