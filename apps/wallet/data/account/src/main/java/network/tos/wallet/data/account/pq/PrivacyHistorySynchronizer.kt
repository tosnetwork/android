package network.tos.wallet.data.account.pq

import network.tos.security.privacy.PrivacyHistoryWalk
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
