package co.rivium.push.sdk.internal

import co.rivium.protocol.PNEndpoint
import co.rivium.push.sdk.ApiClient
import co.rivium.push.sdk.PNSocketManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttEndpointsTest {

    private fun parse(json: String) = MqttEndpoints.parse(JsonParser.parseString(json))

    private val default = PNEndpoint("push.rivium.co", 8883, true)
    private val e443 = PNEndpoint("edge1.rivium.co", 443, true)
    private val e8443 = PNEndpoint("edge2.rivium.co", 8443, true)
    private val plain = PNEndpoint("edge3.rivium.co", 1883, false)

    // ---------- parsing / validation ----------

    @Test
    fun `parses valid entries in order and defaults tls to true`() {
        val list = parse("""[
            {"host":"edge1.rivium.co","port":443,"tls":true},
            {"host":"edge2.rivium.co","port":8443},
            {"host":"edge3.rivium.co","port":1883,"tls":false}
        ]""")
        assertEquals(listOf(e443, e8443, plain), list)
    }

    @Test
    fun `ignores unknown fields and trims host`() {
        val list = parse("""[{"host":"  edge1.rivium.co ","port":443,"tls":true,"weight":5,"region":"eu"}]""")
        assertEquals(listOf(e443), list)
    }

    @Test
    fun `skips invalid entries`() {
        val list = parse("""[
            {"host":"","port":443},
            {"host":"   ","port":443},
            {"port":443},
            {"host":"a b","port":443},
            {"host":"x.rivium.co/path","port":443},
            {"host":"x.rivium.co","port":0},
            {"host":"x.rivium.co","port":65536},
            {"host":"x.rivium.co","port":-1},
            {"host":"x.rivium.co","port":443.5},
            {"host":"x.rivium.co","port":"443"},
            {"host":"x.rivium.co"},
            {"host":"x.rivium.co","port":443,"tls":"yes"},
            {"host":123,"port":443},
            "edge.rivium.co:443",
            null,
            42,
            {"host":"edge1.rivium.co","port":443}
        ]""")
        assertEquals(listOf(e443), list)
    }

    @Test
    fun `accepts port bounds`() {
        val list = parse("""[{"host":"a","port":1},{"host":"b","port":65535}]""")
        assertEquals(listOf(PNEndpoint("a", 1, true), PNEndpoint("b", 65535, true)), list)
    }

    @Test
    fun `keeps at most five entries and drops duplicates`() {
        val entries = (1..8).joinToString(",") { """{"host":"h$it.rivium.co","port":443}""" }
        val list = parse("""[{"host":"h1.rivium.co","port":443},$entries]""")
        assertEquals(MqttEndpoints.MAX_ENDPOINTS, list.size)
        assertEquals((1..5).map { PNEndpoint("h$it.rivium.co", 443, true) }, list)
    }

    @Test
    fun `non array or missing value yields empty list`() {
        assertTrue(MqttEndpoints.parse(null).isEmpty())
        assertTrue(parse("""{"host":"a","port":443}""").isEmpty())
        assertTrue(parse("\"a\"").isEmpty())
        assertTrue(parse("null").isEmpty())
        assertTrue(MqttEndpoints.parseJson("not json [").isEmpty())
        assertTrue(MqttEndpoints.parseJson(null).isEmpty())
    }

    @Test
    fun `register response without mqttEndpoints keeps working`() {
        val response = Gson().fromJson(
            """{"deviceId":"d1","appId":"app","mqtt":{"host":"push.rivium.co","port":8883,"secure":true,"token":"t"}}""",
            ApiClient.RegisterResponse::class.java
        )
        assertEquals("push.rivium.co", response.mqtt?.host)
        assertTrue(MqttEndpoints.parse(response.mqttEndpoints).isEmpty())
    }

    @Test
    fun `register response with mqttEndpoints is parsed`() {
        val response = Gson().fromJson(
            """{"deviceId":"d1","somethingNew":{"a":1},"mqtt":{"host":"push.rivium.co","port":8883},
               "mqttEndpoints":[{"host":"edge1.rivium.co","port":443,"tls":true},{"host":"","port":1}]}""",
            ApiClient.RegisterResponse::class.java
        )
        assertEquals(listOf(e443), MqttEndpoints.parse(response.mqttEndpoints))
    }

    @Test
    fun `json round trip`() {
        val list = listOf(e443, plain)
        assertEquals(list, MqttEndpoints.parseJson(MqttEndpoints.toJson(list)))
        assertEquals(e8443, MqttEndpoints.decode(MqttEndpoints.encode(e8443)))
        assertNull(MqttEndpoints.decode("garbage"))
        assertNull(MqttEndpoints.decode("h|0|true"))
        assertNull(MqttEndpoints.decode("h|443|maybe"))
    }

    // ---------- ordering ----------

    @Test
    fun `no server list means default endpoint only`() {
        assertEquals(listOf(default), MqttEndpoints.order(emptyList(), default, null))
        // a remembered winner is ignored when the server sends nothing
        assertEquals(listOf(default), MqttEndpoints.order(emptyList(), default, e443))
    }

    @Test
    fun `server list order then default last`() {
        assertEquals(
            listOf(e443, e8443, default),
            MqttEndpoints.order(listOf(e443, e8443), default, null)
        )
    }

    @Test
    fun `last winner goes first`() {
        assertEquals(
            listOf(e8443, e443, default),
            MqttEndpoints.order(listOf(e443, e8443), default, e8443)
        )
    }

    @Test
    fun `default that won last time goes first`() {
        assertEquals(
            listOf(default, e443, e8443),
            MqttEndpoints.order(listOf(e443, e8443), default, default)
        )
    }

    @Test
    fun `stale winner no longer offered by the server is ignored`() {
        assertEquals(
            listOf(e443, default),
            MqttEndpoints.order(listOf(e443), default, plain)
        )
    }

    @Test
    fun `default listed by the server is not duplicated and stays last`() {
        assertEquals(
            listOf(e443, default),
            MqttEndpoints.order(listOf(default, e443), default, null)
        )
    }

    @Test
    fun `network type mapping`() {
        assertEquals("wifi", MqttEndpoints.networkKey("wifi"))
        assertEquals("cellular", MqttEndpoints.networkKey("cellular"))
        assertEquals("other", MqttEndpoints.networkKey("vpn"))
        assertEquals("other", MqttEndpoints.networkKey("ethernet"))
        assertEquals("other", MqttEndpoints.networkKey(null))
    }

    // ---------- per-network memory ----------

    private class FakeStore(var server: List<PNEndpoint> = emptyList()) : EndpointStore {
        val winners = mutableMapOf<String, PNEndpoint>()
        override fun serverEndpoints() = server
        override fun saveServerEndpoints(endpoints: List<PNEndpoint>) { server = endpoints }
        override fun winner(networkKey: String) = winners[networkKey]
        override fun saveWinner(networkKey: String, endpoint: PNEndpoint) { winners[networkKey] = endpoint }
    }

    @Test
    fun `winner is remembered per network type`() {
        val store = FakeStore(listOf(e443, e8443))
        var network = "wifi"
        val selector = EndpointSelector(store, { default }, { network })

        assertEquals(listOf(e443, e8443, default), selector.endpoints())

        // on Wi-Fi the second endpoint worked
        selector.onConnected(e8443)
        assertEquals(listOf(e8443, e443, default), selector.endpoints())

        // cellular has its own memory
        network = "cellular"
        assertEquals(listOf(e443, e8443, default), selector.endpoints())
        selector.onConnected(default)
        assertEquals(listOf(default, e443, e8443), selector.endpoints())

        // back on Wi-Fi the Wi-Fi winner is used again
        network = "wifi"
        assertEquals(listOf(e8443, e443, default), selector.endpoints())
        assertEquals(e8443, store.winners["wifi"])
        assertEquals(default, store.winners["cellular"])
    }

    @Test
    fun `server removing its list falls back to default only`() {
        val store = FakeStore(listOf(e443))
        val selector = EndpointSelector(store, { default }, { "wifi" })
        selector.onConnected(e443)
        store.saveServerEndpoints(emptyList())
        assertEquals(listOf(default), selector.endpoints())
    }

    @Test
    fun `missing default endpoint still yields server endpoints`() {
        val store = FakeStore(listOf(e443))
        val selector = EndpointSelector(store, { null }, { "other" })
        assertEquals(listOf(e443), selector.endpoints())
    }

    // ---------- defaults ----------

    @Test
    fun `sdk connection defaults`() {
        assertEquals(30, PNSocketManager.HEARTBEAT_INTERVAL_S)
        assertEquals(1_000L, PNSocketManager.RECONNECT_DELAY_MS)
        assertEquals(60_000L, PNSocketManager.MAX_RECONNECT_DELAY_MS)
        assertEquals(0, PNSocketManager.MAX_RECONNECT_ATTEMPTS)
    }
}

class ReconnectDebouncerTest {

    @Test
    fun `triggers within the window are dropped`() {
        var now = 10_000L
        val d = ReconnectDebouncer(2_000L) { now }
        assertTrue(d.tryAcquire())      // screen on
        now += 50
        assertFalse(d.tryAcquire())     // user present
        now += 500
        assertFalse(d.tryAcquire())     // app foreground
        now += 1_450
        assertTrue(d.tryAcquire())      // window elapsed
    }

    @Test
    fun `first trigger always passes even at clock zero`() {
        val d = ReconnectDebouncer(2_000L) { 0L }
        assertTrue(d.tryAcquire())
        assertFalse(d.tryAcquire())
    }
}
