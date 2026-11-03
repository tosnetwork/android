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
