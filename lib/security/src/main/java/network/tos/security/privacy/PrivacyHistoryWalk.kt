package network.tos.security.privacy

import org.json.JSONObject

/** Raw bounded history orchestration. Caller expectations must originate in one proof observation. */
object PrivacyHistoryWalk {
    const val MAX_TRANSACTIONS = 8192
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private const val MAX_TRANSACTION_BYTES = 1024 * 1024
    fun interface Query { fun transaction(address: String, logicalTime: String, hash: String): ByteArray }
    class Completed internal constructor(private val replay: PrivacyNative.Replay, history: List<ByteArray>) : AutoCloseable {
        private val lock = Any()
        private var archive: List<ByteArray>? = history.toList()
        fun transactions(): List<ByteArray> = synchronized(lock) {
            val archive = checkNotNull(this.archive) { "Privacy history archive is closed" }
            archive.map { it.copyOf() }
        }
        fun info(): ByteArray = replay.info()
        fun scanAndWipeSeed(seed: ByteArray, offset: Int, count: Int): ByteArray = replay.scanAndWipeSeed(seed, offset, count)
        /** Synchronous worker call. Rechecks cancellation and the caller's proof on BOTH sides
         * of complete native summarization; no old batch-scan fallback or partial sum exists.
         */
        fun summaryAndWipeSeed(seed: ByteArray, recheck: () -> Unit,
                              stopped: () -> Boolean = { Thread.currentThread().isInterrupted }): ByteArray = synchronized(lock) {
            PrivacySummaryRead.readAndWipeSeed(seed, checkCurrent = {
                checkNotNull(archive) { "Privacy history archive is closed" }
                check(!stopped()) { "Privacy summary cancelled" }
                recheck()
            }) { replay.summaryAndWipeSeed(seed) }
        }
        override fun close() = synchronized(lock) { replay.close(); archive = null }
    }
    /** Run on a worker. Query enforces reception/transport timeout and cancellation limits. */
    fun replay(request: ByteArray, query: Query, stopped: () -> Boolean = { Thread.currentThread().isInterrupted },
               recheck: () -> Unit, nanoTime: () -> Long = System::nanoTime): Completed {
        val started = nanoTime()
        fun running() { check(!stopped() && nanoTime() - started < 600_000_000_000L) { "Privacy sync cancelled or expired" } }
        running(); recheck()
        val replay = PrivacyNative.replay(request)
        var succeeded = false
        try {
            val value = JSONObject(String(request, Charsets.UTF_8))
            val accountID = value.getString("account_id")
            val account = hex(accountID)
            var time = value.getString("last_lt")
            var hash = value.getString("last_hash")
            var state = hex(value.getString("account_root"))
            var maximum = value.getString("shard_time").toLong()
            val newestFirst = ArrayList<ByteArray>()
            var total = 0
            while (time != "0") {
                running(); check(newestFirst.size < MAX_TRANSACTIONS) { "Privacy history exceeds sync budget" }
                val received = query.transaction("0:$accountID", time, hash)
                running()
                check(received.size in 1..MAX_TRANSACTION_BYTES && received.size <= MAX_ARCHIVE_BYTES - total)
                val boc = received.copyOf() // A fetcher cannot mutate a checked/staged transaction afterwards.
                val decoded = JSONObject(String(PrivacyNative.decodeHistoryTransaction(boc, hex(hash), time.toULong(), account, state, maximum), Charsets.UTF_8))
                newestFirst.add(boc); total += boc.size
                time = decoded.getString("previous_lt"); hash = decoded.getString("previous_hash")
                state = hex(decoded.getString("pre_state_hash")); maximum = decoded.getString("time").toLong()
            }
            check(hash == "00".repeat(32))
            val history = newestFirst.asReversed()
            for (boc in history) { running(); replay.push(boc) }
            running(); replay.finish(); recheck(); running()
            val completed = Completed(replay, history)
            succeeded = true
            return completed
        } finally { if (!succeeded) replay.close() }
    }
    private fun hex(value: String): ByteArray {
        require(value.matches(Regex("[0-9a-f]{64}")))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
