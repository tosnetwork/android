package network.tos.wallet.data.account

import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.account.pq.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ton.boc.BagOfCells
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumExpiryCapabilityTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID()
        fun bytes(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        var now = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        private val anchor = JSONObject(bytes("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(1, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val identity = TosQuantumSelfPayIdentity.fromBoc(Base64.decode(JSONObject(bytes("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject("active").getString("identity_boc"), Base64.NO_WRAP))
        val outbox = QuantumPaymentOutbox(context)
        val journal = File(context.noBackupFilesDir, "quantum-payment-outbox/${hex(identity.cell.hash().toByteArray())}.json")
        private val names = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
        fun transport() : QuantumProofTransport {
            val replies = (names + listOf("active/material/chain-0000.tl", "active/material/account.tl")).map(::bytes)
            var calls = 0
            return QuantumProofTransport { _, maximum -> replies[calls++].also { check(it.size <= maximum) } }
        }
        fun accounts() = QuantumAccountReader(context, transport()) { now }
        fun reader(transport: QuantumProofTransport = transport()) = QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, transport) { now }, QuantumHistoryReader.Query { _, _, _ ->
            error("Expiry cannot infer safety from history")
        }) { now }
        val observation: QuantumAccountReader.Observation
        val shardTime: Long
        init {
            check(!journal.exists()) { "Fixture identity already has an authorization" }
            QuantumCreationRecord(id, profile, identity).save(context)
            var calls = 0
            QuantumProofSession(context, id, profile.anchor()).enrollBound(bytes("policy/request.json"), now,
                QuantumProofTransport { _, maximum -> bytes(names[calls++]).also { check(it.size <= maximum) } })
            observation = accounts().observe(id, identity, intArrayOf(8, 19, 48))
            shardTime = observation.proof.provenAccountTime("0:" + hex(identity.address.address.toByteArray()))
        }
        fun entry(phase: QuantumPaymentOutbox.Phase, deadline: Long): QuantumPaymentOutbox.Entry {
            val intent = QuantumPaymentIntent(identity.address, BigInteger.ONE, false, "expiry fixture")
            val active = observation.snapshot.status as QuantumSelfPayAccount.Status.Active
            // These are the ORIGINAL authorization counters. Current chain counters need not match for expiry.
            val originalState = QuantumSelfPayState(active.state.authority, epoch = 7u, primaryNonce = 9u)
            val request = TosQuantumSelfPayRequest(identity.address, originalState, QuantumAuthRole.PRIMARY, deadline,
                QuantumSelfPayAction.Execute(intent.actions()), deadline - 1, BagOfCells(observation.network.provenConfigParam(48)).roots.single())
            val value = JSONObject().put("version", 1).put("id", UUID.randomUUID().toString())
                .put("identity", WalletIdentity.Quantum(identity).toBase64()).put("profile", profile.json())
                .put("intent", QuantumPaymentOutbox.encodeIntent(intent)).put("deploy", false).put("phase", phase.name)
                .put("request", encode(BagOfCells(request.request).toByteArray())).put("request_hash", encode(request.digest))
                .put("public_key", encode(active.state.authority.primaryKey))
            if (phase in setOf(QuantumPaymentOutbox.Phase.SIGNED, QuantumPaymentOutbox.Phase.SUBMITTING, QuantumPaymentOutbox.Phase.UNKNOWN)) {
                // Canonical regression seed is public; it is never installed in production App custody.
                val sig = QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY, ByteArray(32) { 0x11 }, active.state.authority.primaryKey, request.digest)
                val message = BagOfCells(identity.wallet.external(request.submission(sig), false)).toByteArray()
                value.put("signature", encode(sig)).put("message", encode(message))
                if (phase != QuantumPaymentOutbox.Phase.SIGNED) value.put("approval", JSONObject()
                    .put("message_sha256", hex(QuantumPaymentOutbox.digest(message))).put("observed_balance", "3")
                    .put("estimated_fee", "1").put("estimated_debit", "2").put("estimated_balance", "1")
                    .put("quoted_at", deadline - 2).put("expires_at", deadline))
            }
            write(value)
            return checkNotNull(outbox.pending(identity))
        }
        fun write(value: JSONObject) {
            val fd = Os.open(journal.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW, 0x180)
            FileOutputStream(fd).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        override fun close() {
            journal.delete()
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }
    @Test fun allCanonicalPhasesRequireStrictlyLaterFreshShardTimeAndHaveNoReleaseSideEffects() {
        for (phase in QuantumPaymentOutbox.Phase.entries) Fixture().use { f ->
            for (offset in listOf(1L, 0L, -1L)) {
                val entry = f.entry(phase, f.shardTime + offset)
                val before = f.journal.readBytes()
                val result = runCatching { runBlocking { f.reader().expiry(f.id, f.identity) } }
                if (offset >= 0) assertTrue("$phase deadline offset $offset accepted", result.exceptionOrNull() is IllegalStateException)
                else {
                    val cap = result.getOrThrow()
                    assertEquals(entry.id, cap.operationId); assertEquals(entry.deadline, cap.deadline)
                    assertEquals(f.shardTime, cap.provenShardTime)
                    assertEquals(entry.signedBOC != null, cap.hasExactMessage)
                    f.outbox.validateExpired(cap, f.now)
                    assertThrows("Stale expiry capability remained usable", IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now + 301) }
                }
                assertArrayEquals("Expiry must never delete, rewrite or broadcast", before, f.journal.readBytes())
            }
        }
    }
    @Test fun emptyAuthenticatedHistoryNeverMintsAReceiptOrReleasesTheExpiredSlot(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
        val before = f.journal.readBytes()
        val page = runBlocking { f.reader().read(f.id, f.identity) }
        assertEquals(entry.id, page.operationId); assertEquals(0, page.scannedCount)
        assertNull(page.receipt); assertNull(page.next); assertTrue(page.authorizationExpired)
        assertArrayEquals(before, f.journal.readBytes())
    }
    @Test fun localFutureClockCannotSubstituteForProvenShardTime(): Unit = Fixture().use { f ->
        f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime + 1)
        f.now += 60
        assertTrue(runCatching { runBlocking { f.reader().expiry(f.id, f.identity) } }.exceptionOrNull() is IllegalStateException)
        assertTrue(f.journal.isFile)
    }
    @Test fun operationPhaseAndMissingMessageChangesRejectOldCapabilities(): Unit = Fixture().use { f ->
        val original = f.entry(QuantumPaymentOutbox.Phase.SIGNING, f.shardTime - 1)
        val cap = runBlocking { f.reader().expiry(f.id, f.identity) }
        val snapshot = original.json()
        for ((key, value) in listOf("id" to UUID.randomUUID().toString(), "phase" to QuantumPaymentOutbox.Phase.RESERVED.name, "deploy" to true)) {
            f.write(JSONObject(snapshot.toString()).put(key, value))
            assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
        }
        f.write(JSONObject(snapshot.toString()).put("profile", QuantumNetworkProfile(1, "11".repeat(32), f.profile.fileHash).json()))
        assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
        val foreign = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(f.identity.wallet.initialAuthority, 1))
        f.write(JSONObject(snapshot.toString()).put("identity", WalletIdentity.Quantum(foreign).toBase64()))
        assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
        // A signing result that appeared after proof acquisition needs a new capability.
        val signed = f.entry(QuantumPaymentOutbox.Phase.SIGNED, original.deadline)
        f.write(signed.json().put("id", original.id.toString()))
        assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
        f.write(snapshot)
        f.outbox.validateExpired(cap, f.now)
        assertThrows(IllegalStateException::class.java) { f.outbox.cancelUnsigned(original) }
    }
    @Test fun aliasCannotBypassTheSameBirthWorkerAndSlotBinding(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.RESERVED, f.shardTime - 1)
        val alias = UUID.randomUUID()
        QuantumCreationRecord(alias, f.profile, f.identity).save(f.context)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val delegate = f.transport()
        val blocking = QuantumProofTransport { query, maximum ->
            if (calls.getAndIncrement() == 0) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            delegate.query(query, maximum)
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<QuantumExpiredAuthorization> { runBlocking { f.reader(blocking).expiry(f.id, f.identity) } }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var aliasCalls = 0
            val rejected = runCatching { runBlocking { f.reader(QuantumProofTransport { _, _ -> aliasCalls++; error("Alias transport must not run") }).expiry(alias, f.identity) } }
            assertEquals("Payment result reader busy", rejected.exceptionOrNull()?.message)
            assertEquals(0, aliasCalls)
            release.countDown()
            val cap = first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            f.outbox.validateExpired(cap, f.now)
            assertEquals(entry.id, checkNotNull(f.outbox.pending(f.identity)).id)
        } finally {
            release.countDown(); executor.shutdownNow()
            File(f.context.noBackupFilesDir, "quantum-creation/$alias.json").delete()
        }
    }
    @Test fun repeatedLoadsJsonKeyOrderAndEquivalentBirthEncodingKeepStableBindings(): Unit = Fixture().use { f ->
        val original = f.entry(QuantumPaymentOutbox.Phase.UNKNOWN, f.shardTime - 1)
        val cap = runBlocking { f.reader().expiry(f.id, f.identity) }
        val source = original.json()
        val identityBytes = Base64.decode(source.getString("identity"), Base64.NO_WRAP)
        val alternativeIdentity = encode(toggleCrc(identityBytes))
        assertEquals(original.identity, WalletIdentity.quantumFromBase64(alternativeIdentity))
        repeat(32) { iteration ->
            val value = JSONObject(source.toString())
            value.put("identity", if (iteration % 2 == 0) alternativeIdentity else WalletIdentity.Quantum(
                WalletIdentity.quantumFromBase64(source.getString("identity"))).toBase64())
            for (key in listOf("profile", "intent", "approval")) value.put(key, reverseKeys(value.getJSONObject(key)))
            f.write(if (iteration % 2 == 0) reverseKeys(value) else value)
            f.outbox.validateExpired(cap, f.now)
            assertEquals(original.id, f.outbox.pending(f.identity)!!.id)
        }
    }
    @Test fun sameCellRootsWithDifferentRequestOrMessageBytesRejectOldCapability(): Unit = Fixture().use { f ->
        val entry = f.entry(QuantumPaymentOutbox.Phase.SIGNED, f.shardTime - 1)
        val cap = runBlocking { f.reader().expiry(f.id, f.identity) }
        val original = entry.json()
        for (key in listOf("request", "message")) {
            val bytes = if (key == "request") entry.requestBOC else checkNotNull(entry.signedBOC)
            val alternative = toggleCrc(bytes)
            assertFalse(bytes.contentEquals(alternative))
            assertEquals(BagOfCells(bytes).roots.single().hash(), BagOfCells(alternative).roots.single().hash())
            f.write(JSONObject(original.toString()).put(key, encode(alternative)))
            assertNotNull("Equivalent valid BOC must pass ordinary journal parsing", f.outbox.pending(f.identity))
            assertThrows("Raw $key bytes changed but old expiry remained usable", IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
        }
        f.write(original); f.outbox.validateExpired(cap, f.now)
    }
    @Test fun malformedJournalAndUncertainPendingCannotBecomeExpiryAuthority(): Unit = Fixture().use { f ->
        val original = f.entry(QuantumPaymentOutbox.Phase.RESERVED, f.shardTime - 1)
        val cap = runBlocking { f.reader().expiry(f.id, f.identity) }
        val pending = File(f.journal.parentFile, "${UUID.randomUUID()}.pending")
        try {
            assertTrue(pending.createNewFile())
            assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
            assertTrue(runCatching { runBlocking { f.reader().expiry(f.id, f.identity) } }.exceptionOrNull() is IllegalStateException)
            assertTrue(pending.exists()); assertTrue(f.journal.exists())
        } finally { pending.delete() }
        f.write(original.json().put("request_hash", encode(ByteArray(32))))
        assertThrows(IllegalStateException::class.java) { f.outbox.validateExpired(cap, f.now) }
    }
    companion object {
        private fun reverseKeys(value: JSONObject): JSONObject = JSONObject().also { output ->
            value.keys().asSequence().toList().reversed().forEach { output.put(it, value.get(it)) }
        }
        private fun toggleCrc(bytes: ByteArray): ByteArray {
            if (bytes[4].toInt() and 0x40 != 0) return bytes.copyOf(bytes.size - 4).also { it[4] = (it[4].toInt() and 0xbf).toByte() }
            val payload = bytes.copyOf().also { it[4] = (it[4].toInt() or 0x40).toByte() }
            var crc = -1
            for (byte in payload) {
                crc = crc xor (byte.toInt() and 255)
                repeat(8) { crc = (crc ushr 1) xor if (crc and 1 != 0) 0x82f63b78.toInt() else 0 }
            }
            crc = crc.inv()
            return payload + ByteArray(4) { (crc ushr (it * 8)).toByte() }
        }
        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    }
}
