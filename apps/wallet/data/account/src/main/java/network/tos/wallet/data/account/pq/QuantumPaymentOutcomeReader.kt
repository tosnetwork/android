package network.tos.wallet.data.account.pq

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.ton.boc.BagOfCells
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Original durable authorization reconciliation, never signing/retry/release authority. */
class QuantumPaymentOutcomeReader(
    private val context: Context, private val accounts: QuantumAccountReader,
    private val query: QuantumHistoryReader.Query, private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Outcome { SenderSent, IncludedWithoutExactTransfer }
    class Receipt private constructor(
        private val binding: QuantumAuthorizationBinding,
        private val observation: QuantumAccountReader.Observation,
        val transactionHash: String, val logicalTime: String, val outcome: Outcome,
    ) {
        val operationId: UUID = binding.operationId
        internal val identity: TosQuantumSelfPayIdentity get() = binding.identity
        internal fun requireCurrent(entry: QuantumPaymentOutbox.Entry, now: Long) {
            observation.requireFresh(now)
            check(observation.identity == entry.identity && sameProfile(observation.profile, entry.profile))
            binding.requireCurrent(entry)
        }
        companion object {
            /** The private receipt constructor is reachable only after this authenticated chain walk. */
            internal suspend fun read(reader: QuantumPaymentOutcomeReader, id: UUID, identity: TosQuantumSelfPayIdentity,
                                      cursor: Cursor?, limit: Int): Page {
                require(limit in 1..20)
                return reader.perform(identity) { running ->
                    val record = QuantumCreationRecord.load(reader.context, id); check(record.id == id && record.identity == identity)
                    val outbox = QuantumPaymentOutbox(reader.context); val entry = checkNotNull(outbox.pending(identity))
                    check(sameProfile(entry.profile, record.profile))
                    val binding = QuantumAuthorizationBinding.capture(entry)
                    val bytes = checkNotNull(entry.signedBOC) { "Signing outcome is unknown; no message hash is available" }
                    check(entry.phase in setOf(QuantumPaymentOutbox.Phase.SIGNED, QuantumPaymentOutbox.Phase.SUBMITTING, QuantumPaymentOutbox.Phase.UNKNOWN))
                    if (cursor != null) {
                        check(cursor.id == id) { "Payment result cursor wallet mismatch" }
                        cursor.binding.requireCurrent(entry)
                    }
                    val observation = reader.accounts.observe(id, identity); observation.requireFresh(reader.now()); running()
                    check(sameProfile(observation.profile, entry.profile))
                    val address = QuantumAccountReader.rawAddress(identity)
                    val shardTime = observation.proof.provenAccountTime(address)
                    val historicalTime = cursor?.shardTime ?: shardTime
                    val head = observation.proof.provenTransactionHead(address)
                    var time = cursor?.lt ?: head.logicalTime; var hash = cursor?.hash ?: head.hash
                    var newer = cursor?.newerTimestamp; var count = 0; var receipt: Receipt? = null
                    val externalHash = QuantumPaymentQuote.hex(BagOfCells(bytes).roots.single().hash().toByteArray())
                    while (time != "0" && count < limit) {
                        running()
                        val tx = QuantumHistoryTransaction.verify(address, time, hash, reader.query.transaction(address, time, hash))
                        check(tx.timestamp <= historicalTime && (newer == null || tx.timestamp <= newer!!))
                        count++; newer = tx.timestamp; time = tx.previousLogicalTime; hash = tx.previousHash
                        if (tx.inbound?.hash == externalHash) {
                            check(tx.inbound.kind == "external_in" && tx.inbound.destination == address)
                            receipt = Receipt(binding, observation, tx.hash, tx.logicalTime,
                                if (tx.matchesPayment(externalHash, entry.intent)) Outcome.SenderSent else Outcome.IncludedWithoutExactTransfer)
                            break
                        }
                    }
                    observation.requireFresh(reader.now()); running()
                    outbox.validateBinding(binding)
                    receipt?.let { outbox.validateReceipt(it, reader.now()) }
                    Page(entry.id, receipt, if (receipt == null && time != "0") Cursor(id, binding, historicalTime, time, hash, newer) else null,
                        count, shardTime, shardTime > binding.deadline)
                }
            }
        }
    }
    class Cursor internal constructor(internal val id: UUID, internal val binding: QuantumAuthorizationBinding,
        internal val shardTime: Long, internal val lt: String, internal val hash: String, internal val newerTimestamp: Long?)
    data class Page(val operationId: UUID, val receipt: Receipt?, val next: Cursor?, val scannedCount: Int,
        val provenShardTime: Long, val authorizationExpired: Boolean)
    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity, cursor: Cursor? = null, limit: Int = 20): Page =
        Receipt.read(this, id, identity, cursor, limit)

    /** Fresh current proof only: no historical cursor, nonce inference or local-clock expiry. */
    suspend fun expiry(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumExpiredAuthorization = perform(identity) { running ->
        QuantumExpiredAuthorization.acquire(context, accounts, id, identity, now, running)
    }
    private suspend fun <T> perform(identity: TosQuantumSelfPayIdentity, body: (() -> Unit) -> T): T {
        val originalFailure = AtomicReference<Throwable?>()
        try {
            return runInterruptible(Dispatchers.IO) {
                val birth = QuantumPaymentQuote.hex(identity.cell.hash().toByteArray())
                var registered = false
                try {
                    synchronized(active) {
                        check(active.size < 2 && birth !in active) { "Payment result reader busy" }
                        active.add(birth); registered = true
                    }
                    val deadline = System.nanoTime() + 60_000_000_000L
                    val running = {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Payment result cancelled")
                        check(System.nanoTime() - deadline < 0) { "Payment result deadline exceeded" }
                    }
                    running(); val result = body(running); running(); result
                } catch (error: Exception) {
                    // Never replace a real journal/checkpoint/binding failure with concurrent cancellation.
                    if (error !is InterruptedException && error !is CancellationException) originalFailure.set(error)
                    throw error
                } finally { if (registered) synchronized(active) { active.remove(birth) } }
            }
        } catch (cancelled: CancellationException) { originalFailure.get()?.let { throw it }; throw cancelled }
    }
    companion object { private val active = HashSet<String>() }
}
