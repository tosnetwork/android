package network.tos.wallet.data.account.pq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import java.util.Collections
import java.util.UUID

/** Fresh proof-anchored history. At most two reads globally, one per wallet,
 * twenty transactions per page and sixty seconds per walk; busy never queues.
 */
class QuantumHistoryReader(
    private val accounts: QuantumAccountReader,
    private val query: Query,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun interface Query { fun transaction(address: String, logicalTime: String, hash: String): String }
    class Busy : IllegalStateException("History reader busy")
    class Cursor internal constructor(
        internal val observation: QuantumAccountReader.Observation,
        internal val logicalTime: String,
        internal val hash: String,
    )
    data class Page(val transactions: List<QuantumHistoryTransaction>, val next: Cursor?, val checkpointTime: Long, val balanceNano: String)

    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity, cursor: Cursor? = null, limit: Int = 20): Page {
        require(limit in 1..20)
        if (cursor != null) require(cursor.observation.id == id && cursor.observation.identity == identity)
        return runInterruptible(Dispatchers.IO) {
            enter(id)
            try {
                val deadline = System.nanoTime() + 60_000_000_000L
                fun requireRunning() {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("History read cancelled")
                    check(System.nanoTime() - deadline < 0) { "History read expired" }
                }
                requireRunning()
                val observation = cursor?.observation ?: accounts.observe(id, identity)
                observation.requireFresh(now())
                val address = QuantumAccountReader.rawAddress(identity)
                val head = observation.proof.provenTransactionHead(address)
                var time = cursor?.logicalTime ?: head.logicalTime
                var hash = cursor?.hash ?: head.hash
                require((time == "0") == (hash == "00".repeat(32)))
                val transactions = ArrayList<QuantumHistoryTransaction>(limit)
                val shardTime = observation.proof.provenAccountTime(address)
                while (time != "0" && transactions.size < limit) {
                    requireRunning()
                    val transaction = QuantumHistoryTransaction.verify(address, time, hash, query.transaction(address, time, hash))
                    require(transaction.timestamp <= shardTime &&
                        (transactions.lastOrNull()?.let { transaction.timestamp <= it.timestamp } ?: true))
                    transactions.add(transaction)
                    time = transaction.previousLogicalTime; hash = transaction.previousHash
                }
                requireRunning()
                observation.requireFresh(now())
                Page(Collections.unmodifiableList(transactions), if (time == "0") null else Cursor(observation, time, hash),
                    observation.network.masterchainTime, observation.snapshot.balance.toString())
            } finally { leave(id) }
        }
    }
    private companion object {
        val active = HashSet<UUID>()
        fun enter(id: UUID) = synchronized(active) {
            if (active.size >= 2 || id in active) throw Busy()
            active.add(id)
        }
        fun leave(id: UUID) = synchronized(active) { active.remove(id) }
    }
}
