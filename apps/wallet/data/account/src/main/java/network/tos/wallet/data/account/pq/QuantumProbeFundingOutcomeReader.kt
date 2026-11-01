package network.tos.wallet.data.account.pq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.TosQuantumAccountState
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumVerifiedRead
import java.util.UUID

/** Same-checkpoint historical funding and possession verification. Never retries or unlocks recovery. */
class QuantumProbeFundingOutcomeReader(
    private val context: Context,
    private val history: QuantumHistoryReader,
    private val transport: QuantumProofTransport,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    enum class Status { NotFoundInRecentHistory, IncludedWithoutSuccessfulFunding, FundedWithoutVerifiedProbe, Verified }
    class Result private constructor(
        val operation: UUID, val status: Status, val transactionHash: String?, val feesNano: String?,
        val checkpointTime: Long, val olderHistoryAvailable: Boolean, val currentStateMatchesExpected: Boolean?,
        private val fundingBytes: ByteArray, private val priorBytes: ByteArray, private val probeBytes: ByteArray,
        private val observation: QuantumAccountReader.Observation, private val proof: QuantumVerifiedRead,
    ) {
        internal fun requireCompletion(entry: QuantumProbeFundingJournal.Reservation,
                                       prior: QuantumRecoveryControlJournal.Entry,
                                       probe: QuantumRecoveryProbeJournal.Entry, now: Long) {
            check(status == Status.Verified && currentStateMatchesExpected == true)
            check(fundingBytes.contentEquals(entry.encode()) && priorBytes.contentEquals(prior.encode()) &&
                probeBytes.contentEquals(probe.encode())) { "Recovery funding records changed since verification" }
            val submitted = entry.submission(prior, probe)
            require(submitted.operation == operation && submitted.walletId == observation.id &&
                submitted.identity == observation.identity && sameProfile(submitted.profile, observation.profile))
            validate(submitted, probe, observation, proof, now)
        }
        internal fun requireRecordedFunding(entry: QuantumProbeFundingJournal.Reservation,
                                           prior: QuantumRecoveryControlJournal.Entry,
                                           probe: QuantumRecoveryProbeJournal.Entry, now: Long):
            network.tos.blockchain.ton.contract.QuantumSelfPayState {
            check(entry.phase == QuantumProbeFundingJournal.Phase.COMPLETED)
            requireCompletion(entry, prior, probe, now)
            val submitted = entry.submission(prior, probe)
            entry.requireRecordedOutcome(checkNotNull(transactionHash), submitted.expectedState.cell.hash().toByteArray(),
                checkpointTime, checkNotNull(feesNano))
            return submitted.expectedState
        }
        /** Only this private authenticated result can supply the proven wallet time for handoff. */
        internal fun prepareMigration(entry: QuantumProbeFundingJournal.Reservation,
            prior: QuantumRecoveryControlJournal.Entry, probe: QuantumRecoveryProbeJournal.Entry,
            confirmedBackupDigest: ByteArray, now: Long, ttl: Long, retainedDeadline: Long? = null,
        ): QuantumPreparedSuccessorMigration {
            requireRecordedFunding(entry, prior, probe, now)
            return QuantumPreparedSuccessorMigration(entry, prior, probe, entry.submission(prior, probe),
                observation, confirmedBackupDigest, now, ttl, retainedDeadline)
        }
        companion object {
            internal suspend fun read(reader: QuantumProbeFundingOutcomeReader, id: UUID,
                                      identity: TosQuantumSelfPayIdentity, operation: UUID): Result {
                val journal = QuantumProbeFundingJournal(reader.context)
                val entry = checkNotNull(journal.pending(identity)); check(entry.operation == operation)
                val prior = checkNotNull(QuantumRecoveryControlJournal(reader.context).pending(identity))
                val probe = checkNotNull(QuantumRecoveryProbeJournal(reader.context).pending(identity))
                val submitted = entry.submission(prior, probe)
                require(submitted.walletId == id && submitted.identity == identity)
                check(QuantumPaymentOutbox(reader.context).requiresRecovery(identity))
                val page = reader.history.readForRecovery(id, identity, limit = 20)
                return runInterruptible(Dispatchers.IO) {
                    val observed = page.observation
                    observed.requireFresh(reader.now())
                    require(sameProfile(observed.profile, submitted.profile))
                    val proof = QuantumProofSession(reader.context, id, submitted.profile.anchor()).readBound(
                        observed.network.requestAtCheckpoint(submitted.probeAddress, maximumAge = 300), reader.now(), reader.transport)
                    observed.network.requireSameCheckpoint(proof)
                    val tx = page.transactions.singleOrNull { it.inbound?.hash == submitted.messageHash }
                    val funded = tx != null && tx.successfulExecution && !tx.otherFeeCurrencies &&
                        tx.skippedActions == 0L && tx.messagesCreated == 1L &&
                        tx.inbound?.kind == "external_in" && tx.inbound.destination == QuantumAccountReader.rawAddress(identity) &&
                        tx.outbound.size == 1 && tx.outbound.single().let {
                            it.kind == "internal" && it.source == QuantumAccountReader.rawAddress(identity) &&
                                it.destination == submitted.probeAddress && it.amountNano == submitted.amount.toString() &&
                                it.bodyHash == submitted.probeBodyHash && !it.otherCurrencies && !it.bounced && it.bounce == false
                        }
                    val current = (QuantumSelfPayAccount.bind(identity, observed.proof, reader.now(), 300)
                        .snapshot(reader.now()).status as? QuantumSelfPayAccount.Status.Active)?.state
                    val matches = current?.cell?.hash() == submitted.expectedState.cell.hash()
                    // Missing/mismatched evidence is not success; network/parser failures propagate.
                    val verified = probeVerified(submitted, probe, observed, proof, reader.now())
                    check(checkNotNull(journal.pending(identity)).encode().contentEquals(entry.encode()))
                    check(checkNotNull(QuantumRecoveryControlJournal(reader.context).pending(identity)).encode().contentEquals(prior.encode()))
                    check(checkNotNull(QuantumRecoveryProbeJournal(reader.context).pending(identity)).encode().contentEquals(probe.encode()))
                    check(QuantumPaymentOutbox(reader.context).requiresRecovery(identity))
                    Result(operation, when {
                        tx == null -> Status.NotFoundInRecentHistory
                        !funded -> Status.IncludedWithoutSuccessfulFunding
                        !verified -> Status.FundedWithoutVerifiedProbe
                        else -> Status.Verified
                    }, tx?.hash, tx?.feesNano, page.checkpointTime, page.next != null,
                        if (tx == null) null else matches, entry.encode(), prior.encode(), probe.encode(), observed, proof)
                }
            }
        }
    }
    suspend fun read(id: UUID, identity: TosQuantumSelfPayIdentity, operation: UUID): Result =
        Result.read(this, id, identity, operation)

    private companion object {
        fun probeVerified(submitted: QuantumProbeFundingSubmission, probe: QuantumRecoveryProbeJournal.Entry,
                          observation: QuantumAccountReader.Observation, proof: QuantumVerifiedRead, now: Long): Boolean {
            observation.requireFresh(now); proof.requireLive(now, 300)
            observation.network.requireSameCheckpoint(proof)
            QuantumSelfPayAccount.validateTime(proof.masterchainTime, proof.provenAccountTime(submitted.probeAddress), now, 300)
            if (proof.provenAccountState(submitted.probeAddress) == null || !proof.provenAccountIsActive(submitted.probeAddress)) return false
            val active = TosQuantumAccountState.snapshot(proof.accountState(submitted.probeAddress,
                QuantumPaymentQuote.hex(probe.probe.code.hash().toByteArray())), probe.probe.address, probe.probe.code)
            return probe.probe.isVerified(probe.probe.address, probe.probe.code, active.data)
        }
        fun validate(submitted: QuantumProbeFundingSubmission, probe: QuantumRecoveryProbeJournal.Entry,
                     observation: QuantumAccountReader.Observation, proof: QuantumVerifiedRead, now: Long) {
            check(probeVerified(submitted, probe, observation, proof, now))
            val current = (QuantumSelfPayAccount.bind(submitted.identity, observation.proof, now, 300)
                .snapshot(now).status as? QuantumSelfPayAccount.Status.Active)?.state
            check(current?.cell?.hash() == submitted.expectedState.cell.hash())
        }
    }
}
