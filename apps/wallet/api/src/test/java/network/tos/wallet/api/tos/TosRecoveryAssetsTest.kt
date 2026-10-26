package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TosRecoveryAssetsTest {
    private val owner = "0:" + "11".repeat(32)
    private val tokenWallet = "0:" + "22".repeat(32)
    private val nft = "0:" + "33".repeat(32)
    private fun enqueue(server: MockWebServer, type: String, field: String, rows: String, complete: Boolean) {
        server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"@type":"$type","$field":$rows,"index_state":{"complete":$complete}}}""").build())
    }
    @Test fun recoveryUsesCurrentNodeMethodsAndRetainsTokenOnlyAndNftOnlyAccounts() {
        MockWebServer().use { server ->
            server.start()
            var endpointReads = 0
            val source = TosSource(OkHttpClient(), { endpointReads++; server.url("/").toString() })
            enqueue(server, "wallet.accountTokens", "tokens", """[{"token_wallet":"$tokenWallet"}]""", true)
            enqueue(server, "wallet.accountNfts", "nfts", "[]", true)
            assertEquals(TosRecoveryAssets(true, false, true), source.getRecoveryAssets(owner))
            assertEquals(1, endpointReads)
            for (method in listOf("getAccountTokens", "getAccountNfts")) {
                val request = JSONObject(server.takeRequest().body!!.utf8())
                assertEquals(method, request.getString("method"))
                assertEquals(owner, request.getJSONObject("params").getString("address"))
                assertEquals(1, request.getJSONObject("params").getInt("limit"))
            }
            enqueue(server, "wallet.accountTokens", "tokens", "[]", true)
            enqueue(server, "wallet.accountNfts", "nfts", """[{"nft_item":"$nft"}]""", true)
            assertEquals(TosRecoveryAssets(false, true, true), source.getRecoveryAssets(owner))
        }
    }
    @Test fun emptyIncompleteIndexNeverEstablishesAbsence() {
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            enqueue(server, "wallet.accountTokens", "tokens", "[]", false)
            enqueue(server, "wallet.accountNfts", "nfts", "[]", true)
            assertEquals(TosRecoveryAssets(false, false, false), source.getRecoveryAssets(owner))
            enqueue(server, "wallet.accountTokens", "tokens", "[]", true)
            enqueue(server, "wallet.accountNfts", "nfts", "[]", true)
            assertEquals(TosRecoveryAssets(false, false, true), source.getRecoveryAssets(owner))
        }
    }
    @Test fun ordinaryTokenQueriesDecodeCurrentWireNamesAndRefuseMalformedRows() {
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            enqueue(server, "wallet.accountTokens", "tokens", """[{"token_wallet":"$tokenWallet"}]""", true)
            assertEquals(listOf(tokenWallet), source.getAccountJettonWallets(owner))
            assertEquals("getAccountTokens", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
            enqueue(server, "wallet.accountTokens", "tokens", "[{}]", true)
            assertThrows(Exception::class.java) { source.getAccountJettonWallets(owner) }
        }
    }
    @Test fun unavailableAndMalformedDiscoveryNeverBecomeEmptyCompleteAssets() {
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            server.enqueue(MockResponse.Builder().code(200).body("""{"error":{"code":-32000,"message":"index unavailable"}}""").build())
            assertThrows(Exception::class.java) { source.getRecoveryAssets(owner) }
            enqueue(server, "wallet.accountTokens", "tokens", "[{}]", true)
            enqueue(server, "wallet.accountNfts", "nfts", "[]", true)
            assertThrows(Exception::class.java) { source.getRecoveryAssets(owner) }
        }
    }
}
