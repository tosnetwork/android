package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import org.ton.boc.BagOfCells

/** Unsigned same-address replacement only. Not a signer, fee approval, dispatch or install capability.
 * Constructed by the private authenticated funding result; service separately verifies saved backup. */
class QuantumPreparedSuccessorMigration internal constructor(
    entry: QuantumProbeFundingJournal.Reservation,
    prior: QuantumRecoveryControlJournal.Entry,
    probe: QuantumRecoveryProbeJournal.Entry,
    submitted: QuantumProbeFundingSubmission,
    private val observation: QuantumAccountReader.Observation,
    backupDigest: ByteArray, now: Long, ttl: Long,
) {
    val identity = submitted.identity
    val profile = submitted.profile
    val fundingOperation = submitted.operation
    val probeOperation = probe.operation
    val successor = probe.successor
    val priorAuthorizationDeadline = submitted.deadline
    internal val walletId = submitted.walletId
    private val fundingBytes = entry.encode()
    private val priorBytes = prior.encode()
    private val probeBytes = probe.encode()
    private val savedBackup = backupDigest.copyOf()
    private val material = probe.materialDigest.copyOf()
    private val stateHash = submitted.expectedState.cell.hash().toByteArray()
    private val stateBytes = BagOfCells(submitted.expectedState.cell).toByteArray()
    internal val boundStateBOC: ByteArray get() = stateBytes.copyOf()
    val confirmedBackupDigest: ByteArray get() = savedBackup.copyOf()
    val materialDigest: ByteArray get() = material.copyOf()
    val expiresAt: Long
    val request: TosQuantumSelfPayRequest
    val expectedState: QuantumSelfPayState

    init {
        require(backupDigest.size == 32 && now in 1..0xffffffffL && ttl in 1..3600)
        check(entry.phase == QuantumProbeFundingJournal.Phase.COMPLETED)
        require(entry.operation == submitted.operation && entry.walletId == walletId && probe.walletId == walletId &&
            prior.walletId == walletId && probe.identity == identity && prior.identity == identity)
        require(sameProfile(profile, probe.profile) && sameProfile(profile, prior.profile))
        require(successor.policy == QuantumPolicy.REQUIRED && submitted.expectedState.retired == 2)
        require(!successor.primaryKey.contentEquals(submitted.expectedState.authority.primaryKey) &&
            !successor.rescueKey.contentEquals(submitted.expectedState.authority.rescueKey))
        validateObservation(observation, now)
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = time + ttl
        require(expiresAt <= 0xffffffffL)
        val state = submitted.expectedState
        request = TosQuantumSelfPayRequest(identity.address, state, QuantumAuthRole.RESCUE, expiresAt,
            QuantumSelfPayAction.Migrate(successor), time)
        // MIGRATE resets counters and preserves retirement; it is allowed to recover exhausted
        // EXECUTE counters. The builder separately rejects a saturated epoch.
        expectedState = QuantumSelfPayState(successor, state.walletId, 0, state.retired, state.epoch + 1u, 0u, 0u)
    }

    private fun validateObservation(fresh: QuantumAccountReader.Observation, now: Long) {
        require(fresh.id == walletId && fresh.identity == identity && sameProfile(fresh.profile, profile))
        fresh.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(fresh.network, profile.globalId, now)
        fresh.network.requireSameCheckpoint(fresh.proof)
        val state = (QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Migration account no longer active")
        check(state.cell.hash().toByteArray().contentEquals(stateHash)) { "Migration funding state changed" }
        requireStrictExpiry(fresh.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)), priorAuthorizationDeadline)
    }

    fun requireFresh(now: Long) {
        validateObservation(observation, now)
        requireBeforeExpiry(observation, now)
    }

    internal fun requireRevalidated(fresh: QuantumAccountReader.Observation, now: Long) {
        requireFresh(now)
        validateObservation(fresh, now)
        requireBeforeExpiry(fresh, now)
    }

    private fun requireBeforeExpiry(fresh: QuantumAccountReader.Observation, now: Long) {
        val time = maxOf(now, fresh.network.masterchainTime,
            fresh.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        check(time in 1 until expiresAt) { "Migration preparation expired" }
    }

    internal fun requireRecords(entry: QuantumProbeFundingJournal.Reservation, prior: QuantumRecoveryControlJournal.Entry,
        probe: QuantumRecoveryProbeJournal.Entry, backupDigest: ByteArray) {
        check(fundingBytes.contentEquals(entry.encode()) && priorBytes.contentEquals(prior.encode()) &&
            probeBytes.contentEquals(probe.encode()) && savedBackup.contentEquals(backupDigest)) { "Migration recovery binding changed" }
    }

    companion object {
        internal fun requireStrictExpiry(provenShardTime: Long, authorizationDeadline: Long) {
            require(provenShardTime in 1..0xffffffffL && authorizationDeadline in 1..0xffffffffL)
            if (provenShardTime <= authorizationDeadline)
                throw QuantumPriorControlNotExpired(provenShardTime, authorizationDeadline)
        }
    }
}
