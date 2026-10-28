package network.tos.wallet.data.account.pq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.ton.boc.BagOfCells
import java.util.UUID

/** Original durable authorization reconciliation, never signing/retry/release authority. */
class QuantumPaymentOutcomeReader(
    private val context: Context, private val accounts: QuantumAccountReader,
    private val query: QuantumHistoryReader.Query, private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Outcome { SenderSent, IncludedWithoutExactTransfer }
    class Receipt internal constructor(val operationId: UUID, val transactionHash: String, val logicalTime: String, val outcome: Outcome)
    class Cursor internal constructor(internal val id: UUID, internal val entry: QuantumPaymentOutbox.Entry,
        internal val shardTime: Long, internal val lt: String, internal val hash: String, internal val newerTimestamp: Long?)
    data class Page(val operationId: UUID, val receipt: Receipt?, val next: Cursor?, val scannedCount: Int,
        val provenShardTime: Long, val authorizationExpired: Boolean)
    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity, cursor: Cursor? = null, limit: Int = 20): Page {
        require(limit in 1..20)
        return runInterruptible(Dispatchers.IO) {
            val birth = QuantumPaymentQuote.hex(identity.cell.hash().toByteArray())
            synchronized(active) { check(active.size < 2 && birth !in active) { "Payment result reader busy" }; active.add(birth) }
            try {
                val deadline = System.nanoTime() + 60_000_000_000L
                fun running() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Payment result cancelled"); check(System.nanoTime() - deadline < 0) }
                running()
                val record = QuantumCreationRecord.load(context, id); check(record.identity == identity)
                val outbox = QuantumPaymentOutbox(context); val entry = checkNotNull(outbox.pending(identity))
                check(sameProfile(entry.profile, record.profile))
                val bytes = checkNotNull(entry.signedBOC) { "Signing outcome is unknown; no message hash is available" }
                check(entry.phase in setOf(QuantumPaymentOutbox.Phase.SIGNED, QuantumPaymentOutbox.Phase.SUBMITTING, QuantumPaymentOutbox.Phase.UNKNOWN))
                if (cursor != null) check(cursor.id == id && sameEntry(cursor.entry, entry)) { "Payment result cursor binding mismatch" }
                val observation = accounts.observe(id, identity); observation.requireFresh(now()); running()
                val address = QuantumAccountReader.rawAddress(identity)
                val shardTime = observation.proof.provenAccountTime(address)
                val historicalTime = cursor?.shardTime ?: shardTime
                val head = observation.proof.provenTransactionHead(address)
                var time = cursor?.lt ?: head.logicalTime; var hash = cursor?.hash ?: head.hash
                var newer = cursor?.newerTimestamp; var count = 0; var receipt: Receipt? = null
                val externalHash = QuantumPaymentQuote.hex(BagOfCells(bytes).roots.single().hash().toByteArray())
                while (time != "0" && count < limit) {
                    running()
                    val tx = QuantumHistoryTransaction.verify(address, time, hash, query.transaction(address, time, hash))
                    check(tx.timestamp <= historicalTime && (newer == null || tx.timestamp <= newer!!))
                    count++; newer = tx.timestamp; time = tx.previousLogicalTime; hash = tx.previousHash
                    if (tx.inbound?.hash == externalHash) {
                        check(tx.inbound.kind == "external_in" && tx.inbound.destination == address)
                        receipt = Receipt(entry.id, tx.hash, tx.logicalTime,
                            if (tx.matchesPayment(externalHash, entry.intent)) Outcome.SenderSent else Outcome.IncludedWithoutExactTransfer)
                        break
                    }
                }
                observation.requireFresh(now()); running()
                check(sameEntry(checkNotNull(outbox.pending(identity)), entry))
                Page(entry.id, receipt, if (receipt == null && time != "0") Cursor(id, entry, historicalTime, time, hash, newer) else null,
                    count, shardTime, shardTime > entry.deadline)
            } finally { synchronized(active) { active.remove(birth) } }
        }
    }
    private fun sameEntry(a: QuantumPaymentOutbox.Entry, b: QuantumPaymentOutbox.Entry) =
        a.id == b.id && a.identity == b.identity && sameProfile(a.profile, b.profile) && a.intent == b.intent && a.deploy == b.deploy &&
            a.requestHash.contentEquals(b.requestHash) && a.signedBOC.contentEquals(b.signedBOC)
    companion object { private val active = HashSet<String>() }
}
