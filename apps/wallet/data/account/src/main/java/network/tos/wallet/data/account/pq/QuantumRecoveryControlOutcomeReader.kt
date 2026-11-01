package network.tos.wallet.data.account.pq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.ton.boc.BagOfCells
import java.util.UUID

/** Authenticated result; a private fresh result can record completion, never replacement, retry or recovery unlock. */
class QuantumRecoveryControlOutcomeReader(
    private val context: Context,
    private val history: QuantumHistoryReader,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Status { NotFoundInRecentHistory, IncludedWithoutSuccessfulControl, Executed }
    class Result private constructor(val operation: UUID, val status: Status, val transactionHash: String?, val feesNano: String?,
        val checkpointTime: Long, val olderHistoryAvailable: Boolean, val currentStateMatchesExpected: Boolean?,
        private val original: ByteArray, private val observation: QuantumAccountReader.Observation,
    ) {
        internal fun requireCompletion(entry: QuantumRecoveryControlJournal.Entry, now: Long) {
            check(status == Status.Executed && currentStateMatchesExpected == true)
            check(original.contentEquals(entry.encode())) { "Recovery operation changed since verification" }
            observation.requireFresh(now)
            check(observation.id == entry.walletId && observation.identity == entry.identity && sameProfile(observation.profile, entry.profile))
            val state = (QuantumSelfPayAccount.bind(entry.identity, observation.proof, now, 300).snapshot(now).status
                as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Recovery account no longer active")
            check(state.cell.hash() == entry.expectedControlState().cell.hash())
        }
        companion object {
            internal suspend fun read(reader: QuantumRecoveryControlOutcomeReader, id: UUID, identity: TosQuantumSelfPayIdentity): Result =
                reader.readVerified(id, identity) { entry, page, tx, status, matches ->
                    Result(entry.operation, status, tx?.hash, tx?.feesNano, page.checkpointTime, page.next != null,
                        matches, entry.encode(), page.observation)
                }
        }
    }

    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity): Result = Result.read(this, id, identity)

    private suspend fun readVerified(id: UUID, identity: TosQuantumSelfPayIdentity,
        result: (QuantumRecoveryControlJournal.Entry, QuantumHistoryReader.Page, QuantumHistoryTransaction?, Status, Boolean?) -> Result,
    ): Result = withContext(Dispatchers.IO) {
        val journal = QuantumRecoveryControlJournal(context)
        val entry = checkNotNull(journal.pending(identity))
        require(entry.walletId == id)
        check(entry.phase in setOf(QuantumRecoveryControlJournal.Phase.SUBMITTING, QuantumRecoveryControlJournal.Phase.UNKNOWN))
        val messageHash = QuantumPaymentQuote.hex(BagOfCells(entry.signedMessage()).roots.single().hash().toByteArray())
        val page = history.read(id, identity, limit = 20)
        page.observation.requireFresh(now())
        check(sameProfile(page.observation.profile, entry.profile))
        // A status read cannot silently switch to a different durable operation.
        check(checkNotNull(journal.pending(identity)).encode().contentEquals(entry.encode()))
        val tx = page.transactions.singleOrNull { it.inbound?.hash == messageHash }
        if (tx != null) check(tx.inbound?.kind == "external_in" && tx.inbound.destination == QuantumAccountReader.rawAddress(identity))
        val successful = tx != null && tx.successfulExecution && !tx.otherFeeCurrencies && tx.outbound.isEmpty() &&
            tx.skippedActions == 0L && (tx.messagesCreated == null || tx.messagesCreated == 0L)
        // This is the same fresh observation that authenticated the history head.
        // A later operation may change state without changing this transaction's execution result.
        val current = (QuantumSelfPayAccount.bind(identity, page.observation.proof, now(), 300).snapshot(now()).status
            as? QuantumSelfPayAccount.Status.Active)?.state
        result(entry, page, tx, when {
            tx == null -> Status.NotFoundInRecentHistory
            successful -> Status.Executed
            else -> Status.IncludedWithoutSuccessfulControl
        },
            if (tx == null) null else current?.cell?.hash() == entry.expectedControlState().cell.hash())
    }
}
