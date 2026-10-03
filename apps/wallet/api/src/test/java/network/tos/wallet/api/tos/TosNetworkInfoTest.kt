package network.tos.wallet.api.tos

import network.tos.blockchain.ton.extensions.base64
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.ton.cell.buildCell

class TosNetworkInfoTest {
    private val global3 = buildCell { storeInt(3, 32) }.base64()
    private val version18 = buildCell { storeUInt(0xc4, 8); storeUInt(18, 32); storeUInt(0, 64) }.base64()
    @Test fun capabilityDiscoveryDoesNotCallClassicWalletsPostQuantum() {
        val network = TosNetworkInfo.fromConfig(global3, version18)
        assertEquals(3, network.globalId)
        assertEquals(18L, network.vmVersion)
        assertTrue(network.supportsNativeV5)
        for (version in listOf(4L, 5L)) {
            assertFalse(TosNetworkInfo(3, version).supportsNativeV5)
            assertThrows(IllegalArgumentException::class.java) { TosNetworkInfo(3, version).requireNativeV5() }
        }
        assertTrue(TosNetworkInfo(3, 6).requireNativeV5().supportsNativeV5)
        assertTrue(network.supportsMlDsa)
        assertFalse(network.supportsFalcon)
        assertThrows(IllegalArgumentException::class.java) { TosNetworkInfo.fromConfig(version18, version18) }
        assertThrows(IllegalArgumentException::class.java) { TosNetworkInfo.fromConfig(global3, global3) }
    }
    @Test fun networkMismatchAndFailedSeqnoNeverProduceDeploymentSequenceZero() {
        MockWebServer().use { server ->
            server.start()
            val source = TosSource(OkHttpClient(), { server.url("/").toString() })
            enqueueNetwork(server)
            assertThrows(IllegalArgumentException::class.java) { source.requireNetwork(4) }
            server.enqueue(MockResponse.Builder().code(200).body("""{"ok":false,"code":-32001,"error":"node offline"}""").build())
            assertThrows(TosRpcException::class.java) { source.getSeqno("0:${"11".repeat(32)}") }
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"wallet":true,"account_state":"active"}}""").build())
            assertThrows(Exception::class.java) { source.getSeqno("0:${"11".repeat(32)}") }
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"wallet":false,"account_state":"uninitialized","seqno":null}}""").build())
            assertEquals(0, source.getSeqno("0:${"11".repeat(32)}"))
        }
    }
    @Test fun oneNetworkDiscoveryUsesOneEndpointEvenWhenSettingsChange() {
        MockWebServer().use { server ->
            server.start()
            var reads = 0
            val source = TosSource(OkHttpClient(), {
                reads++
                if (reads == 1) server.url("/").toString() else "http://127.0.0.1:1"
            })
            enqueueNetwork(server)
            assertEquals(3, source.getNetworkInfo().globalId)
            assertEquals(1, reads)
            assertEquals("getMasterchainInfo", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
            val params = JSONObject(server.takeRequest().body!!.utf8()).getJSONObject("params")
            assertEquals(19, params.getInt("param"))
            assertEquals(27, params.getInt("seqno"))
        }
    }
    @Test fun fractionalBooleanNegativeAndOverflowSequenceNumbersAreRejected() {
        for (value in listOf("0.5", "1.0", "true", "-1", "2147483648", "\"00\"", "\"0.5\"", "null")) {
            assertThrows("Invalid sequence accepted: $value", Exception::class.java) {
                TosWalletInfo.fromJson(JSONObject("""{"wallet":true,"account_state":"active","seqno":$value}"""))
            }
        }
        assertEquals(7, TosWalletInfo.fromJson(JSONObject("""{"wallet":true,"account_state":"active","seqno":"7"}""")).seqno)
    }
    @Test fun broadcastStaysOnValidatedEndpointWhenSettingsChangeAndWrongNetworkCannotBroadcast() {
        MockWebServer().use { server ->
            server.start()
            var reads = 0
            val source = TosSource(OkHttpClient(), {
                reads++
                if (reads == 1) server.url("/").toString() else "http://127.0.0.1:1"
            })
            enqueueNetwork(server)
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"status":1,"hash":"abc"}}""").build())
            assertTrue(source.sendBocForNetwork("test-boc", 3).accepted)
            assertEquals(1, reads)
            assertEquals(4, server.requestCount)
            repeat(3) { server.takeRequest() }
            assertEquals("sendBocReturnHash", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
            val fixed = TosSource(OkHttpClient(), { server.url("/").toString() })
            enqueueNetwork(server)
            assertThrows(IllegalArgumentException::class.java) { fixed.sendBocForNetwork("test-boc", 4) }
            assertEquals(7, server.requestCount)
        }
    }
    @Test fun capturedTransferKeepsSequenceFeeAndBroadcastOnOriginalEndpoint() {
        MockWebServer().use { server ->
            server.start()
            var endpoint = server.url("/").toString()
            val source = TosSource(OkHttpClient(), { endpoint })
            val transfer = source.snapshot()
            endpoint = "http://127.0.0.1:1"
            enqueueNetwork(server)
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"wallet":true,"account_state":"active","seqno":7}}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"source_fees":{"gas_fee":1234,"in_fwd_fee":0,"storage_fee":0,"fwd_fee":0}}}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"status":1}}""").build())
            transfer.requireNetwork(3)
            assertEquals(7, transfer.getSeqno("0:${"11".repeat(32)}"))
            assertEquals(1234L, transfer.estimateFee("0:${"11".repeat(32)}", "body").total)
            assertTrue(transfer.sendBoc("signed-body").accepted)
            assertEquals(6, server.requestCount)
            val methods = List(6) { JSONObject(server.takeRequest().body!!.utf8()).getString("method") }
            assertEquals(listOf("getMasterchainInfo", "getConfigParam", "getConfigParam", "getWalletInformation", "estimateFee", "sendBocReturnHash"), methods)
        }
    }
    private fun enqueueNetwork(server: MockWebServer) {
        server.enqueue(MockResponse.Builder().code(200).body("""{"result":{"last":{"workchain":-1,"seqno":27,"shard":"-9223372036854775808"}}}""").build())
        for (boc in listOf(global3, version18)) server.enqueue(MockResponse.Builder().code(200).body(
            JSONObject().put("result", JSONObject().put("@type", "configInfo").put("config",
                JSONObject().put("@type", "tvm.cell").put("bytes", boc))).toString()).build())
    }

    @Test fun feeDecoderRejectsMissingFractionalNegativeBooleanAndOverflowValues() {
        val fields = listOf("in_fwd_fee", "storage_fee", "gas_fee", "fwd_fee")
        val valid = JSONObject().apply { fields.forEach { put(it, "1") } }
        assertEquals(4L, TosFees.fromJson(JSONObject().put("source_fees", valid)).total)
        assertEquals(4L, TosFees.fromJson(valid).total)
        assertThrows(Exception::class.java) { TosFees.fromJson(JSONObject()) }
        assertThrows(Exception::class.java) { TosFees.fromJson(JSONObject().put("source_fees", true)) }
        for (field in fields) {
            assertThrows("Missing fee accepted: $field", Exception::class.java) {
                TosFees.fromJson(JSONObject(valid.toString()).apply { remove(field) })
            }
            for (value in listOf(0.5, 1.0, true, -1L, "-1", "00", "9223372036854775808", JSONObject.NULL)) {
                assertThrows("Invalid fee accepted: $field=$value", Exception::class.java) {
                    TosFees.fromJson(JSONObject(valid.toString()).put(field, value))
                }
            }
        }
        assertThrows(ArithmeticException::class.java) {
            TosFees.fromJson(JSONObject(valid.toString()).put("gas_fee", Long.MAX_VALUE))
        }
    }
}
