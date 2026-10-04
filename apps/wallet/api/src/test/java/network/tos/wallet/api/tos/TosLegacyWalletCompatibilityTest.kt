package network.tos.wallet.api.tos

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.LegacyWalletCompatibility
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.extensions.toAccountId
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.bitstring.BitString
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.CellType
import org.ton.cell.buildCell
import org.ton.crypto.hex
import java.util.Base64

class TosLegacyWalletCompatibilityTest {
    private fun vectors(): List<JSONObject> {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/tos-legacy-active-state-vectors.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("vectors")
        return List(fixture.length()) { fixture.getJSONObject(it) }
    }
    private fun contract(vector: JSONObject): BaseWalletContract = BaseWalletContract.create(
        PublicKeyEd25519(hex(vector.getString("public_key"))), vector.getString("version"), -239)
    private fun source(server: MockWebServer) = TosSource(OkHttpClient(), { server.url("/").toString() })
    private fun enqueue(server: MockWebServer, result: Any) {
        server.enqueue(MockResponse.Builder().code(200).body(JSONObject().put("result", result).toString()).build())
    }
    private fun copy(json: JSONObject) = JSONObject(json.toString())
    private fun currentData(wallet: BaseWalletContract, seqno: Long = 1,
        signaturesAllowed: Boolean = true, walletId: Long? = null, key: ByteArray? = null,
        dictionary: Cell? = null): Cell {
        val initial = wallet.getStateCell().beginParse()
        val v5 = wallet.getWalletVersion() == WalletVersion.V5R1
        if (v5) initial.loadBit()
        initial.loadUInt(32)
        val id = initial.loadUInt(32).toLong()
        return buildCell {
            if (v5) storeBit(signaturesAllowed)
            storeUInt(seqno, 32)
            storeUInt(walletId ?: id, 32)
            storeBytes(key ?: wallet.publicKey.key.toByteArray())
            if (wallet.getWalletVersion() in listOf(WalletVersion.V4R1, WalletVersion.V4R2, WalletVersion.V5R1)) {
                storeBit(dictionary != null)
                dictionary?.let { storeRef(it) }
            }
        }
    }

    @Test fun fiveActualActiveLegacySnapshotsAreVerifiedWithoutRelaxingWalletInfoDecoder() {
        for (vector in vectors()) MockWebServer().use { server ->
            server.start()
            val wallet = contract(vector)
            assertEquals(vector.getString("code_hash"), hex(wallet.getCode().hash().toByteArray()))
            assertEquals(vector.getString("address"), wallet.address.toAccountId())
            val info = vector.getJSONObject("wallet_information")
            assertFalse(info.getBoolean("wallet")); assertTrue(info.isNull("seqno"))
            assertThrows(Exception::class.java) { TosWalletInfo.fromJson(info) }
            enqueue(server, info); enqueue(server, vector.getJSONObject("account_state"))
            assertEquals(1, source(server).getSeqno(wallet.address.toAccountId()))
            assertEquals(listOf("getWalletInformation", "getAddressInformation"),
                List(2) { JSONObject(server.takeRequest().body!!.utf8()).getString("method") })
            enqueue(server, vector.getJSONObject("account_state"))
            assertEquals(1, source(server).getSeqno(wallet.address.toAccountId(), false, wallet))
            assertEquals("getAddressInformation", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
            // This is the independently signed BOC actually accepted by the node;
            // its signed seqno0 differs from the post-send snapshot's seqno1.
            assertEquals(0, LegacyWalletCompatibility.signedTransferSeqno(wallet,
                vector.getString("external_message_boc").cellFromBase64()))
        }
    }

    @Test fun alteredLegacyDataCodeAndUnsupportedStatesNeverProduceASequence() {
        for (vector in vectors()) {
            val wallet = contract(vector)
            val original = vector.getJSONObject("account_state")
            val data = original.getString("data").cellFromBase64()
            val code = original.getString("code").cellFromBase64()
            val exotic = buildCell { isExotic = true; storeUInt(2, 8); storeBytes(ByteArray(32)) }
            val higherLevelData = if (wallet.getWalletVersion() in listOf(WalletVersion.V4R1, WalletVersion.V4R2, WalletVersion.V5R1)) {
                currentData(wallet, dictionary = CellBuilder.createPrunedBranch(Cell.empty(), 0)).also {
                    // The root itself is ordinary and its header/address binding
                    // remains valid; an ordinary-only root gate cannot reject it.
                    assertEquals(CellType.ORDINARY, it.type)
                    assertTrue(it.levelMask.level > 0)
                }
            } else null
            fun multipleRoots(root: Cell) = Base64.getEncoder().encodeToString(BagOfCells(root, Cell.empty()).toByteArray())
            val malformed = listOf(
                copy(original).put("code", Cell.empty().base64()),
                copy(original).put("code", TosWalletV5R1Contract(wallet.publicKey, 3).getCode().base64()),
                copy(original).put("data", Cell.empty().base64()),
                copy(original).put("code", exotic.base64()),
                copy(original).put("data", exotic.base64()),
                copy(original).put("code", multipleRoots(code)),
                copy(original).put("data", multipleRoots(data)),
                copy(original).put("data", currentData(wallet, 0xffffffffL).base64()),
                copy(original).put("data", currentData(wallet, walletId = 42).base64()),
                copy(original).put("data", currentData(wallet, key = ByteArray(32)).base64()),
                copy(original).put("data", buildCell { storeSlice(data.beginParse()); storeBit(true) }.base64()),
                copy(original).put("data", buildCell { storeSlice(data.beginParse()); storeRef(Cell.empty()) }.base64()),
                copy(original).put("state", "frozen"),
                copy(original).apply { remove("state") },
                copy(original).put("code", JSONObject.NULL),
                copy(original).put("data", JSONObject.NULL),
            ) + listOfNotNull(higherLevelData?.let { copy(original).put("data", it.base64()) }) +
                if (wallet.getWalletVersion() == WalletVersion.V5R1)
                    listOf(copy(original).put("data", currentData(wallet, signaturesAllowed = false).base64())) else emptyList()
            for (state in malformed) MockWebServer().use { server ->
                server.start(); enqueue(server, state)
                assertThrows("Malformed ${vector.getString("version")} state accepted", Exception::class.java) {
                    source(server).getSeqno(wallet.address.toAccountId(), false, wallet)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun reconstructedAddressAndTypedRevisionPublicKeyAndIdMustMatch() {
        for (vector in vectors()) {
            val wallet = contract(vector)
            val state = vector.getJSONObject("account_state")
            val code = state.getString("code").cellFromBase64()
            val data = state.getString("data").cellFromBase64()
            assertThrows(Exception::class.java) {
                LegacyWalletCompatibility.verifiedSeqno(AddrStd.parse("0:${"11".repeat(32)}"), code, data)
            }
            val wrongKey = BaseWalletContract.create(PublicKeyEd25519(ByteArray(32)), vector.getString("version"), -239)
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.verifiedSeqno(wallet.address, code, data, wrongKey) }
            val otherVersion = if (wallet.getWalletVersion() == WalletVersion.V3R1) "v3r2" else "v3r1"
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.verifiedSeqno(wallet.address, code, data,
                BaseWalletContract.create(wallet.publicKey, otherVersion, -239)) }
            val native = TosWalletV5R1Contract(wallet.publicKey, 3)
            assertFalse(LegacyWalletCompatibility.isSupported(native))
            assertFalse(LegacyWalletCompatibility.isSupported(BaseWalletContract.create(wallet.publicKey, "v5beta", -239)))
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.verifiedSeqno(wallet.address, code, data, native) }
            MockWebServer().use { server ->
                server.start(); enqueue(server, vector.getJSONObject("wallet_information"))
                assertThrows(Exception::class.java) { source(server).getSeqno(wallet.address.toAccountId(), false, native) }
                assertEquals("A native caller must not fetch legacy account data", 1, server.requestCount)
            }
        }
    }

    @Test fun malformedRecognizedWalletAndRpcFailuresNeverTriggerLegacyFallback() {
        val address = vectors().last().getString("address")
        val malformed = listOf("null", "0.5", "true", "-1", "2147483648")
        for (seqno in malformed) MockWebServer().use { server ->
            server.start()
            enqueue(server, JSONObject("""{"wallet":true,"account_state":"active","seqno":$seqno}"""))
            assertThrows(Exception::class.java) { source(server).getSeqno(address) }
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("""{"ok":false,"code":401,"error":"unauthorized"}""").build())
            assertThrows(TosRpcException::class.java) { source(server).getSeqno(address) }
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start(); enqueue(server, vectors().last().getJSONObject("wallet_information"))
            enqueue(server, JSONObject().put("state", "uninitialized").put("code", "").put("data", ""))
            assertThrows(Exception::class.java) { source(server).getSeqno(address) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun legacyFallbackRetainsOneEndpointAndCredentialSnapshot() {
        val vector = vectors().last()
        MockWebServer().use { server ->
            server.start()
            var endpointReads = 0; var credentialReads = 0
            val rpc = TosSource(OkHttpClient(), {
                if (++endpointReads == 1) server.url("/").toString() else "http://127.0.0.1:1"
            }, { if (++credentialReads == 1) "PUBLIC-TEST-original" else "PUBLIC-TEST-replaced" })
            enqueue(server, vector.getJSONObject("wallet_information"))
            enqueue(server, vector.getJSONObject("account_state"))
            assertEquals(1, rpc.getSeqno(vector.getString("address")))
            assertEquals(1, endpointReads); assertEquals(1, credentialReads)
            repeat(2) { assertEquals("PUBLIC-TEST-original", server.takeRequest().headers["X-API-Key"]) }
        }
    }

    @Test fun zeroRequiresTheKnownLegacyAddressAndExplicitEmptyUninitializedState() {
        val vector = vectors().last(); val wallet = contract(vector)
        val uninitialized = JSONObject().put("state", "uninitialized").put("code", "").put("data", "")
        MockWebServer().use { server ->
            server.start(); enqueue(server, uninitialized)
            assertEquals(0, source(server).getSeqno(wallet.address.toAccountId(), false, wallet))
            enqueue(server, copy(uninitialized).put("data", vector.getJSONObject("account_state").getString("data")))
            assertThrows(Exception::class.java) { source(server).getSeqno(wallet.address.toAccountId(), false, wallet) }
            assertThrows(Exception::class.java) { source(server).getSeqno("0:${"22".repeat(32)}", false, wallet) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun legacyFeeValidatesItsSingleSnapshotBeforeEstimatingAndOnlyInitializesAnUninitializedWallet() {
        val fees = JSONObject("""{"source_fees":{"in_fwd_fee":1,"storage_fee":2,"gas_fee":3,"fwd_fee":4}}""")
        for (vector in vectors()) {
            val wallet = contract(vector)
            val active = vector.getJSONObject("account_state")
            val body = wallet.createTransferUnsignedBody(2000000000L, 1, false, null).base64()
            val uninitialized = JSONObject().put("state", "uninitialized").put("code", "").put("data", "")
            val multiRootCode = Base64.getEncoder().encodeToString(BagOfCells(wallet.getCode(), Cell.empty()).toByteArray())
            val malformed = listOf(
                copy(active).put("state", "frozen"),
                copy(active).put("state", "unknown"),
                copy(active).apply { remove("state") },
                copy(active).put("code", Cell.empty().base64()),
                copy(active).put("code", multiRootCode),
                copy(active).put("data", currentData(wallet, key = ByteArray(32)).base64()),
                copy(active).put("data", currentData(wallet, walletId = 42).base64()),
                copy(uninitialized).put("data", active.getString("data")),
            )
            for (state in malformed) MockWebServer().use { server ->
                server.start(); enqueue(server, state); enqueue(server, fees)
                assertThrows(Exception::class.java) {
                    source(server).estimateLegacyWalletFee(wallet.address.toAccountId(), body, wallet)
                }
                assertEquals("Invalid snapshot reached estimateFee", 1, server.requestCount)
                assertEquals("getAddressInformation", JSONObject(server.takeRequest().body!!.utf8()).getString("method"))
            }
            for ((state, needsInit) in listOf(active to false, uninitialized to true)) MockWebServer().use { server ->
                server.start()
                var endpointReads = 0; var credentialReads = 0
                val node = TosSource(OkHttpClient(), {
                    if (++endpointReads == 1) server.url("/").toString() else "http://127.0.0.1:1"
                }, { if (++credentialReads == 1) "PUBLIC-TEST-fee-node" else "PUBLIC-TEST-replaced" })
                enqueue(server, state); enqueue(server, fees)
                assertEquals(10L, node.estimateLegacyWalletFee(wallet.address.toAccountId(), body, wallet).total)
                val snapshotRequest = server.takeRequest(); val estimateRequest = server.takeRequest()
                assertEquals("getAddressInformation", JSONObject(snapshotRequest.body!!.utf8()).getString("method"))
                val estimate = JSONObject(estimateRequest.body!!.utf8())
                assertEquals("estimateFee", estimate.getString("method"))
                val params = estimate.getJSONObject("params")
                assertEquals(wallet.address.toAccountId(), params.getString("address")); assertEquals(body, params.getString("body"))
                assertEquals(needsInit, params.has("init_code")); assertEquals(needsInit, params.has("init_data"))
                if (needsInit) {
                    assertEquals(wallet.getCode().hash(), params.getString("init_code").cellFromBase64().hash())
                    assertEquals(wallet.getStateCell().hash(), params.getString("init_data").cellFromBase64().hash())
                }
                assertEquals(1, endpointReads); assertEquals(1, credentialReads)
                assertEquals("PUBLIC-TEST-fee-node", snapshotRequest.headers["X-API-Key"])
                assertEquals("PUBLIC-TEST-fee-node", estimateRequest.headers["X-API-Key"])
            }
        }
    }

    @Test fun everyLegacySignedNonceBindsDestinationWalletIdAndSignatureLayout() {
        for (vector in vectors()) {
            val wallet = contract(vector)
            fun external(unsigned: Cell, signed: Boolean = true, destination: AddrStd = wallet.address): Cell =
                wallet.createTransferMessageCell(destination, 7,
                    if (signed) wallet.signedBody(BitString(ByteArray(64)), unsigned) else unsigned)
            val valid = wallet.createTransferUnsignedBody(2000000000L, 7, false, null)
            assertEquals(7, LegacyWalletCompatibility.signedTransferSeqno(wallet, external(valid)))
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.signedTransferSeqno(wallet, external(valid, false)) }
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.signedTransferSeqno(wallet,
                external(valid, destination = AddrStd.parse("0:${"22".repeat(32)}"))) }
            fun altered(id: Long, seqno: Long = 7, opcode: Long = 0x7369676e): Cell = buildCell {
                if (wallet.getWalletVersion() == WalletVersion.V5R1) storeUInt(opcode, 32)
                storeUInt(id, 32); storeUInt(2000000000L, 32); storeUInt(seqno, 32)
                if (wallet.getWalletVersion() == WalletVersion.V5R1) { storeBit(false); storeBit(false) }
                else if (wallet.getWalletVersion() in listOf(WalletVersion.V4R1, WalletVersion.V4R2)) storeUInt(0, 8)
            }
            val initial = wallet.getStateCell().beginParse()
            if (wallet.getWalletVersion() == WalletVersion.V5R1) initial.loadBit()
            initial.loadUInt(32); val id = initial.loadUInt(32).toLong()
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.signedTransferSeqno(wallet, external(altered(id + 1))) }
            assertThrows(Exception::class.java) { LegacyWalletCompatibility.signedTransferSeqno(wallet, external(altered(id, 0xffffffffL))) }
            if (wallet.getWalletVersion() == WalletVersion.V5R1) assertThrows(Exception::class.java) {
                LegacyWalletCompatibility.signedTransferSeqno(wallet, external(altered(id, opcode = 0x73696e74)))
            }
        }
    }

    @Test fun activeLegacyNonceAdvanceNeedsTheExactFullExternalReceiptAndNeverConfirmsAnotherSend() {
        val vector = vectors().last(); val wallet = contract(vector)
        val boc = vector.getString("external_message_boc")
        val receipt = requireNotNull(javaClass.getResourceAsStream("/tos-mobile-receipt-hash-proof.json"))
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("raw_rpc_fixture")
        MockWebServer().use { server ->
            server.start()
            fun response(hash: ByteArray) {
                enqueue(server, vector.getJSONObject("account_state"))
                enqueue(server, JSONArray().put(copy(receipt).put("in_msg_hash", Base64.getEncoder().encodeToString(hash))))
            }
            response(boc.cellFromBase64().hash().toByteArray())
            assertEquals(TosSendReconciliation.CONFIRMED,
                source(server).reconcileSend(vector.getString("address"), boc, 0, false, wallet))
            response(ByteArray(32))
            assertEquals(TosSendReconciliation.AMBIGUOUS,
                source(server).reconcileSend(vector.getString("address"), boc, 0, false, wallet))
            assertEquals(listOf("getAddressInformation", "getTransactions", "getAddressInformation", "getTransactions"),
                List(4) { JSONObject(server.takeRequest().body!!.utf8()).getString("method") })
        }
    }
}
