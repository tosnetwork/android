package network.tos.wallet.data.account.pq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import java.util.UUID

/** Same-checkpoint execution and full-state evidence. Never retries, installs keys or unlocks payment. */
class QuantumMigrationOutcomeReader(
    private val context: Context, private val history: QuantumHistoryReader,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Status { NotFoundInRecentHistory, IncludedWithoutSuccessfulMigration, Executed }

    class Result private constructor(
        val operation: UUID, val status: Status, val transactionHash: String?, val feesNano: String?,
        val checkpointTime: Long, val olderHistoryAvailable: Boolean, val currentStateMatchesExpected: Boolean?,
        private val signingBytes: ByteArray, private val fundingBytes: ByteArray,
        private val priorBytes: ByteArray, private val probeBytes: ByteArray,
        private val observation: QuantumAccountReader.Observation,
    ) {
        /** Fresh evidence only; the caller still needs a separate durable completion/install protocol. */
        internal fun requireCurrent(record: QuantumMigrationSigningRecord, funding: QuantumProbeFundingJournal.Reservation,
            prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry, now: Long) {
            check(status == Status.Executed && currentStateMatchesExpected == true)
            check(signingBytes.contentEquals(record.encode()) && fundingBytes.contentEquals(funding.encode()) &&
                priorBytes.contentEquals(prior.encode()) && probeBytes.contentEquals(probe.encode())) {
                "Migration records changed since verification"
            }
            val submitted = QuantumMigrationSigningSnapshot.decode(record.binding).submitted(funding, prior, probe, record)
            require(submitted.operation == operation && submitted.walletId == observation.id &&
                submitted.identity == observation.identity && sameProfile(submitted.profile, observation.profile))
            observation.requireFresh(now)
            QuantumSelfPayNetwork.requireRecoveryBase(observation.network, submitted.profile.globalId, now)
            observation.network.requireSameCheckpoint(observation.proof)
            val state = (QuantumSelfPayAccount.bind(submitted.identity, observation.proof, now, 300).snapshot(now).status
                as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Migration account no longer active")
            check(QuantumMigrationStateMatch.matches(state, submitted.expectedState)) { "Migration final state changed" }
        }

        companion object {
            internal suspend fun read(reader: QuantumMigrationOutcomeReader, id: UUID, identity: TosQuantumSelfPayIdentity): Result =
                withContext(Dispatchers.IO) {
                    check(QuantumPaymentOutbox(reader.context).requiresRecovery(identity))
                    val key = identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
                    val store = QuantumMigrationSigningStore(QuantumMigrationSigningAndroidIO(reader.context, key))
                    val record = checkNotNull(store.load())
                    val funding = checkNotNull(QuantumProbeFundingJournal(reader.context).pending(identity))
                    val prior = checkNotNull(QuantumRecoveryControlJournal(reader.context).pending(identity))
                    val probe = checkNotNull(QuantumRecoveryProbeJournal(reader.context).pending(identity))
                    val submitted = QuantumMigrationSigningSnapshot.decode(record.binding).submitted(funding, prior, probe, record)
                    require(submitted.walletId == id && submitted.identity == identity)
                    val page = reader.history.readForRecovery(id, identity, limit = 20)
                    val now = reader.now()
                    page.observation.requireFresh(now)
                    require(sameProfile(page.observation.profile, submitted.profile))
                    page.observation.network.requireSameCheckpoint(page.observation.proof)
                    val tx = page.transactions.singleOrNull { it.inbound?.hash == submitted.messageHash }
                    if (tx != null) check(tx.inbound?.kind == "external_in" &&
                        tx.inbound.destination == QuantumAccountReader.rawAddress(identity))
                    val executed = tx != null && tx.successfulExecution && !tx.otherFeeCurrencies && tx.outbound.isEmpty() &&
                        tx.skippedActions == 0L && (tx.messagesCreated == null || tx.messagesCreated == 0L)
                    val current = (QuantumSelfPayAccount.bind(identity, page.observation.proof, now, 300).snapshot(now).status
                        as? QuantumSelfPayAccount.Status.Active)?.state
                    check(checkNotNull(store.load()).encode().contentEquals(record.encode()))
                    check(checkNotNull(QuantumProbeFundingJournal(reader.context).pending(identity)).encode().contentEquals(funding.encode()))
                    check(checkNotNull(QuantumRecoveryControlJournal(reader.context).pending(identity)).encode().contentEquals(prior.encode()))
                    check(checkNotNull(QuantumRecoveryProbeJournal(reader.context).pending(identity)).encode().contentEquals(probe.encode()))
                    check(QuantumPaymentOutbox(reader.context).requiresRecovery(identity))
                    Result(record.operation, when {
                        tx == null -> Status.NotFoundInRecentHistory
                        executed -> Status.Executed
                        else -> Status.IncludedWithoutSuccessfulMigration
                    }, tx?.hash, tx?.feesNano, page.checkpointTime, page.next != null,
                        if (tx == null) null else QuantumMigrationStateMatch.matches(current, submitted.expectedState),
                        record.encode(), funding.encode(), prior.encode(), probe.encode(), page.observation)
                }
        }
    }

    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity): Result = Result.read(this, id, identity)
}
