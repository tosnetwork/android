package network.tos.wallet.data.account

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.data.account.pq.QuantumHistoryTransaction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class QuantumHistoryTransactionTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun read(name: String) = assets.open("quantum-transactions/$name").use { it.readBytes() }
    @Test fun incomingSkippedComputeRemainsIncludedAndHeadChainReachesZero() {
        val manifest = JSONObject(read("incoming-manifest.json").toString(Charsets.UTF_8))
        val account = manifest.getString("account")
        var hash = manifest.getString("proven_head"); var time = manifest.getString("proven_lt")
        val rows = manifest.getJSONArray("transactions"); val fees = ArrayList<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index); val boc = read(row.getString("file"))
            val digest = MessageDigest.getInstance("SHA-256").digest(boc).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(row.getString("sha256"), digest)
            val transaction = QuantumHistoryTransaction.verify(account, time, hash, Base64.encodeToString(boc, Base64.NO_WRAP))
            assertEquals(QuantumHistoryTransaction.Direction.Incoming, transaction.direction)
            assertEquals(QuantumHistoryTransaction.Outcome.Included, transaction.outcome)
            assertFalse(transaction.successfulExecution)
            assertEquals("10000000000", transaction.inbound?.amountNano)
            assertTrue(transaction.outbound.isEmpty())
            fees.add(transaction.feesNano); hash = transaction.previousHash; time = transaction.previousLogicalTime
        }
        assertEquals(listOf("1", "0"), fees)
        assertEquals("0", time); assertEquals("00".repeat(32), hash)
    }
    @Test fun skippedSendAndOpaqueActualOutgoingAreDistinct() {
        val manifest = JSONObject(read("manifest.json").toString(Charsets.UTF_8))
        val account = "0:" + manifest.getString("account")
        val rows = manifest.getJSONArray("transactions")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val transaction = QuantumHistoryTransaction.verify(account, row.getString("lt"), row.getString("hash"),
                Base64.encodeToString(read(row.getString("file")), Base64.NO_WRAP))
            assertTrue(transaction.successfulExecution)
            if (index == 0) {
                assertEquals(QuantumHistoryTransaction.Direction.Operation, transaction.direction)
                assertEquals(QuantumHistoryTransaction.Outcome.SendSkipped, transaction.outcome)
                assertEquals(1L, transaction.skippedActions); assertTrue(transaction.outbound.isEmpty())
            } else {
                assertEquals(QuantumHistoryTransaction.Direction.Outgoing, transaction.direction)
                assertEquals(QuantumHistoryTransaction.Outcome.Included, transaction.outcome)
                assertEquals(row.getJSONArray("outgoing_amounts").getString(0), transaction.outbound.single().amountNano)
            }
        }
    }
    @Test fun exactNanoFormattingPreservesWideValuesAndRefusesInvalidCoins() {
        assertEquals("0 TOS", QuantumHistoryTransaction.formatNano("0"))
        assertEquals("0.000000001 TOS", QuantumHistoryTransaction.formatNano("1"))
        assertEquals("10 TOS", QuantumHistoryTransaction.formatNano("10000000000"))
        assertEquals("1267650600228229401496.703205393 TOS", QuantumHistoryTransaction.formatNano("1267650600228229401496703205393"))
        for (invalid in listOf("", "01", "-1", "1.0", "١", java.math.BigInteger.ONE.shiftLeft(120).toString())) {
            assertNull(QuantumHistoryTransaction.formatNano(invalid))
        }
    }
    @Test fun fullUnsignedTimesRemainExactAndInvalidValuesRefuse() {
        assertEquals(ULong.MAX_VALUE, QuantumHistoryTransaction.time("18446744073709551615"))
        for (invalid in listOf("01", "-1", "1e3", "18446744073709551616")) {
            assertTrue(runCatching { QuantumHistoryTransaction.time(invalid) }.isFailure)
        }
    }
}
