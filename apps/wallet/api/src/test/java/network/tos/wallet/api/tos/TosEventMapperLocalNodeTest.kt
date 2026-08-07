package network.tos.wallet.api.tos

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Optional end-to-end regression test; enabled by TOS_TEST_ADDRESS for a running local node. */
class TosEventMapperLocalNodeTest {

    @Test
    fun `maps transactions returned by a local node`() {
        val address = System.getenv("TOS_TEST_ADDRESS")
        if (address.isNullOrBlank()) {
            assumeTrue("TOS_TEST_ADDRESS is not set", false)
            return
        }
        val endpoint = System.getenv("TOS_TEST_RPC") ?: "http://127.0.0.1:18545/jsonRPC"
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "getTransactions")
            .put("params", JSONObject().put("address", address).put("limit", 20))

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val transactions = JSONObject(response).getJSONArray("result")
        val raw = (0 until transactions.length()).mapNotNull {
            transactions.optJSONObject(it)?.let(TosRawTransaction::fromJson)
        }

        assertTrue("local node returned no transactions", raw.isNotEmpty())
        assertTrue(
            "real local-node transactions produced no wallet events",
            TosEventMapper.toAccountEvents(address, raw).isNotEmpty(),
        )
    }
}
