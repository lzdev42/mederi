package xyz.mederi

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mederi.app.shared.generated.resources.*
import xyz.mederi.ui.UiMessage

/** Provisions a remotely managed tunnel for the desktop's actual HTTP listener. */
internal class CloudflareTunnelProvisioner(
    private val client: HttpClient,
    private val apiBase: String = "https://api.cloudflare.com/client/v4",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(apiToken: String, domain: String?, port: Int): Connection {
        val hostname = domain?.trim()?.lowercase()?.removePrefix("https://")?.removePrefix("http://")
        if (hostname == null || !hostnamePattern.matches(hostname) || hostname.length > 253) {
            throw SetupFailure(UiMessage(Res.string.settings_tunnel_invalid_domain))
        }
        val api = Api(apiToken)
        val zone = findZone(api, hostname)
        val dnsPath = "/zones/${zone.id}/dns_records"
        val records = api.get<List<DnsRecord>>("$dnsPath?name.exact=$hostname")
        val existingRecord = records.singleOrNull()
        val existingTunnelId = existingRecord?.content?.trimEnd('.')?.lowercase()
            ?.let { tunnelTarget.matchEntire(it)?.groupValues?.get(1) }
        if (records.isNotEmpty() &&
            (existingRecord?.type != "CNAME" || existingRecord.proxied != true || existingTunnelId == null)
        ) {
            throw SetupFailure(UiMessage(Res.string.settings_tunnel_dns_conflict, listOf(hostname)))
        }

        val tunnelsPath = "/accounts/${zone.account.id}/cfd_tunnel"
        val tunnelName = "mederi-$hostname"
        val listedTunnel = if (existingTunnelId == null) {
            api.get<List<Tunnel>>("$tunnelsPath?name=$tunnelName&is_deleted=false")
                .singleOrNull { it.name == tunnelName }
        } else {
            api.get<Tunnel>("$tunnelsPath/$existingTunnelId")
        }
        val tunnel = listedTunnel ?: api.write<Tunnel, CreateTunnel>(
            HttpMethod.Post, tunnelsPath, CreateTunnel(tunnelName, "cloudflare"),
        )
        if (tunnel.configSource != "cloudflare" || tunnel.deletedAt != null) {
            throw SetupFailure(UiMessage(Res.string.settings_tunnel_remote_config_required))
        }

        val configPath = "$tunnelsPath/${tunnel.id}/configurations"
        val config = when {
            listedTunnel == null -> null
            existingRecord != null -> api.get<TunnelConfiguration>(configPath).config
            // A prior provisioning attempt may have created the tunnel before writing its first config.
            else -> try {
                api.get<TunnelConfiguration>(configPath).config
            } catch (failure: ApiFailure) {
                if (failure.status != 404) throw failure
                null
            }
        }
        val updated = updateIngress(config ?: JsonObject(emptyMap()), hostname, port)
        if (updated != config) {
            api.write<JsonElement, TunnelConfiguration>(HttpMethod.Put, configPath, TunnelConfiguration(updated))
        }
        val connectorToken = api.get<String>("$tunnelsPath/${tunnel.id}/token")
        if (existingRecord == null) {
            api.write<JsonElement, DnsRecord>(
                HttpMethod.Post, dnsPath, DnsRecord("CNAME", hostname, "${tunnel.id}.cfargotunnel.com", true),
            )
        }
        return Connection(hostname, connectorToken)
    }

    private suspend fun findZone(api: Api, hostname: String): Zone {
        var candidate = hostname.substringAfter('.')
        while ('.' in candidate) {
            val zone = api.get<List<Zone>>("/zones?name=$candidate&status=active")
                .singleOrNull { it.name == candidate && it.status == "active" }
            if (zone != null) return zone
            candidate = candidate.substringAfter('.')
        }
        throw SetupFailure(UiMessage(Res.string.settings_tunnel_zone_missing, listOf(hostname)))
    }

    private fun updateIngress(config: JsonObject, hostname: String, port: Int): JsonObject {
        val rules = config["ingress"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }.toMutableList()
        val matching = rules.indices.filter {
            rules[it]["hostname"]?.jsonPrimitive?.contentOrNull.equals(hostname, ignoreCase = true) &&
                rules[it]["path"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
        }
        if (matching.size > 1) {
            throw SetupFailure(UiMessage(Res.string.settings_tunnel_route_conflict, listOf(hostname)))
        }
        val service = JsonPrimitive("http://127.0.0.1:$port")
        val index = matching.singleOrNull()
        if (index != null) {
            rules[index] = JsonObject(rules[index] + ("service" to service))
        } else {
            // Keep existing route order and put the new rule ahead of the final catch-all.
            val catchAll = rules.indexOfFirst(::isCatchAll)
            val rule = buildJsonObject {
                put("hostname", hostname)
                put("service", service)
            }
            if (catchAll >= 0) rules.add(catchAll, rule) else rules.add(rule)
        }
        if (rules.lastOrNull()?.let(::isCatchAll) != true) {
            rules.add(buildJsonObject { put("service", "http_status:404") })
        }
        return JsonObject(config + ("ingress" to JsonArray(rules)))
    }

    private fun isCatchAll(rule: JsonObject): Boolean =
        rule["hostname"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() &&
            rule["path"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()

    private inner class Api(private val token: String) {
        suspend inline fun <reified T> get(path: String): T =
            request<T>(HttpMethod.Get, path, null)

        suspend inline fun <reified T, reified B> write(method: HttpMethod, path: String, body: B): T =
            request<T>(method, path, json.encodeToString(body))

        suspend inline fun <reified T> request(method: HttpMethod, path: String, body: String?): T {
            val response = client.request("$apiBase$path") {
                this.method = method
                bearerAuth(token)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val payload = response.bodyAsText()
            val envelope = runCatching { json.decodeFromString<ApiResponse>(payload) }.getOrNull()
            if (!response.status.isSuccess() || envelope?.success != true || envelope.result == null) {
                val codes = envelope?.errors.orEmpty().joinToString { it.code.toString() }
                throw ApiFailure(response.status.value, method.value, path.substringBefore('?'), codes)
            }
            return json.decodeFromJsonElement(envelope.result)
        }
    }

    class Connection(val hostname: String, val token: String)
    open class SetupFailure(val reason: UiMessage) : Exception()
    private class ApiFailure(val status: Int, method: String, path: String, codes: String) : SetupFailure(
        UiMessage(Res.string.settings_tunnel_api_failed, listOf("$method $path", status, codes.ifEmpty { "—" })),
    )

    @Serializable
    private data class ApiResponse(val success: Boolean, val result: JsonElement? = null, val errors: List<ApiError> = emptyList())

    @Serializable
    private data class ApiError(val code: Int)

    @Serializable
    private data class Zone(val id: String, val name: String, val status: String, val account: Account)

    @Serializable
    private data class Account(val id: String)

    @Serializable
    private data class DnsRecord(val type: String, val name: String, val content: String, val proxied: Boolean? = null)

    @Serializable
    private data class Tunnel(
        val id: String,
        val name: String,
        @SerialName("config_src") val configSource: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
    )

    @Serializable
    private data class CreateTunnel(
        val name: String,
        @SerialName("config_src") val configSource: String,
    )

    @Serializable
    private data class TunnelConfiguration(val config: JsonObject? = null)

    private companion object {
        val hostnamePattern = Regex("""(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){2,}[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?""")
        val tunnelTarget = Regex("""([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\.cfargotunnel\.com""")
    }
}
