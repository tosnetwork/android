package network.tos.wallet.data.account

import android.util.Base64
import kotlinx.coroutines.runBlocking
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
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumAccountReaderTest {
    private class Fixture(role: String, globalId: Int = 1) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID()
        fun read(name: String) = instrumentation.context.assets.open("quantum-self-pay-live/$name").use { it.readBytes() }
        val now = JSONObject(read("manifest.json").toString(Charsets.UTF_8)).getLong("controlled_now")
        private val original = JSONObject(read("public-self-pay-accounts.json").toString(Charsets.UTF_8))
            .getJSONObject("accounts").getJSONObject(role).getString("identity_boc")
            .let { TosQuantumSelfPayIdentity.fromBoc(Base64.decode(it, Base64.NO_WRAP)) }
        val identity = if (globalId == 1) original else TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(
            QuantumSelfPayAuthority(globalId, original.wallet.initialAuthority.namespace,
                original.wallet.initialAuthority.primaryKey, original.wallet.initialAuthority.rescueKey)))
        private val anchor = JSONObject(read("anchor.json").toString(Charsets.UTF_8))
        val profile = QuantumNetworkProfile(globalId, anchor.getString("root_hash"), anchor.getString("file_hash"))
        val session = QuantumProofSession(context, id, profile.anchor())
        val policyNames = listOf("policy/material/masterchain-info.tl", "policy/material/chain-0000.tl", "policy/material/config.tl")
        val readNames = policyNames + listOf("$role/material/chain-0000.tl", "$role/material/account.tl")
        init {
            // New mobile enrollments use wallet ID zero. The other recorded
            // public states exercise the general enrolled proof reader only.
            if (identity.wallet.walletId == 0L) QuantumCreationRecord(id, profile, identity).save(context)
        }
        fun enroll() { session.enrollBound(read("policy/request.json"), now, Transcript(policyNames.map(::read))) }
        override fun close() {
            File(context.noBackupFilesDir, "quantum-proof-checkpoints/$id").deleteRecursively()
            File(context.noBackupFilesDir, "quantum-creation/$id.json").delete()
        }
    }
    private class Transcript(private val replies: List<ByteArray>) : QuantumProofTransport {
        var calls = 0
        override fun query(request: ByteArray, maximumBytes: Int): ByteArray {
            check(calls < replies.size) { "Unexpected native query" }
            return replies[calls++].also { check(it.size <= maximumBytes) }
        }
    }

    /** Recorded real chain material, native verified at its recorded clock. */
    @Test fun enrolledReadsUseRetainedIdentityAndProveAbsentPrefundedAndActiveBalances() {
        for (role in listOf("absent", "prefunded", "active")) Fixture(role).use { f ->
            f.enroll()
            val transport = Transcript(f.readNames.map(f::read))
            val reader = QuantumAccountReader(f.context, transport) { f.now }
            val observed = if (role == "active") reader.read(f.id, f.identity) else reader.readEnrolled(f.id, f.identity, f.profile)
            assertEquals(5, transport.calls)
            when (role) {
                "absent" -> { assertEquals(QuantumSelfPayAccount.Status.Absent, observed.status); assertEquals(BigInteger.ZERO, observed.balance) }
                "prefunded" -> { assertEquals(QuantumSelfPayAccount.Status.Uninitialized, observed.status); assertEquals(BigInteger("10000000000"), observed.balance) }
                "active" -> { assertTrue(observed.status is QuantumSelfPayAccount.Status.Active); assertEquals(BigInteger("1000000000000000"), observed.balance) }
            }
        }
    }

    @Test fun historyEmptyHeadUsesProofAndNeverCallsUntrustedTransactionRelay() = Fixture("active").use { f ->
        f.enroll()
        val transport = Transcript(f.readNames.map(f::read))
        val reader = QuantumHistoryReader(QuantumAccountReader(f.context, transport) { f.now },
            QuantumHistoryReader.Query { _, _, _ -> error("Empty authenticated head must not query RPC history") }) { f.now }
        val page = runBlocking { reader.read(f.id, f.identity) }
        assertTrue(page.transactions.isEmpty())
        assertNull(page.next)
        assertEquals("1000000000000000", page.balanceNano)
        assertEquals(5, transport.calls)
        assertTrue(page.checkpointTime <= f.now)
    }

    @Test fun invalidHistoryPageBoundRefusesBeforeProofOrTransactionIO() = Fixture("active").use { f ->
        val transport = Transcript(emptyList())
        val reader = QuantumHistoryReader(QuantumAccountReader(f.context, transport) { f.now },
            QuantumHistoryReader.Query { _, _, _ -> error("Unexpected transaction IO") }) { f.now }
        for (limit in listOf(0, 21, Int.MAX_VALUE)) {
            val failure = runCatching { runBlocking { reader.read(f.id, f.identity, limit = limit) } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        assertEquals(0, transport.calls)
    }

    @Test fun missingCheckpointCannotBeReenrolledByBalanceRefresh() = Fixture("active").use { f ->
        val transport = Transcript(f.readNames.map(f::read))
        assertNotNull(runCatching { QuantumAccountReader(f.context, transport) { f.now }.read(f.id, f.identity) }.exceptionOrNull())
        assertEquals(0, transport.calls)
        assertEquals(f.identity, QuantumCreationRecord.load(f.context, f.id).identity)
    }

    @Test fun savedProfileMustMatchProvenNetworkBeforeReadingTheAccount() = Fixture("active", globalId = 2).use { f ->
        f.enroll()
        val transport = Transcript(f.readNames.map(f::read))
        assertEquals("Account network global ID mismatch", runCatching {
            QuantumAccountReader(f.context, transport) { f.now }.read(f.id, f.identity)
        }.exceptionOrNull()?.message)
        assertEquals(3, transport.calls)
    }

    @Test fun wrongBirthIdentityIsRejectedBeforeAnyQuery() = Fixture("active").use { f ->
        val wrong = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(
            1, ByteArray(32), ByteArray(1312), ByteArray(32))))
        val transport = Transcript(f.readNames.map(f::read))
        assertEquals("Wallet birth identity mismatch", runCatching {
            QuantumAccountReader(f.context, transport) { f.now }.read(f.id, wrong)
        }.exceptionOrNull()?.message)
        assertEquals(0, transport.calls)
    }

    @Test fun staleOrCorruptRepliesCannotProduceAFreshBalance() {
        for (corrupt in listOf(false, true)) Fixture("active").use { f ->
            f.enroll()
            val replies = f.readNames.map(f::read)
            if (corrupt) replies[2][20] = (replies[2][20].toInt() xor 1).toByte()
            val transport = Transcript(replies)
            assertNotNull(runCatching {
                QuantumAccountReader(f.context, transport) { f.now + if (corrupt) 0 else 301 }.read(f.id, f.identity)
            }.exceptionOrNull())
        }
    }
}
