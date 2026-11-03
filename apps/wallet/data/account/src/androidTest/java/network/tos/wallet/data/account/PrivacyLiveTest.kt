package network.tos.wallet.data.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumProofSession
import network.tos.wallet.data.account.pq.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import java.io.File
import java.util.UUID

/** Opt-in App reader/native replay integration against a fresh isolated empty pool. */
@RunWith(AndroidJUnit4::class)
class PrivacyLiveTest {
    @Test fun authenticatedPoolObservationAndCompleteHistory() = exerciseAuthenticatedPool(summary = false)

    @Test fun authenticatedEmptyPoolSummaryRechecksFreshnessAndCancellation() = exerciseAuthenticatedPool(summary = true)

    @Test fun originalProtectedMasterFeedsTheUserObservationReader() = exerciseAuthenticatedPool(summary = true, originalMaster = true)

    @Test fun consecutiveReceiptsReserveDistinctIndices() =
        exerciseAuthenticatedPool(summary = false, originalMaster = true, receive = true)

    @Test fun installedPaymentProverAuditsAgainstVerifiedPool() =
        exerciseAuthenticatedPool(summary = false, prover = true)

    private fun exerciseAuthenticatedPool(
        summary: Boolean,
        originalMaster: Boolean = false,
        receive: Boolean = false,
        prover: Boolean = false,
    ) {
        val encoded = InstrumentationRegistry.getArguments().getString("privacyLiveConfig").orEmpty()
        assumeTrue("Provide privacyLiveConfig for isolated live-pool validation", encoded.isNotEmpty())
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size <= 131072)
        val config = JSONObject(bytes.toString(Charsets.UTF_8))
        fun hash(name: String): ByteArray {
            val text = config.getString(name)
            require(text.matches(Regex("[0-9a-f]{64}")))
            return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val endpoint = config.getString("rpc").toHttpUrl()
        require(endpoint.scheme == "http" && endpoint.host == "10.0.2.2")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID()
        val profile = QuantumNetworkProfile(3, config.getString("root_hash"), config.getString("file_hash"))
        val fixture = instrumentation.context.assets.open("quantum-self-pay-live/public-self-pay-accounts.json").use { it.readBytes() }
        val original = JSONObject(fixture.toString(Charsets.UTF_8)).getJSONObject("accounts").getJSONObject("active")
            .getString("identity_boc").let { TosQuantumSelfPayIdentity.fromBoc(Base64.decode(it, Base64.NO_WRAP)) }
        val authority = original.wallet.initialAuthority
        // Public mnemonic already used by QuantumWalletCreationTest; no funded/user material.
        val words = "twice half glass steak version only friend bone addict during joke offer fog punch panel true regular letter topple often afraid hint ready exhibit".split(" ")
        val identity = if (!originalMaster) TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(3,
            authority.namespace, authority.primaryKey, authority.rescueKey))) else {
            var master = ByteArray(0); var primary = ByteArray(0); var rescue = ByteArray(0)
            try {
                master = network.tos.blockchain.TosQuantumMnemonic.masterAndWipePassword(words, CharArray(0))
                primary = network.tos.security.pq.QuantumKdf.deriveAndWipe(network.tos.security.pq.QuantumKdf.Material.PRIMARY,
                    master.copyOf(), authority.namespace, 3, 0, 0)
                rescue = network.tos.security.pq.QuantumKdf.deriveAndWipe(network.tos.security.pq.QuantumKdf.Material.RESCUE,
                    master.copyOf(), authority.namespace, 3, 0, 0)
                TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(3, authority.namespace,
                    network.tos.security.pq.QuantumCrypto.publicKey(network.tos.security.pq.QuantumRole.PRIMARY, primary),
                    network.tos.security.pq.QuantumCrypto.publicKey(network.tos.security.pq.QuantumRole.RESCUE, rescue))))
            } finally { master.fill(0); primary.fill(0); rescue.fill(0) }
        }
        val address = AddrStd.parse("0:" + config.getString("pool_account"))
        val code = BagOfCells(Base64.decode(config.getString("code_boc"), Base64.NO_WRAP)).roots.single()
        val client = OkHttpClient()
        val transport = QuantumProofHttpTransport(endpoint, client)
        fun now() = System.currentTimeMillis() / 1000
        val custody = if (originalMaster) QuantumCreationCustody(context) else null
        try {
            custody?.saveMnemonic(id, words)
            val session = QuantumProofSession(context, id, profile.anchor())
            val request = JSONObject().put("mode", "live").put("max_age_seconds", 300)
                .put("config_params", org.json.JSONArray(listOf(19, 21))).toString().toByteArray()
            session.enrollBound(request, now(), transport)
            val reader = QuantumAccountReader(context, transport, ::now)
            val profileHash = hash("profile_hash")
            val manifestHash = hash("poseidon_manifest_hash")
            val vkHash = hash("groth16_vk_hash")
            val observation = reader.observePool(id, identity, profile, address, code, profileHash, manifestHash, vkHash)
            if (originalMaster) {
                val record = QuantumCreationRecord(id, profile, identity)
                record.save(context)
                val selection = network.tos.wallet.data.account.privacy.PrivacyPoolSelection(profile,
                    "0:" + config.getString("pool_account"), Base64.decode(config.getString("code_boc"), Base64.NO_WRAP),
                    config.getString("profile_hash"), config.getString("poseidon_manifest_hash"), config.getString("groth16_vk_hash"))
                if (receive) {
                    val root = File(context.noBackupFilesDir, "privacy-receive-${UUID.randomUUID()}")
                    android.system.Os.mkdir(root.absolutePath, 448) // 0700
                    try {
                        val balanceReader = PrivacyBalanceReader(reader, QuantumHistoryHttpTransport(endpoint, client), ::now)
                        val rootBytes = root.absolutePath.toByteArray(Charsets.UTF_8)
                        val pending = balanceReader.pendingStatus(record, selection, rootBytes, { false }) { body ->
                            requireNotNull(custody).withOriginalPrivacyMaster(id, identity, body)
                        }
                        assertEquals(network.tos.security.privacy.PrivacyNative.Replay.PendingStatus.NO_RECORD, pending)
                        repeat(2) { expected ->
                            val descriptor = balanceReader.receive(record, selection, rootBytes, { false }) { body ->
                                requireNotNull(custody).withOriginalPrivacyMaster(id, identity, body)
                            }
                            val wire = java.nio.ByteBuffer.wrap(descriptor)
                            assertEquals(2573, descriptor.size)
                            assertEquals(0x53484431, wire.int)
                            assertEquals(expected.toLong(), wire.getLong(37))
                            assertTrue(balanceReader.validateRecipient(record, selection, descriptor) { false })
                            val wrongDomain = descriptor.copyOf()
                            wrongDomain[36] = (wrongDomain[36].toInt() xor 1).toByte()
                            assertFalse(balanceReader.validateRecipient(record, selection, wrongDomain) { false })
                        }
                    } finally {
                        assertTrue(root.deleteRecursively())
                    }
                }
                val observed = PrivacyBalanceReader(reader, QuantumHistoryHttpTransport(endpoint, client), ::now)
                    .read(QuantumCreationRecord.load(context, id), selection, { false }) { body ->
                        requireNotNull(custody).withOriginalPrivacyMaster(id, identity, body)
                    }
                assertEquals("0", observed.summary.unspentBalance)
                assertEquals("0", observed.summary.totalOutputs)
                assertEquals(selection.pool, observed.pool); assertEquals(3, observed.globalId)
                assertTrue(observed.checkedAt - observed.provenShardTime in -60..300)
                assertEquals("Explicit local deployment pins", observed.configurationSource)
            }
            val completed = PrivacyHistorySynchronizer.synchronize(observation,
                QuantumHistoryHttpTransport(endpoint, client), { false }, ::now, forReceive = prover)
            completed.use {
                assertTrue(it.transactions().isNotEmpty())
                assertFalse(it.intentAccepted(ByteArray(32).apply { this[31] = 1 }, ::now))
                val info = JSONObject(it.info(now()).toString(Charsets.UTF_8))
                assertEquals("0", info.getString("total_outputs"))
                assertEquals("0", info.getString("native_liability"))
                if (prover) {
                    val root = java.io.File(context.filesDir, "tos-privacy-prover")
                    it.loadPaymentProver(java.io.File(root, "phase1"),
                        java.io.File(root, "ceremony"), ::now).use { }
                }
                if (summary) {
                    // Public deterministic test seed; this empty-pool case does not prove
                    // nonempty private note discovery, ownership or product backup recovery.
                    val seed = ByteArray(32) { 0x42 }
                    val complete = JSONObject(it.summaryAndWipeSeed(seed, ::now).toString(Charsets.UTF_8))
                    assertArrayEquals(ByteArray(32), seed)
                    assertEquals("0", complete.getString("total_outputs"))
                    assertEquals("0", complete.getString("unspent_balance"))
                    assertEquals("0", complete.getString("unique_unspent_notes"))
                    assertEquals("0", complete.getString("scan_next_index"))
                    assertFalse(complete.has("batch_unspent_balance"))

                    var clockReads = 0
                    val stale = ByteArray(32) { 0x42 }
                    val expired = assertThrows(IllegalStateException::class.java) {
                        it.summaryAndWipeSeed(stale, now = { clockReads++; now() + if (clockReads == 2) 3600 else 0 })
                    }
                    assertEquals("Proof freshness refused", expired.message)
                    assertEquals(2, clockReads); assertArrayEquals(ByteArray(32), stale)
                    for (stopBefore in listOf(true, false)) {
                        var checks = 0
                        val cancelled = ByteArray(32) { 0x42 }
                        val error = assertThrows(IllegalStateException::class.java) {
                            it.summaryAndWipeSeed(cancelled, ::now, stopped = { checks++; stopBefore || checks == 2 })
                        }
                        assertEquals("Privacy summary cancelled", error.message)
                        assertEquals(if (stopBefore) 1 else 2, checks)
                        assertArrayEquals(ByteArray(32), cancelled)
                    }
                    val retry = ByteArray(32) { 0x42 }
                    assertEquals("0", JSONObject(it.summaryAndWipeSeed(retry, ::now).toString(Charsets.UTF_8)).getString("unspent_balance"))
                    assertArrayEquals(ByteArray(32), retry)
                }
                // The positive operation above proves this refusal is not an unavailable RPC.
                try {
                    reader.observePool(id, identity, profile, address, code, ByteArray(32), manifestHash, vkHash)
                    fail("Changed pool profile was accepted")
                } catch (expected: IllegalArgumentException) {
                    assertEquals("Privacy pool deployment pins do not match", expected.message)
                }
            }
            try { completed.info(now()); fail("Closed replay remained usable") }
            catch (_: IllegalStateException) { }
            if (summary) {
                val seed = ByteArray(32) { 0x42 }
                assertThrows(IllegalStateException::class.java) { completed.summaryAndWipeSeed(seed, ::now) }
                assertArrayEquals(ByteArray(32), seed)
            }
        } finally {
            custody?.delete(id)
            if (originalMaster) File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
        }
    }
}
