package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.cellFromBase64
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.contract.wallet.WalletTransferBuilder
import java.util.Base64

class TosSendReconciliationTest {
    private fun fixture(): JSONObject = requireNotNull(javaClass.getResourceAsStream("/tos-mobile-receipt-hash-proof.json"))
        .bufferedReader().use { JSONObject(it.readText()) }
    private fun enqueue(server: MockWebServer, seqno: Int, receipt: JSONObject) {
        server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"wallet":true,"account_state":"active","seqno":$seqno}}""").build())
        server.enqueue(MockResponse.Builder().code(200).body(JSONObject().put("result", JSONArray().put(receipt)).toString()).build())
    }
    @Test fun differentDeviceBocAtTheSameSequenceNeverConfirmsThisPayment() {
        // PUBLIC TEST KEY. Two independently signed payments contend for seqno 7.
        val key = PrivateKeyEd25519(ByteArray(32) { it.toByte() })
        val wallet = TosWalletV5R1Contract(key.publicKey(), 3)
        fun message(recipient: String) = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${recipient.repeat(32)}"); coins = Coins.ofNano(1L); sendMode = 3
        }.build().let { gift ->
            val unsigned = wallet.createTransferUnsignedBody(2000000000L, 7, false, null, gift)
            wallet.createTransferMessageCell(wallet.address, 7,
                wallet.signedBody(org.ton.bitstring.BitString(key.sign(unsigned.hash().toByteArray())), unsigned))
        }
        val submitted = message("11")
        val otherDevice = message("22")
        assertNotEquals(submitted.hash(), otherDevice.hash())
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            val receipt = fixture().getJSONObject("raw_rpc_fixture").put("in_msg_hash",
                Base64.getEncoder().encodeToString(otherDevice.hash().toByteArray()))
            enqueue(server, 8, receipt)
            assertEquals(TosSendReconciliation.AMBIGUOUS, source.reconcileSend(wallet.address.toString(), submitted.base64(), 7))
            assertEquals(2, server.requestCount)
            val methods = List(2) { JSONObject(server.takeRequest().body!!.utf8()).getString("method") }
            assertEquals(listOf("getWalletInformation", "getTransactions"), methods)
        }
    }
    @Test fun ownLostResponseRequiresFullStateInitHashAndSuccessfulDeliveryReceipt() {
        val proof = fixture()
        val boc = proof.getString("external_message_boc_base64")
        val normalized = proof.getString("normalized_message_boc_base64")
        assertNotEquals(boc.cellFromBase64().hash(), normalized.cellFromBase64().hash())
        val address = proof.getString("sender")
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            enqueue(server, 1, proof.getJSONObject("raw_rpc_fixture"))
            assertEquals(TosSendReconciliation.CONFIRMED, source.reconcileSend(address, boc, 0))
            val invalid = listOf<(JSONObject) -> Unit>(
                { it.put("aborted", true) }, { it.remove("action") },
                { it.put("out_msgs", JSONArray()) },
                { it.getJSONObject("action").put("skipped_actions", 1) },
                { it.getJSONObject("compute").put("success", false) },
                { it.put("in_msg_hash", Base64.getEncoder().encodeToString(normalized.cellFromBase64().hash().toByteArray())) },
            )
            invalid.forEach { mutate ->
                val receipt = JSONObject(proof.getJSONObject("raw_rpc_fixture").toString()).also(mutate)
                enqueue(server, 1, receipt)
                assertEquals(TosSendReconciliation.AMBIGUOUS, source.reconcileSend(address, boc, 0))
            }
        }
    }
}
