package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import network.tos.blockchain.ton.contract.QuantumPaymentIntent
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumSeedVault
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Dedicated two-consent path. App authentication must precede authorizeAndEstimate.
 * The production constructor uses the retained encrypted device custody, never a fixture key.
 */
class QuantumPaymentCoordinator internal constructor(
    private val context: Context, private val transport: QuantumProofTransport,
    private val broadcaster: (ByteArray) -> Unit,
    private val signer: (QuantumPaymentOutbox.Entry, UUID) -> ByteArray,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(context: Context, transport: QuantumProofTransport, broadcaster: (ByteArray) -> Unit) :
        this(context.applicationContext, transport, broadcaster, { entry, id ->
            val record = QuantumCreationRecord.load(context, id)
            check(record.identity == entry.identity && sameProfile(record.profile, entry.profile))
            val binding = QuantumSeedVault.Context(entry.identity.wallet.initialAuthority.namespace, entry.profile.globalId, 0, 0)
            QuantumCreationCustody(context).signPayment(id, binding, entry.signingPublicKey, entry.requestHash)
        })
    private class Job {
        private val deadline = System.nanoTime() + 60_000_000_000L
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Payment cancelled")
            check(System.nanoTime() - deadline < 0) { "Payment operation expired" }
        }
    }
    private suspend fun <T> perform(identity: TosQuantumSelfPayIdentity, action: (Job) -> T): T {
        val key = QuantumPaymentQuote.hex(identity.cell.hash().toByteArray())
        val originalFailure = AtomicReference<Throwable?>()
        try {
            return runInterruptible(Dispatchers.IO) {
                synchronized(active) { check(active.size < 2 && key !in active) { "Payment worker busy" }; active.add(key) }
                try {
                    val job = Job(); job.check(); val result = action(job); job.check(); result
                } catch (e: Exception) {
                    // Cancellation cannot hide an uncertain journal/checkpoint write.
                    if (e is IOException || e is ErrnoException || e is IllegalStateException) originalFailure.set(e)
                    throw e
                } finally { synchronized(active) { active.remove(key) } }
            }
        } catch (cancelled: CancellationException) { originalFailure.get()?.let { throw it }; throw cancelled }
    }
    private fun relay(job: Job) = QuantumProofTransport { bytes, capacity ->
        job.check(); val result = transport.query(bytes, capacity); job.check(); result
    }
    private fun pending(outbox: QuantumPaymentOutbox, id: UUID, identity: TosQuantumSelfPayIdentity): QuantumPaymentOutbox.Entry? {
        val record = QuantumCreationRecord.load(context, id)
        check(record.id == id && record.identity == identity)
        return outbox.pending(identity)?.also { check(sameProfile(it.profile, record.profile)) }
    }
    suspend fun pending(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumPaymentOutbox.Entry? = perform(identity) { job ->
        job.check(); pending(QuantumPaymentOutbox(context), id, identity)
    }
    suspend fun prepare(id: UUID, identity: TosQuantumSelfPayIdentity, intent: QuantumPaymentIntent, ttl: Long = 120): QuantumPreparedPayment = perform(identity) { job ->
        require(ttl in 1..3600)
        val observation = QuantumAccountReader(context, relay(job), now).observe(id, identity, intArrayOf(8, 19, 48))
        job.check(); QuantumPreparedPayment(observation, intent, now(), ttl)
    }
    /** User consent for local signing/estimation only. This never broadcasts. */
    suspend fun authorizeAndEstimate(payment: QuantumPreparedPayment): QuantumPaymentQuote = perform(payment.identity) { job ->
        payment.requireFresh(now())
        val outbox = QuantumPaymentOutbox(context)
        val record = QuantumCreationRecord.load(context, payment.walletId)
        check(record.identity == payment.identity && sameProfile(record.profile, payment.profile))
        val entry = outbox.reserve(payment, now())
        outbox.sign(entry) { stored -> job.check(); payment.requireFresh(now()); signer(stored, payment.walletId) }
        job.check()
        val signed = checkNotNull(pending(outbox, payment.walletId, payment.identity)); check(signed.id == entry.id)
        val bytes = checkNotNull(signed.signedBOC)
        val session = QuantumProofSession(context, payment.walletId, payment.anchorBytes)
        val simulation = session.simulate(payment.simulationRequest(), now(), bytes, relay(job))
        job.check(); QuantumPaymentQuote(payment, signed, simulation, now())
    }
    /** User confirms this exact displayed estimate; all transport outcomes retain unknown. */
    suspend fun submit(quote: QuantumPaymentQuote) = perform(quote.payment.identity) { job ->
        val outbox = QuantumPaymentOutbox(context)
        val entry = checkNotNull(pending(outbox, quote.payment.walletId, quote.payment.identity))
        val bytes = outbox.beginSubmission(entry, quote, now())
        try {
            job.check(); quote.requireFresh(entry, now()); job.check() // Recheck after lock/fsync before dispatch.
            broadcaster(bytes)
        } catch (e: Exception) { outbox.unknown(entry); throw e }
        outbox.unknown(entry)
    }
    suspend fun refresh(quote: QuantumPaymentQuote): QuantumPaymentQuote = perform(quote.payment.identity) { job ->
        quote.payment.requireFresh(now())
        val entry = checkNotNull(pending(QuantumPaymentOutbox(context), quote.payment.walletId, quote.payment.identity))
        quote.requireSameEntry(entry)
        val simulation = QuantumProofSession(context, quote.payment.walletId, quote.payment.anchorBytes)
            .simulate(quote.payment.simulationRequest(), now(), checkNotNull(entry.signedBOC), relay(job))
        job.check(); QuantumPaymentQuote(quote.payment, entry, simulation, now())
    }
    /** Restores only a signed message that never entered broadcast, with a fresh native estimate. */
    suspend fun estimatePending(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumPaymentQuote = perform(identity) { job ->
        val entry = checkNotNull(pending(QuantumPaymentOutbox(context), id, identity))
        check(entry.phase == QuantumPaymentOutbox.Phase.SIGNED) { "Query authenticated outcome before recovery" }
        val observation = QuantumAccountReader(context, relay(job), now).observe(id, identity, intArrayOf(8, 19, 48))
        val payment = QuantumPreparedPayment(observation, entry.intent, now(), 1, entry)
        job.check()
        val simulation = QuantumProofSession(context, id, payment.anchorBytes)
            .simulate(payment.simulationRequest(), now(), checkNotNull(entry.signedBOC), relay(job))
        job.check(); QuantumPaymentQuote(payment, entry, simulation, now())
    }
    suspend fun cancelUnsigned(id: UUID, identity: TosQuantumSelfPayIdentity, operation: UUID) = perform(identity) { _ ->
        val outbox = QuantumPaymentOutbox(context); val entry = checkNotNull(pending(outbox, id, identity))
        check(entry.id == operation); outbox.cancelUnsigned(entry)
    }
    companion object { private val active = HashSet<String>() }
}
