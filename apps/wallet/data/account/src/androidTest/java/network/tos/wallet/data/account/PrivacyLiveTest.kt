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
    @Test fun authenticatedPoolObservationAndCompleteHistory() {
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
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(3,
            authority.namespace, authority.primaryKey, authority.rescueKey)))
        val address = AddrStd.parse("0:" + config.getString("pool_account"))
        val code = BagOfCells(Base64.decode(config.getString("code_boc"), Base64.NO_WRAP)).roots.single()
        val client = OkHttpClient()
        val transport = QuantumProofHttpTransport(endpoint, client)
        fun now() = System.currentTimeMillis() / 1000
        try {
            val session = QuantumProofSession(context, id, profile.anchor())
            val request = JSONObject().put("mode", "live").put("max_age_seconds", 300)
                .put("config_params", org.json.JSONArray(listOf(19, 21))).toString().toByteArray()
            session.enrollBound(request, now(), transport)
            val reader = QuantumAccountReader(context, transport, ::now)
            val profileHash = hash("profile_hash")
            val manifestHash = hash("poseidon_manifest_hash")
            val vkHash = hash("groth16_vk_hash")
            val observation = reader.observePool(id, identity, profile, address, code, profileHash, manifestHash, vkHash)
            val completed = PrivacyHistorySynchronizer.synchronize(observation,
                QuantumHistoryHttpTransport(endpoint, client), { false }, ::now)
            completed.use {
                assertTrue(it.transactions().isNotEmpty())
                val info = JSONObject(it.info(now()).toString(Charsets.UTF_8))
                assertEquals("0", info.getString("total_outputs"))
                assertEquals("0", info.getString("native_liability"))
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
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
        }
    }
}
