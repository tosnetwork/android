package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TosTokenReadTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.close()
    private fun client() = TosRpcClient(OkHttpClient(), { server.url("/").toString() })
    private fun enqueue(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())
    @Test fun readMethodsAreBoundToResponseIDAndObjectEnvelope() {
        enqueue("""{"jsonrpc":"2.0","id":1,"result":{"tokens":[]}}""")
        assertEquals(0, client().callTokenRead("getAccountTokens", JSONObject().put("limit", 10000)).getJSONArray("tokens").length())
        val sent = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("getAccountTokens", sent.getString("method"))
        assertEquals(10000, sent.getJSONObject("params").getInt("limit"))
        for (body in listOf(
            """{"jsonrpc":"2.0","id":2,"result":{}}""",
            """{"jsonrpc":"2.0","id":"1","result":{}}""",
            """{"jsonrpc":"2.0","id":1,"ok":false,"result":{}}""",
            """{"jsonrpc":"2.0","id":1,"error":null,"result":{}}""",
            """{"jsonrpc":"2.0","id":1,"result":[]}""",
        )) { enqueue(body); assertThrows(Exception::class.java) { client().callTokenRead("getTokenData", JSONObject()) } }
        assertThrows(IllegalArgumentException::class.java) { client().callTokenRead("sendBocReturnHash", JSONObject()) }
    }
    @Test fun oversizedAndDeepResponsesAreRefusedAndRedirectIsNotFollowed() {
        enqueue("x".repeat(2_097_153))
        assertThrows(Exception::class.java) { client().callTokenRead("getTokenData", JSONObject()) }
        enqueue("""{"jsonrpc":"2.0","id":1,"result":{"nested":REPLACE}}""".replace("REPLACE", "[".repeat(65) + "0" + "]".repeat(65)))
        assertThrows(Exception::class.java) { client().callTokenRead("getTokenData", JSONObject()) }
        val requests = server.requestCount
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", server.url("/redirect")).build())
        assertThrows(Exception::class.java) { client().callTokenRead("getTokenData", JSONObject()) }
        assertEquals(requests + 1, server.requestCount)
    }
    private val master = "0:" + "22".repeat(32)
    private val wallet = "0:" + "11".repeat(32)
    private fun index(rows: String, state: String = "{}") = JSONObject("""{"@type":"wallet.accountTokens","index_state":STATE,"tokens":ROWS}""".replace("STATE", state).replace("ROWS", rows))
    @Test fun indexCarriesHintsAndReportsIncompleteOwnershipWithoutClaimingZeroAssets() {
        val row = """{"token_master":"$master","token_wallet":"$wallet"}"""
        val parsed = TosTokenIndex.decode(index("[$row]", """{"complete":true}"""))
        assertEquals(TosTokenIndex.Position(wallet, master), parsed.positions.single())
        assertTrue(parsed.nodeReportsComplete); assertFalse(parsed.reachedRowLimit)
        assertFalse(TosTokenIndex.decode(index("[]")).nodeReportsComplete)
        assertFalse(TosTokenIndex.decode(index("[]", """{"complete":true,"ownership_complete":false}""")).nodeReportsComplete)
        assertThrows(Exception::class.java) { TosTokenIndex.decode(index("[$row,$row]")) }
        assertThrows(Exception::class.java) { TosTokenIndex.decode(index("""[{"token_master":"$master","token_wallet":"0:wrong"}]""")) }
        assertThrows(Exception::class.java) { TosTokenIndex.decode(index("""[{"token_master":"$master"}]""")) }
    }
    @Test fun rowLimitIsExplicitAndExcessRowsAreRejected() {
        val rows = JSONArray()
        for (number in 1..10_000) rows.put(JSONObject().put("token_master", "0:" + "%064x".format(number))
            .put("token_wallet", "0:" + "%064x".format(number + 10_000)))
        val value = JSONObject().put("@type", "wallet.accountTokens").put("tokens", rows)
        val parsed = TosTokenIndex.decode(value)
        assertEquals(10_000, parsed.positions.size); assertTrue(parsed.reachedRowLimit)
        rows.put(JSONObject().put("token_master", "0:" + "%064x".format(10_001))
            .put("token_wallet", "0:" + "%064x".format(20_001)))
        assertThrows(Exception::class.java) { TosTokenIndex.decode(value) }
    }

}
