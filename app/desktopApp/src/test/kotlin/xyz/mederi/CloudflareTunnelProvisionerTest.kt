package xyz.mederi

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class CloudflareTunnelProvisionerTest {
    private val hostname = "m.example.com"
    private val tunnelId = "efd7d540-202e-49ad-abf7-17925c2c42b2"
    private val tunnelPath = "/accounts/account/cfd_tunnel"
    private val dnsPath = "/zones/zone/dns_records"
    private val zone = """[{"id":"zone","name":"example.com","status":"active","account":{"id":"account"}}]"""
    private val tunnel = """{"id":"$tunnelId","name":"mederi-$hostname","config_src":"cloudflare"}"""
    private val record = """{"type":"CNAME","name":"$hostname","content":"$tunnelId.cfargotunnel.com","proxied":true}"""
    private val config = """{"ingress":[{"hostname":"$hostname","service":"http://127.0.0.1:8081","originRequest":{"noTLSVerify":false}},{"hostname":"other.example.com","service":"http://localhost:9090"},{"service":"http_status:404"}]}"""

    @Test
    fun changedActualPortUpdatesOnlyMederiServiceAndNeverWritesDns() {
        val requests = configureExisting(port = 49321)
        val update = requests.single { it.method == "PUT" }
        assertEquals("$tunnelPath/$tunnelId/configurations", update.path)
        val expected = Json.parseToJsonElement(config.replace("127.0.0.1:8081", "127.0.0.1:49321"))
        assertEquals(expected, Json.parseToJsonElement(update.body).jsonObject["config"])
        assertFalse(requests.any { it.method == "POST" })
    }

    @Test
    fun matchingPortDoesNotWriteAnyCloudflareConfiguration() {
        assertTrue(configureExisting(port = 8081).all { it.method == "GET" })
    }

    @Test
    fun newHostnameCreatesTunnelRouteAndProxiedCname() {
        val requests = withCloudflare({ request ->
            when (request.method to request.path) {
                "GET" to "/zones" -> zone
                "GET" to dnsPath, "GET" to tunnelPath -> "[]"
                "POST" to tunnelPath -> tunnel
                "PUT" to "$tunnelPath/$tunnelId/configurations" -> "{}"
                "GET" to "$tunnelPath/$tunnelId/token" -> "\"connector-token\""
                "POST" to dnsPath -> record
                else -> null
            }
        }) { provisioner ->
            val result = provisioner.prepare("api-token", hostname, 49321)
            assertEquals(hostname, result.hostname)
            assertEquals("connector-token", result.token)
        }
        assertEquals(listOf(tunnelPath, "$tunnelPath/$tunnelId/configurations", dnsPath),
            requests.filter { it.method != "GET" }.map { it.path })
        val creation = requests.single { it.method == "POST" && it.path == tunnelPath }
        assertEquals("cloudflare", Json.parseToJsonElement(creation.body).jsonObject["config_src"]?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement(record), Json.parseToJsonElement(requests.last().body))
    }

    @Test
    fun unrelatedDnsRecordStopsProvisioningBeforeAnyWrites() {
        val requests = withCloudflare({ request ->
            when (request.path) {
                "/zones" -> zone
                dnsPath -> """[{"type":"A","name":"$hostname","content":"192.0.2.1","proxied":true}]"""
                else -> null
            }
        }) { provisioner ->
            assertFailsWith<CloudflareTunnelProvisioner.SetupFailure> {
                provisioner.prepare("api-token", hostname, 8081)
            }
        }
        assertEquals(listOf("/zones", dnsPath), requests.map { it.path })
    }

    private fun configureExisting(port: Int): List<Request> = withCloudflare({ request ->
        when (request.method to request.path) {
            "GET" to "/zones" -> zone
            "GET" to dnsPath -> "[$record]"
            "GET" to "$tunnelPath/$tunnelId" -> tunnel
            "GET" to "$tunnelPath/$tunnelId/configurations" -> """{"config":$config}"""
            "PUT" to "$tunnelPath/$tunnelId/configurations" -> "{}"
            "GET" to "$tunnelPath/$tunnelId/token" -> "\"connector-token\""
            else -> null
        }
    }) { provisioner ->
        assertEquals("connector-token", provisioner.prepare("api-token", hostname, port).token)
    }

    private fun withCloudflare(
        respond: (Request) -> String?,
        verify: suspend (CloudflareTunnelProvisioner) -> Unit,
    ): List<Request> {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val request = Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestBody.bufferedReader().readText())
                requests += request
                val result = respond(request).takeIf { exchange.requestHeaders.getFirst("Authorization") == "Bearer api-token" }
                val body = if (result == null) """{"success":false}""" else """{"success":true,"result":$result}"""
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(if (result == null) 400 else 200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            HttpClient(CIO).use { client ->
                runBlocking { verify(CloudflareTunnelProvisioner(client, "http://127.0.0.1:${server.address.port}")) }
            }
        } finally {
            server.stop(0)
        }
        return requests.toList()
    }

    private data class Request(val method: String, val path: String, val body: String)
}
