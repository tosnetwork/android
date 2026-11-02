package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TosTokenResponseTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() { server = MockWebServer().apply { start() } }

    @After
    fun stopServer() = server.close()

    private fun source() = TosSource(OkHttpClient(), { server.url("/").toString() })
    private fun enqueue(result: String) {
        server.enqueue(MockResponse.Builder().code(200).body("{\"result\":$result}").build())
    }

    @Test
    fun `new token positions are read using the new method`() {
        enqueue("""{"tokens":[{"token_master":"0:master","token_wallet":"0:wallet","last_lt":"10"}]}""")
        assertEquals(listOf("0:wallet"), source().getAccountJettonWallets("0:owner"))
        val request = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("getAccountTokens", request.getString("method"))
        assertEquals("0:owner", request.getJSONObject("params").getString("address"))
        enqueue("""{"tokens":[]}""")
        assertEquals(emptyList<String>(), source().getAccountJettonWallets("0:owner"))
    }

    @Test
    fun `old and malformed positions never become an empty balance`() {
        for (result in listOf(
            """{"jettons":[]}""",
            """{"tokens":[{"jetton_master":"0:master","jetton_wallet":"0:wallet"}]}""",
            """{"tokens":null}""",
        )) {
            enqueue(result)
            assertThrows(Exception::class.java) { source().getAccountJettonWallets("0:owner") }
        }
    }

    @Test
    fun `new master metadata takes precedence over offchain metadata`() {
        val metadata = TosTokenMetadata(JSONObject("""{"@type":"ext.tokens.tokenMasterData",
            "token_name":"Credit","token_symbol":"CR","token_decimals":"6",
            "token_image":"https://example.invalid/credit.png","token_metadata_uri":"https://example.invalid/credit.json"}"""))
        val offchain = JSONObject("""{"name":"Other","decimals":"8"}""")
        assertEquals("Credit", metadata.name("0:master", offchain))
        assertEquals("CR", metadata.symbol(offchain))
        assertEquals(6, metadata.decimals(offchain))
        assertEquals("https://example.invalid/credit.png", metadata.image(offchain))
        assertEquals("https://example.invalid/credit.json", metadata.uri)
    }

    @Test
    fun `missing decimals use offchain value then default to nine`() {
        val metadata = TosTokenMetadata(JSONObject("""{"@type":"ext.tokens.tokenMasterData"}"""))
        assertEquals(8, metadata.decimals(JSONObject("""{"decimals":"8"}""")))
        assertEquals(9, metadata.decimals(null))
        assertEquals(9, metadata.decimals(JSONObject("""{"decimals":"invalid"}""")))
        assertThrows(IllegalArgumentException::class.java) {
            TosTokenMetadata(JSONObject("""{"@type":"ext.tokens.jettonMasterData"}"""))
        }
    }
}
