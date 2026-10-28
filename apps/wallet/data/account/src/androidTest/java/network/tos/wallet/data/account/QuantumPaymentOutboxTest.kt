package network.tos.wallet.data.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.wallet.data.account.pq.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumPaymentOutboxTest {
    @Test fun unsignedCancelAndSigningFailurePreserveSingleDurableAuthorization() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        val now = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(JSONObject(bytes("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val anchor = JSONObject(bytes("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val id = UUID.randomUUID()
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/" + identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")
        check(!journal.exists()) { "Fixture birth identity already has an authorization" }
        try {
            QuantumCreationRecord(id, profile, identity).save(context)
            val policyNames = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
            fun transcript(names: List<String>): QuantumProofTransport {
                var index = 0
                return QuantumProofTransport { _, maximum -> bytes(names[index++]).also { check(it.size <= maximum) } }
            }
            QuantumProofSession(context, id, profile.anchor()).enrollBound(bytes("policy/request.json"), now, transcript(policyNames))
            val observation = QuantumAccountReader(context, transcript(policyNames + listOf("active/material/chain-0000.tl", "active/material/account.tl"))) { now }
                .observe(id, identity, intArrayOf(8, 19, 48))
            val intent = QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "durable test")
            val payment = QuantumPreparedPayment(observation, intent, now, 120)
            val outbox = QuantumPaymentOutbox(context)
            val first = outbox.reserve(payment, now)
            assertEquals(first.id, QuantumPaymentOutbox(context).pending(identity)!!.id)
            assertThrows(IllegalStateException::class.java) { outbox.reserve(payment, now) }
            val original = journal.readBytes()
            try {
                val malformed = JSONObject(original.toString(Charsets.UTF_8))
                malformed.getJSONObject("intent").put("amount", 1.0)
                journal.writeText(malformed.toString())
                assertThrows(IllegalStateException::class.java) { outbox.pending(identity) }
            } finally { journal.writeBytes(original) }
            assertEquals(first.id, outbox.pending(identity)!!.id)
            outbox.cancelUnsigned(first)
            assertNull(outbox.pending(identity))
            val start = java.util.concurrent.CountDownLatch(1)
            val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val attempts = (0..1).map {
                    workers.submit<Pair<QuantumPaymentOutbox.Entry?, Throwable?>> {
                        check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        try { Pair(outbox.reserve(payment, now), null) }
                        catch (error: Throwable) { Pair(null, error) }
                    }
                }
                start.countDown()
                val results = attempts.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.first != null })
                assertEquals(1, results.count { it.second is IllegalStateException })
                outbox.cancelUnsigned(results.single { it.first != null }.first!!)
            } finally { start.countDown(); workers.shutdownNow() }
            val second = outbox.reserve(payment, now)
            assertThrows(IOException::class.java) {
                outbox.sign(second) {
                    assertEquals(QuantumPaymentOutbox.Phase.SIGNING, QuantumPaymentOutbox(context).pending(identity)!!.phase)
                    throw IOException("Interrupted custody lookup")
                }
            }
            val retained = QuantumPaymentOutbox(context).pending(identity)!!
            assertEquals(second.id, retained.id); assertEquals(QuantumPaymentOutbox.Phase.SIGNING, retained.phase)
            assertNull(retained.signedBOC)
            assertThrows(IllegalStateException::class.java) { outbox.cancelUnsigned(retained) }
            assertThrows(IllegalStateException::class.java) { outbox.reserve(payment, now) }
            val retainedBytes = journal.readBytes()
            val orphan = File(journal.parentFile, "${UUID.randomUUID()}.pending")
            try {
                assertTrue(orphan.createNewFile())
                assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox(context).pending(identity) }
                assertTrue("Uncertain write must not be silently deleted", orphan.isFile)
                assertArrayEquals(retainedBytes, journal.readBytes())
            } finally { orphan.delete() }
            assertEquals(second.id, outbox.pending(identity)!!.id)
        } finally {
            journal.delete()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }
}
