package network.tos.wallet.data.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.security.pq.QuantumProofSession
import network.tos.wallet.data.account.pq.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires the fresh f2d public-signer probe; does not sign from phone custody. */
@RunWith(AndroidJUnit4::class)
class QuantumHistoryLiveTest {
    @Test fun proofAnchoredHistoryIgnoresMetadataAndRefusesCorruptRawTransaction() {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString("quantumHistoryEndpoint")
        val root = arguments.getString("quantumHistoryRoot")
        val file = arguments.getString("quantumHistoryFile")
        assumeTrue("Requires fresh disposable native history chain", endpoint != null && root != null && file != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = instrumentation.context.assets.open("quantum-self-pay-live/public-self-pay-accounts.json")
            .use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(original.getJSONObject("accounts")
            .getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val profile = QuantumNetworkProfile(1, checkNotNull(root), checkNotNull(file))
        val id = UUID.randomUUID(); val url = (checkNotNull(endpoint) + "/jsonRPC").toHttpUrl()
        val client = OkHttpClient()
        val proofTransport = QuantumProofHttpTransport(url, client)
        fun mode(value: String) {
            val request = Request.Builder().url((endpoint + "/__history_mode").toHttpUrl())
                .post(JSONObject().put("mode", value).toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful)
                assertEquals(value, JSONObject(checkNotNull(response.body).string()).getString("mode"))
            }
        }
        try {
            QuantumCreationRecord(id, profile, identity).save(context)
            val enrollment = JSONObject().put("mode", "live").put("max_age_seconds", 300)
                .put("config_params", JSONArray(listOf(19))).toString().toByteArray()
            QuantumProofSession(context, id, profile.anchor()).enrollBound(enrollment, System.currentTimeMillis() / 1000, proofTransport)
            val reader = QuantumHistoryReader(QuantumAccountReader(context, proofTransport), QuantumHistoryHttpTransport(url, client))
            mode("normal")
            val page = runBlocking { reader.read(id, identity) }
            assertEquals(2, page.transactions.size); assertNull(page.next)
            for (transaction in page.transactions) {
                assertTrue(transaction.successfulExecution)
                assertEquals(QuantumHistoryTransaction.Direction.Outgoing, transaction.direction)
                assertEquals("10000000000", transaction.outbound.single().amountNano)
                assertNotEquals("999999999999", transaction.feesNano)
                assertTrue(transaction.timestamp > 0)
            }
            // Pagination resumes only from the reader's opaque proof-bound cursor.
            val first = runBlocking { reader.read(id, identity, limit = 1) }
            val next = checkNotNull(first.next)
            val older = runBlocking { reader.read(id, identity, next, limit = 1) }
            assertEquals(page.transactions[1].hash, older.transactions.single().hash); assertNull(older.next)
            val other = UUID.randomUUID()
            assertTrue(runCatching { runBlocking { reader.read(other, identity, next) } }.exceptionOrNull() is IllegalArgumentException)
            mode("corrupt")
            val refused = runCatching { runBlocking { reader.read(id, identity) } }.exceptionOrNull()
            assertTrue("Expected native hash binding refusal, got $refused", refused is IllegalArgumentException)
            assertEquals("Transaction verification refused", refused?.message)
            mode("normal")
            val restored = runBlocking { reader.read(id, identity) }
            assertEquals(page.transactions.map { it.hash }, restored.transactions.map { it.hash })
        } finally {
            runCatching { mode("normal") }
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }
}
