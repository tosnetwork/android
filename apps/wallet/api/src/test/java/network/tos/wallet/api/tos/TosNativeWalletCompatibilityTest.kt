package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.extensions.toAccountId
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.crypto.hex
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.CellType
import org.ton.cell.buildCell
import java.util.Base64

class TosNativeWalletCompatibilityTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/tos-native-active-wallet.json"))
        .bufferedReader().use { JSONObject(it.readText()) }
    private fun wallet() = fixture().let {
        TosWalletV5R1Contract(PublicKeyEd25519(hex(it.getString("public_key"))),
            it.getInt("network_global_id"), it.getLong("subwallet_id"))
    }
    private fun source(server: MockWebServer) = TosSource(OkHttpClient(), { server.url("/").toString() })
    private fun enqueue(server: MockWebServer, result: JSONObject) {
        server.enqueue(MockResponse.Builder().code(200).body(JSONObject().put("result", result).toString()).build())
    }
    private fun active() = JSONObject().put("state", "active")
        .put("code", wallet().getCode().base64()).put("data", fixture().getString("data_boc"))
    private fun data(seqno: Long = 1, signatures: Boolean = true, subwallet: Long = 0,
        key: ByteArray = wallet().publicKey.key.toByteArray(), dictionary: Cell? = null,
        extra: Boolean = false) = buildCell {
        storeBit(signatures); storeUInt(seqno, 32); storeUInt(subwallet, 32); storeBytes(key)
        storeBit(dictionary != null); dictionary?.let { storeRef(it) }
        if (extra) storeBit(false)
    }
    private fun reject(state: JSONObject, message: String) {
        MockWebServer().use { server ->
            server.start(); enqueue(server, state)
            val error = assertThrows(IllegalArgumentException::class.java) {
                source(server).getSeqno(wallet().address.toAccountId(), false, wallet())
            }
            assertEquals(message, error.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun actualActiveNativeSnapshotRemainsReadableWhenNodeRegistryOmitsItsCode() {
        val vector = fixture(); val wallet = wallet()
        assertEquals(vector.getString("code_hash"), hex(wallet.getCode().hash().toByteArray()))
        assertEquals(vector.getString("address"), wallet.address.toAccountId())
        MockWebServer().use { server ->
            server.start()
            enqueue(server, vector.getJSONObject("wallet_information")); enqueue(server, active())
            assertEquals(vector.getInt("seqno"), source(server).getSeqno(wallet.address.toAccountId()))
            assertEquals(listOf("getWalletInformation", "getAddressInformation"),
                List(2) { JSONObject(server.takeRequest().body!!.utf8()).getString("method") })
            enqueue(server, active())
            assertEquals(vector.getInt("seqno"), source(server).getSeqno(wallet.address.toAccountId(), false, wallet))
            assertEquals("getAddressInformation", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
        }
    }

    @Test fun codeMustMatchTheShippedNativeContract() {
        reject(active().put("code", Cell.empty().base64()), "Unrecognized native wallet code")
    }

    @Test fun stateKeyAndSubwalletMustReconstructTheOriginalAddress() {
        reject(active().put("data", data(key = ByteArray(32)).base64()), "Native wallet initial address mismatch")
        reject(active().put("data", data(subwallet = 1).base64()), "Native wallet initial address mismatch")
    }

    @Test fun disabledSignaturesAndUnsupportedCountersAreRejected() {
        reject(active().put("data", data(signatures = false).base64()), "Native wallet signatures are disabled")
        reject(active().put("data", data(seqno = 0x80000000L).base64()), "Unsupported native wallet sequence number")
        MockWebServer().use { server ->
            server.start(); enqueue(server, active().put("data", data(seqno = Int.MAX_VALUE.toLong()).base64()))
            assertEquals(Int.MAX_VALUE, source(server).getSeqno(wallet().address.toAccountId(), false, wallet()))
        }
    }

    @Test fun extraStateAndNonOrdinaryOrHigherLevelCellsAreRejected() {
        reject(active().put("data", data(extra = true).base64()), "Unexpected native wallet state data")
        val exotic = buildCell { isExotic = true; storeUInt(2, 8); storeBytes(ByteArray(32)) }
        reject(active().put("data", exotic.base64()), "Native wallet code and data must be ordinary level-zero cells")
        val higher = data(dictionary = CellBuilder.createPrunedBranch(Cell.empty(), 0))
        assertEquals(CellType.ORDINARY, higher.type); assertTrue(higher.levelMask.level > 0)
        reject(active().put("data", higher.base64()), "Native wallet code and data must be ordinary level-zero cells")
    }

    @Test fun multipleBocRootsAreNotSilentlyReducedToOne() {
        for (field in listOf("code", "data")) MockWebServer().use { server ->
            val original = active(); val root = original.getString(field).cellFromBase64()
            val multiple = Base64.getEncoder().encodeToString(BagOfCells(root, Cell.empty()).toByteArray())
            server.start(); enqueue(server, original.put(field, multiple))
            assertThrows(IllegalArgumentException::class.java) {
                source(server).getSeqno(wallet().address.toAccountId(), false, wallet())
            }
        }
    }

    @Test fun onlyAnExplicitEmptyUninitializedStateProducesZero() {
        val empty = JSONObject().put("state", "uninitialized").put("code", "").put("data", "")
        MockWebServer().use { server ->
            server.start(); enqueue(server, empty)
            assertEquals(0, source(server).getSeqno(wallet().address.toAccountId(), false, wallet()))
        }
        reject(JSONObject(empty.toString()).put("data", fixture().getString("data_boc")), "Invalid native deployment state")
        reject(JSONObject(empty.toString()).put("state", "frozen"), "Wallet account is not active")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).body("{\"error\":\"denied\"}").build())
            assertThrows(Exception::class.java) { source(server).getSeqno(wallet().address.toAccountId(), false, wallet()) }
        }
    }

    @Test fun mismatchedTypedMetadataIsRejectedBeforeContactingTheNode() {
        MockWebServer().use { server ->
            server.start()
            // Public test key: the other account is internally consistent, so
            // only the caller's persisted identity can reject this request.
            val otherKey = PrivateKeyEd25519(ByteArray(32) { 7 }).publicKey()
            val other = TosWalletV5R1Contract(otherKey, 3)
            enqueue(server, active().put("data", data(key = otherKey.key.toByteArray()).base64()))
            val error = assertThrows(IllegalArgumentException::class.java) {
                source(server).getSeqno(other.address.toAccountId(), false, wallet())
            }
            assertEquals("Native wallet address mismatch", error.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun malformedRecognizedWalletResponsesCannotEnterCompatibility() {
        MockWebServer().use { server ->
            server.start(); enqueue(server, JSONObject().put("wallet", true).put("account_state", "active").put("seqno", JSONObject.NULL))
            assertThrows(Exception::class.java) { source(server).getSeqno(wallet().address.toAccountId()) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun compatibilityKeepsTheCapturedEndpointAndCredentialsAcrossBothReads() {
        MockWebServer().use { server ->
            server.start(); var endpoints = 0; var credentials = 0
            val node = TosSource(OkHttpClient(), {
                if (++endpoints == 1) server.url("/").toString() else "http://127.0.0.1:1"
            }, { if (++credentials == 1) "PUBLIC-TEST-original" else "PUBLIC-TEST-replaced" })
            enqueue(server, fixture().getJSONObject("wallet_information")); enqueue(server, active())
            assertEquals(1, node.getSeqno(wallet().address.toAccountId()))
            assertEquals(1, endpoints); assertEquals(1, credentials)
            repeat(2) { assertEquals("PUBLIC-TEST-original", server.takeRequest().headers["X-API-Key"]) }
        }
    }
}
