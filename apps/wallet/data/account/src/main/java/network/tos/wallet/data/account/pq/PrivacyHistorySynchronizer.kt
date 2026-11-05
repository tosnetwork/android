package network.tos.wallet.data.account.pq

import network.tos.security.privacy.PrivacyHistoryWalk
import network.tos.security.privacy.PrivacyNative
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Only a fresh PoolObservation supplies initial history expectations. No RPC metadata anchor. */
internal object PrivacyHistorySynchronizer {
    private val active = HashSet<UUID>()
    internal class Lease(private val id: UUID) : AutoCloseable {
        private val held = AtomicBoolean(false)
        init {
            synchronized(active) {
                check(active.size < 2 && id !in active) { "Privacy history synchronizer busy" }
                active.add(id); held.set(true)
            }
        }
        override fun close() {
            if (held.compareAndSet(true, false)) synchronized(active) { active.remove(id) }
        }
        internal fun requireHeld(expected: UUID) { check(id == expected && held.get()) { "Privacy history lease mismatch" } }
    }
    class Completed internal constructor(observation: QuantumAccountReader.PoolObservation,
                                         private val history: PrivacyHistoryWalk.Completed,
                                         private val lease: Lease) : AutoCloseable {
        private val lock = Any()
        private var observation: QuantumAccountReader.PoolObservation? = observation
        fun transactions(): List<ByteArray> = synchronized(lock) {
            checkNotNull(observation); history.transactions()
        }
        fun intentAccepted(digest: ByteArray, now: () -> Long): Boolean = synchronized(lock) {
            val verified = checkNotNull(observation)
            verified.requireFresh(now())
            val accepted = history.intentAccepted(digest)
            verified.requireFresh(now())
            accepted
        }

        fun validateRecipient(descriptor: ByteArray, now: () -> Long): Boolean = synchronized(lock) {
            val verified = checkNotNull(observation)
            verified.requireFresh(now())
            val valid = history.validateRecipient(descriptor)
            verified.requireFresh(now())
            valid
        }

        fun info(now: Long): ByteArray = synchronized(lock) {
            checkNotNull(observation).requireFresh(now); history.info()
        }
        fun scanAndWipeSeed(seed: ByteArray, offset: Int, count: Int, now: Long): ByteArray {
            try {
                return synchronized(lock) {
                    checkNotNull(observation).requireFresh(now)
                    history.scanAndWipeSeed(seed, offset, count)
                }
            } finally { seed.fill(0) }
        }
        /** Run on a worker (e.g. runInterruptible(Dispatchers.IO)). A fresh time is
         * obtained before and after native work; a saved timestamp is insufficient.
         * This is an observed full-ledger summary, not spend/restore authorization.
         */
        fun summaryAndWipeSeed(seed: ByteArray, now: () -> Long,
                              stopped: () -> Boolean = { Thread.currentThread().isInterrupted }): ByteArray {
            try {
                return synchronized(lock) {
                    history.summaryAndWipeSeed(seed, recheck = {
                        checkNotNull(observation).requireFresh(now())
                    }, stopped = stopped)
                }
            } finally { seed.fill(0) }
        }
        fun pendingStatusAndWipeSeed(seed: ByteArray, root: ByteArray, now: () -> Long,
                                     stopped: () -> Boolean) = synchronized(lock) {
            try {
                val verified = checkNotNull(observation)
                val binding = verified.receiveBinding(now())
                history.pendingStatusAndWipeSeed(seed, root, binding) {
                    if (stopped()) throw kotlinx.coroutines.CancellationException("Privacy status cancelled")
                    verified.requireFresh(now())
                }
            } finally { seed.fill(0) }
        }
        fun receiveAndWipeSeed(seed: ByteArray, root: ByteArray, now: () -> Long,
                               stopped: () -> Boolean): ByteArray {
            try {
                return synchronized(lock) {
                    val verified = checkNotNull(observation)
                    val binding = verified.receiveBinding(now())
                    history.receiveAndWipeSeed(seed, root, binding) {
                        if (stopped()) throw kotlinx.coroutines.CancellationException("Privacy receipt cancelled")
                        verified.requireFresh(now())
                    }
                }
            } finally { seed.fill(0) }
        }
        fun backupFileAndWipeSeed(seed: ByteArray, root: ByteArray, fd: Int, restore: Boolean,
                                  now: () -> Long, running: () -> Unit) = synchronized(lock) {
            try {
                val verified = checkNotNull(observation)
                val binding = verified.receiveBinding(now())
                history.backupFileAndWipeSeed(seed, root, binding, fd, restore) {
                    running()
                    verified.requireFresh(now())
                }
            } finally { seed.fill(0) }
        }
        fun depositValue(amount: ULong, now: () -> Long, running: () -> Unit): ULong = synchronized(lock) {
            val verified = checkNotNull(observation)
            history.depositValue(amount) {
                running()
                verified.requireFresh(now())
            }
        }

        fun selfDepositAndWipeSeed(seed: ByteArray, root: ByteArray, amount: ULong, attachedValue: ULong,
                                   queryId: ULong, now: () -> Long, running: () -> Unit): ByteArray =
            synchronized(lock) {
                try {
                    val verified = checkNotNull(observation)
                    val binding = verified.receiveBinding(now())
                    val requireCurrent = {
                        running()
                        verified.requireFresh(now())
                    }
                    check(history.depositValue(amount, requireCurrent) == attachedValue) {
                        "Privacy deposit quote changed"
                    }
                    history.selfDepositAndWipeSeed(seed, root, binding, amount, queryId, requireCurrent)
                } finally { seed.fill(0) }
            }

        fun loadPaymentProver(phase1: java.io.File, ceremony: java.io.File, now: () -> Long) =
            synchronized(lock) {
                checkNotNull(observation).requireFresh(now())
                history.loadPaymentProver(phase1, ceremony)
            }

        fun requestRelayOffer(request: ByteArray, now: () -> Long) = synchronized(lock) {
            val verified = checkNotNull(observation)
            verified.requireFresh(now())
            val offer = history.requestRelayOffer(request)
            try {
                verified.requireFresh(now())
                offer
            } catch (failure: Throwable) {
                offer.close()
                throw failure
            }
        }

        fun stageConfirmedPaymentAndWipeSeed(
            seed: ByteArray,
            root: java.io.File,
            recipient: ByteArray,
            amount: String,
            fee: String,
            quoteIdentity: String,
            prover: PrivacyNative.PaymentProver,
            offer: PrivacyNative.RelayOffer,
            now: () -> Long,
            withdrawalFee: String? = null,
        ): PrivacyNative.PaymentSubmission = try {
            synchronized(lock) {
                val verified = checkNotNull(observation)
                require(root.isAbsolute)
                val request = JSONObject()
                    .put("root", root.path)
                    .put("recipient", if (withdrawalFee == null) android.util.Base64.encodeToString(recipient, android.util.Base64.NO_WRAP) else recipient.decodeToString(throwOnInvalidSequence = true))
                    .put("amount", amount)
                    .put("fee", fee)
                    .put("quote_identity", quoteIdentity)
                    .put("proven_time", verified.provenShardTime(now()))
                    .apply { if (withdrawalFee != null) put("withdrawal_fee", withdrawalFee) }
                    .toString().encodeToByteArray()
                val payment = history.stagePaymentAndWipeSeed(prover, offer, seed, request)
                try {
                    verified.requireFresh(now())
                    payment
                } catch (failure: Throwable) {
                    payment.close()
                    throw failure
                }
            }
        } finally { seed.fill(0) }

        fun recoverPaymentAndWipeSeed(
            seed: ByteArray, request: ByteArray, now: () -> Long
        ): PrivacyNative.PaymentSubmission? = try {
            synchronized(lock) {
                val verified = checkNotNull(observation)
                verified.requireFresh(now())
                val payment = history.recoverPaymentAndWipeSeed(seed, request)
                try {
                    verified.requireFresh(now())
                    payment
                } catch (failure: Throwable) {
                    payment?.close()
                    throw failure
                }
            }
        } finally { seed.fill(0) }

        override fun close() = synchronized(lock) {
            try { history.close() } finally { observation = null; lease.close() }
        }
    }
    /** Capacity remains held until the completed native ledger/archive is closed. */
    fun synchronize(observation: QuantumAccountReader.PoolObservation, query: QuantumHistoryReader.Query,
                    stopped: () -> Boolean, now: () -> Long, lease: Lease = Lease(observation.id),
                    forReceive: Boolean = false): Completed {
        lease.requireHeld(observation.id)
        try {
            val raw = PrivacyHistoryWalk.Query { address, lt, hash ->
                val encoded = query.transaction(address, lt, hash)
                require(encoded.length <= 1_398_104)
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                require(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) == encoded)
                bytes
            }
            val history = PrivacyHistoryWalk.replay(observation.replayRequest(now(), forReceive), raw, stopped,
                recheck = { observation.requireFresh(now()) })
            try { return Completed(observation, history, lease) }
            catch (failure: Throwable) { history.close(); throw failure }
        } catch (failure: Throwable) { lease.close(); throw failure }
    }
}
