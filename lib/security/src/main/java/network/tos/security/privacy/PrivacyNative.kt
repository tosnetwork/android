package network.tos.security.privacy

import org.json.JSONObject

internal object PrivacyNativeBridge {
    init { System.loadLibrary("tos_mobile_shielded") }
    external fun descriptor(seed: ByteArray, domain: ByteArray, index: Long): ByteArray?
    external fun scan(seed: ByteArray, request: ByteArray): ByteArray?
    external fun poolState(boc: ByteArray): ByteArray?
    external fun historyTransaction(request: ByteArray): ByteArray?
    external fun createReplay(request: ByteArray): Long
    external fun pushReplay(handle: Long, boc: ByteArray): Int
    external fun finishReplay(handle: Long): Int
    external fun replayInfo(handle: Long): ByteArray?
    external fun scanReplay(handle: Long, seed: ByteArray, offset: Int, count: Int): ByteArray?
    external fun summaryReplay(handle: Long, seed: ByteArray): ByteArray?
    external fun replayIntentAccepted(handle: Long, digest: ByteArray): ByteArray?
    external fun validateRecipient(handle: Long, descriptor: ByteArray): Boolean
    external fun depositValue(handle: Long, amount: Long): ByteArray?
    external fun selfDeposit(handle: Long, seed: ByteArray, root: ByteArray, binding: ByteArray,
                             amount: Long, queryId: Long): ByteArray?
    external fun pendingStatus(handle: Long, seed: ByteArray, root: ByteArray, binding: ByteArray): ByteArray?
    external fun backupFile(handle: Long, seed: ByteArray, root: ByteArray, binding: ByteArray,
                            fd: Int, restore: Boolean): ByteArray?
    external fun receiveReplay(handle: Long, seed: ByteArray, root: ByteArray, binding: ByteArray): ByteArray?
    external fun requestRelayOffer(replay: Long, request: ByteArray): Long
    external fun relayOfferInfo(offer: Long): ByteArray?
    external fun freeRelayOffer(offer: Long)
    external fun stagePayment(replay: Long, prover: Long, offer: Long, seed: ByteArray, request: ByteArray): Long
    external fun recoverPayment(replay: Long, seed: ByteArray, request: ByteArray): Long
    external fun submitPayment(payment: Long): ByteArray?
    external fun freePayment(payment: Long)
    external fun loadPaymentProver(handle: Long, phase1: ByteArray, ceremony: ByteArray): Long
    external fun freePaymentProver(handle: Long)
    external fun freeReplay(handle: Long)
}

/** Synchronous wallet crypto only. Call off the UI thread with authenticated pool data.
 * Seed is the 32-byte TOS mnemonic master before role-specific Quantum derivation.
 * Neither a successful scan nor descriptor parsing verifies chain state.
 */
object PrivacyNative {
    private val lock = Any()
    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024

    /** The caller must durably reserve index before showing the resulting descriptor. */
    fun descriptorAndWipeSeed(seed: ByteArray, domain: ByteArray, index: ULong): ByteArray {
        try {
            require(seed.size == 32 && domain.size == 32)
            return synchronized(lock) {
                PrivacyNativeBridge.descriptor(seed, domain, index.toLong())
                    ?.also { check(it.size == 2573) }
                    ?: error("Privacy descriptor generation failed")
            }
        } finally { seed.fill(0) }
    }

    /** Layout decoding; expectedDataHash must originate in an authenticated account proof.
     * Address/code/network/profile checks remain caller responsibilities.
     */
    fun decodePoolState(boc: ByteArray, expectedDataHash: ByteArray): ByteArray {
        require(boc.isNotEmpty() && boc.size <= MAX_REQUEST_BYTES && expectedDataHash.size == 32)
        return synchronized(lock) {
            val decoded = PrivacyNativeBridge.poolState(boc) ?: error("Privacy pool state decode failed")
            check(decoded.size in 1..MAX_RESPONSE_BYTES)
            val expected = expectedDataHash.joinToString("") { "%02x".format(it.toInt() and 255) }
            check(JSONObject(String(decoded, Charsets.UTF_8)).getString("data_hash") == expected) {
                "Pool state does not match the account proof"
            }
            decoded
        }
    }

    /** Expectations come from a verified account or the checked newer history link.
     * A single link cannot establish complete history or a private balance.
     */
    fun decodeHistoryTransaction(boc: ByteArray, expectedHash: ByteArray, logicalTime: ULong,
                                 accountID: ByteArray, postStateHash: ByteArray, maximumTime: Long): ByteArray {
        require(boc.isNotEmpty() && boc.size <= MAX_REQUEST_BYTES && logicalTime > 0uL)
        require(listOf(expectedHash, accountID, postStateHash).all { it.size == 32 })
        require(maximumTime in 0..0xffffffffL)
        fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }
        val request = JSONObject().put("version", 1)
            .put("boc", android.util.Base64.encodeToString(boc, android.util.Base64.NO_WRAP))
            .put("expected_hash", hex(expectedHash)).put("expected_lt", logicalTime.toString())
            .put("account_id", hex(accountID)).put("post_state_hash", hex(postStateHash))
            .put("maximum_time", maximumTime.toString()).toString().toByteArray(Charsets.UTF_8)
        require(request.size <= MAX_REQUEST_BYTES)
        return synchronized(lock) {
            val decoded = PrivacyNativeBridge.historyTransaction(request) ?: error("Privacy history binding failed")
            check(decoded.size in 1..MAX_RESPONSE_BYTES)
            decoded
        }
    }

    class PaymentProver internal constructor(internal var handle: Long) : AutoCloseable {
        override fun close() = synchronized(lock) {
            if (handle != 0L) {
                PrivacyNativeBridge.freePaymentProver(handle)
                handle = 0L
            }
        }
    }

    data class RelayOfferDetails(val identity: String, val fee: String, val validUntil: String)

    class RelayOffer internal constructor(internal var handle: Long) : AutoCloseable {
        fun details(): RelayOfferDetails = synchronized(lock) {
            val bytes = checkNotNull(PrivacyNativeBridge.relayOfferInfo(handle))
            val offer = org.json.JSONObject(bytes.decodeToString())
            RelayOfferDetails(offer.getString("identity"), offer.getString("fee"), offer.getString("valid_until"))
        }
        override fun close() = synchronized(lock) {
            PrivacyNativeBridge.freeRelayOffer(handle)
            handle = 0L
        }
    }

    class PaymentSubmission internal constructor(private var handle: Long) : AutoCloseable {
        fun submitOrRetry(): ByteArray = synchronized(lock) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            checkNotNull(PrivacyNativeBridge.submitPayment(handle))
        }
        override fun close() = synchronized(lock) {
            PrivacyNativeBridge.freePayment(handle)
            handle = 0L
        }
    }

    /** Own with use/close, including cancellation. Creation request must be proof-bound. */
    class Replay internal constructor(private var handle: Long) : AutoCloseable {
        init { require(handle != 0L) }
        override fun close() = synchronized(lock) { discard() }
        private fun discard() {
            if (handle != 0L) { PrivacyNativeBridge.freeReplay(handle); handle = 0L }
        }
        fun push(boc: ByteArray) = synchronized(lock) {
            check(handle != 0L)
            if (boc.isEmpty() || boc.size > MAX_REQUEST_BYTES) { discard(); error("Invalid privacy history input") }
            try { check(PrivacyNativeBridge.pushReplay(handle, boc) == 0) { "Privacy history replay failed" } }
            catch (failure: Throwable) { discard(); throw failure }
        }
        fun finish() = synchronized(lock) {
            check(handle != 0L)
            try { check(PrivacyNativeBridge.finishReplay(handle) == 0) { "Privacy history is incomplete" } }
            catch (failure: Throwable) { discard(); throw failure }
        }
        fun intentAccepted(digest: ByteArray): Boolean = synchronized(lock) {
            check(handle != 0L) { "Privacy replay is closed" }
            when (PrivacyNativeBridge.replayIntentAccepted(handle, digest)?.singleOrNull()?.toInt()) {
                0 -> false
                1 -> true
                else -> error("Privacy intent observation failed")
            }
        }

        fun loadPaymentProver(phase1: java.io.File, ceremony: java.io.File): PaymentProver {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            require(phase1.isAbsolute && ceremony.isAbsolute)
            return synchronized(lock) {
                check(handle != 0L) { "Privacy replay is closed" }
                val prover = PrivacyNativeBridge.loadPaymentProver(
                    handle, phase1.path.encodeToByteArray(), ceremony.path.encodeToByteArray()
                )
                check(prover != 0L) { "Privacy payment prover unavailable" }
                try { PaymentProver(prover) }
                catch (failure: Throwable) { PrivacyNativeBridge.freePaymentProver(prover); throw failure }
            }
        }

        fun requestRelayOffer(request: ByteArray): RelayOffer = synchronized(lock) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            val offer = PrivacyNativeBridge.requestRelayOffer(handle, request)
            check(offer != 0L)
            RelayOffer(offer)
        }

        fun stagePaymentAndWipeSeed(
            prover: PaymentProver, offer: RelayOffer, seed: ByteArray, request: ByteArray
        ): PaymentSubmission = try {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            synchronized(lock) {
                check(handle != 0L && prover.handle != 0L && offer.handle != 0L)
                val quote = offer.handle
                offer.handle = 0L
                val payment = PrivacyNativeBridge.stagePayment(handle, prover.handle, quote, seed, request)
                check(payment != 0L)
                PaymentSubmission(payment)
            }
        } finally { seed.fill(0) }

        fun recoverPaymentAndWipeSeed(seed: ByteArray, request: ByteArray): PaymentSubmission? = try {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            synchronized(lock) {
                val payment = PrivacyNativeBridge.recoverPayment(handle, seed, request)
                check(payment != -1L)
                if (payment == 0L) null else PaymentSubmission(payment)
            }
        } finally { seed.fill(0) }

        fun validateRecipient(descriptor: ByteArray): Boolean = synchronized(lock) {
            check(handle != 0L) { "Privacy replay is closed" }
            PrivacyNativeBridge.validateRecipient(handle, descriptor)
        }

        fun info(): ByteArray = synchronized(lock) {
            check(handle != 0L)
            val bytes = PrivacyNativeBridge.replayInfo(handle) ?: error("Privacy replay is not complete")
            check(bytes.size in 1..MAX_RESPONSE_BYTES); bytes
        }
        /** Synchronous worker-only complete Ready-ledger summary, never a batch total.
         * The result does not prove freshness or account for durable off-chain reservations.
         * Ownership of seed transfers to this call, including failure/closed/cancelled paths.
         */
        fun summaryAndWipeSeed(seed: ByteArray): ByteArray = synchronized(lock) {
            PrivacySummaryRead.readAndWipeSeed(seed, checkCurrent = {
                check(handle != 0L) { "Privacy replay is closed" }
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "Privacy summary must run on a worker thread"
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Privacy summary cancelled")
            }) {
                PrivacyNativeBridge.summaryReplay(handle, seed) ?: error("Privacy complete summary failed")
            }
        }

        /** Reserve and issue on a worker after authenticating this replay and its binding.
         * A cancellation after the native call may consume the reserved index.
         */
        fun receiveAndWipeSeed(seed: ByteArray, root: ByteArray, binding: ByteArray,
                               requireCurrent: () -> Unit): ByteArray =
            walletReplayAndWipeSeed(seed, root, binding, requireCurrent, PrivacyNativeBridge::receiveReplay)

        fun depositValue(amount: ULong, requireCurrent: () -> Unit): ULong = synchronized(lock) {
            check(handle != 0L && android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            requireCurrent()
            val value = PrivacyNativeBridge.depositValue(handle, amount.toLong())
                ?.decodeToString()?.toULongOrNull() ?: error("Privacy deposit quote failed")
            requireCurrent()
            check(value >= amount)
            value
        }

        fun selfDepositAndWipeSeed(seed: ByteArray, root: ByteArray, binding: ByteArray,
                                   amount: ULong, queryId: ULong, requireCurrent: () -> Unit): ByteArray =
            walletReplayAndWipeSeed(seed, root, binding, requireCurrent) { replay, key, path, pins ->
                PrivacyNativeBridge.selfDeposit(replay, key, path, pins, amount.toLong(), queryId.toLong())
            }

        enum class PendingStatus { NO_RECORD, UNOBSERVED, ACCEPTED }

        fun backupFileAndWipeSeed(seed: ByteArray, root: ByteArray, binding: ByteArray, fd: Int,
                                  restore: Boolean, requireCurrent: () -> Unit) {
            val result = walletReplayAndWipeSeed(seed, root, binding, requireCurrent) { replay, key, path, pins ->
                PrivacyNativeBridge.backupFile(replay, key, path, pins, fd, restore)
            }
            check(result.singleOrNull()?.toInt() == 1)
        }

        fun pendingStatusAndWipeSeed(seed: ByteArray, root: ByteArray, binding: ByteArray,
                                     requireCurrent: () -> Unit): PendingStatus {
            val result = walletReplayAndWipeSeed(seed, root, binding, requireCurrent, PrivacyNativeBridge::pendingStatus)
            return when (result.singleOrNull()?.toInt()) {
                0 -> PendingStatus.NO_RECORD
                1 -> PendingStatus.UNOBSERVED
                2 -> PendingStatus.ACCEPTED
                else -> error("Invalid privacy payment status")
            }
        }

        private fun walletReplayAndWipeSeed(
            seed: ByteArray,
            root: ByteArray,
            binding: ByteArray,
            requireCurrent: () -> Unit,
            operation: (Long, ByteArray, ByteArray, ByteArray) -> ByteArray?,
        ): ByteArray = synchronized(lock) {
            PrivacySummaryRead.readAndWipeSeed(seed, checkCurrent = {
                check(handle != 0L) { "Privacy replay is closed" }
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "Privacy wallet replay must run on a worker thread"
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Privacy wallet replay cancelled")
                requireCurrent()
            }) {
                require(root.size in 1..4096 && binding.size == 228)
                operation(handle, seed, root, binding) ?: error("Privacy wallet replay failed")
            }
        }

        fun scanAndWipeSeed(seed: ByteArray, offset: Int, count: Int): ByteArray {
            try {
                require(seed.size == 32 && offset >= 0 && count in 1..512)
                return synchronized(lock) {
                    check(handle != 0L)
                    val bytes = PrivacyNativeBridge.scanReplay(handle, seed, offset, count) ?: error("Privacy ledger scan failed")
                    check(bytes.size in 1..MAX_RESPONSE_BYTES); bytes
                }
            } finally { seed.fill(0) }
        }
    }
    fun replay(request: ByteArray): Replay {
        require(request.isNotEmpty() && request.size <= MAX_REQUEST_BYTES)
        return synchronized(lock) {
            val handle = PrivacyNativeBridge.createReplay(request)
            check(handle != 0L) { "Privacy replay creation failed" }
            try { Replay(handle) }
            catch (failure: Throwable) { PrivacyNativeBridge.freeReplay(handle); throw failure }
        }
    }

    /** The response contains private note material; encrypt retained state and never log it. */
    fun scanAndWipeSeed(seed: ByteArray, request: ByteArray): ByteArray {
        try {
            require(seed.size == 32 && request.isNotEmpty() && request.size <= MAX_REQUEST_BYTES)
            return synchronized(lock) {
                PrivacyNativeBridge.scan(seed, request)
                    ?.also { check(it.size in 1..MAX_RESPONSE_BYTES) }
                    ?: error("Privacy note scan failed")
            }
        } finally { seed.fill(0) }
    }
}
