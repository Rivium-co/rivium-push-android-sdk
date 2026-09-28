package co.rivium.push.sdk.internal

import android.content.SharedPreferences
import co.rivium.protocol.PNEndpoint
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Server-provided PN Protocol endpoints with failover.
 *
 * The register response may carry
 * `"mqttEndpoints": [{ "host": "...", "port": 443, "tls": true }, ...]`.
 * Connection order: the endpoint that last worked on the current network type,
 * then the server list, then the default endpoint (`mqtt.host`/`mqtt.port`) as
 * the fallback. Without a server list only the default endpoint is used.
 */
internal object MqttEndpoints {
    const val MAX_ENDPOINTS = 5

    const val NETWORK_WIFI = "wifi"
    const val NETWORK_CELLULAR = "cellular"
    const val NETWORK_OTHER = "other"

    /**
     * Parse and validate a `mqttEndpoints` value. Invalid entries are skipped,
     * unknown fields ignored, duplicates dropped, at most [MAX_ENDPOINTS] kept.
     * Anything that is not an array yields an empty list.
     */
    fun parse(element: JsonElement?): List<PNEndpoint> {
        if (element == null || !element.isJsonArray) return emptyList()
        val result = LinkedHashSet<PNEndpoint>()
        for (item in element.asJsonArray) {
            if (result.size >= MAX_ENDPOINTS) break
            parseEntry(item)?.let { result.add(it) }
        }
        return result.toList()
    }

    fun parseJson(json: String?): List<PNEndpoint> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            parse(JsonParser.parseString(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toJson(endpoints: List<PNEndpoint>): String {
        val array = JsonArray()
        endpoints.forEach { e ->
            array.add(JsonObject().apply {
                addProperty("host", e.host)
                addProperty("port", e.port)
                addProperty("tls", e.secure)
            })
        }
        return array.toString()
    }

    private fun parseEntry(item: JsonElement?): PNEndpoint? {
        if (item == null || !item.isJsonObject) return null
        val obj = item.asJsonObject

        val hostEl = obj.get("host")
        if (hostEl == null || !hostEl.isJsonPrimitive || !hostEl.asJsonPrimitive.isString) return null
        val host = hostEl.asString.trim()
        if (!isValidHost(host)) return null

        val portEl = obj.get("port")
        if (portEl == null || !portEl.isJsonPrimitive || !portEl.asJsonPrimitive.isNumber) return null
        val portNumber = portEl.asDouble
        if (portNumber != Math.floor(portNumber) || portNumber < 1 || portNumber > 65535) return null
        val port = portNumber.toInt()

        val tlsEl = obj.get("tls")
        val tls = when {
            tlsEl == null || tlsEl.isJsonNull -> true
            tlsEl.isJsonPrimitive && tlsEl.asJsonPrimitive.isBoolean -> tlsEl.asBoolean
            else -> return null
        }
        return PNEndpoint(host, port, tls)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        return host.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }
    }

    /**
     * Connection order for one round: [lastWinner] first (only if it is still one
     * of the known endpoints), then [server] in order, then [default] last.
     * The default is only moved to the front when it is itself the last winner.
     */
    fun order(server: List<PNEndpoint>, default: PNEndpoint?, lastWinner: PNEndpoint?): List<PNEndpoint> {
        if (server.isEmpty()) return listOfNotNull(default)

        val ordered = LinkedHashSet<PNEndpoint>()
        if (lastWinner != null && (lastWinner in server || lastWinner == default)) {
            ordered.add(lastWinner)
        }
        server.filter { it != default }.forEach { ordered.add(it) }
        if (default != null) ordered.add(default)
        return ordered.toList()
    }

    /** Map NetworkMonitor.getNetworkType() to the key used to remember a winner. */
    fun networkKey(networkType: String?): String = when (networkType) {
        NETWORK_WIFI -> NETWORK_WIFI
        NETWORK_CELLULAR -> NETWORK_CELLULAR
        else -> NETWORK_OTHER
    }

    internal fun encode(endpoint: PNEndpoint): String =
        "${endpoint.host}|${endpoint.port}|${endpoint.secure}"

    internal fun decode(value: String?): PNEndpoint? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 3) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (parts[0].isEmpty() || port !in 1..65535) return null
        val secure = when (parts[2]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return PNEndpoint(parts[0], port, secure)
    }
}

/** Persistence for the server endpoint list and the per-network winner. */
internal interface EndpointStore {
    fun serverEndpoints(): List<PNEndpoint>
    fun saveServerEndpoints(endpoints: List<PNEndpoint>)
    fun winner(networkKey: String): PNEndpoint?
    fun saveWinner(networkKey: String, endpoint: PNEndpoint)
}

internal class PrefsEndpointStore(private val prefs: SharedPreferences) : EndpointStore {
    companion object {
        const val KEY_ENDPOINTS = "pn_endpoints"
        const val KEY_WINNER_PREFIX = "pn_endpoint_winner_"
    }

    override fun serverEndpoints(): List<PNEndpoint> =
        MqttEndpoints.parseJson(prefs.getString(KEY_ENDPOINTS, null))

    override fun saveServerEndpoints(endpoints: List<PNEndpoint>) {
        val editor = prefs.edit()
        if (endpoints.isEmpty()) editor.remove(KEY_ENDPOINTS)
        else editor.putString(KEY_ENDPOINTS, MqttEndpoints.toJson(endpoints))
        editor.apply()
    }

    override fun winner(networkKey: String): PNEndpoint? =
        MqttEndpoints.decode(prefs.getString(KEY_WINNER_PREFIX + networkKey, null))

    override fun saveWinner(networkKey: String, endpoint: PNEndpoint) {
        prefs.edit().putString(KEY_WINNER_PREFIX + networkKey, MqttEndpoints.encode(endpoint)).apply()
    }
}

/**
 * Chooses the endpoints for each connection round and remembers the winner per
 * network type.
 */
internal class EndpointSelector(
    private val store: EndpointStore,
    private val defaultEndpoint: () -> PNEndpoint?,
    private val networkKey: () -> String
) {
    fun endpoints(): List<PNEndpoint> {
        val key = networkKey()
        return MqttEndpoints.order(store.serverEndpoints(), defaultEndpoint(), store.winner(key))
    }

    fun onConnected(endpoint: PNEndpoint) {
        store.saveWinner(networkKey(), endpoint)
    }
}
