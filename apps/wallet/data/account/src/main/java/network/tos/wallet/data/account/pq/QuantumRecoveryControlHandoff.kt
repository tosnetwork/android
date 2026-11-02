package network.tos.wallet.data.account.pq

/** Waiting information only, never a release capability. */
class QuantumPriorControlNotExpired(val provenShardTime: Long, val authorizationDeadline: Long) :
    IllegalStateException("Prior recovery authorization is not proven strictly expired")

/** Private authenticated handoff. Completed records stay on disk; no unlink or payment unlock. */
internal class QuantumRecoveryControlHandoff private constructor(
    private val original: ByteArray,
    private val observation: QuantumAccountReader.Observation,
) {
    fun requireCurrent(prior: QuantumRecoveryControlJournal.Entry, now: Long) {
        check(original.contentEquals(prior.encode())) { "Prior recovery authorization changed" }
        validate(prior, observation, now)
    }
    companion object {
        private fun validate(prior: QuantumRecoveryControlJournal.Entry, fresh: QuantumAccountReader.Observation, now: Long) {
            check(prior.phase == QuantumRecoveryControlJournal.Phase.COMPLETED && prior.kind == QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY)
            require(prior.walletId == fresh.id && prior.identity == fresh.identity && sameProfile(prior.profile, fresh.profile))
            fresh.requireFresh(now)
            QuantumSelfPayNetwork.requireRecoveryBase(fresh.network, prior.profile.globalId, now)
            fresh.network.requireSameCheckpoint(fresh.proof)
            val current = (QuantumSelfPayAccount.bind(prior.identity, fresh.proof, now, 300).snapshot(now).status
                as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Recovery account no longer active")
            check(current.cell.hash() == prior.expectedControlState().cell.hash())
            val shardTime = fresh.proof.provenAccountTime(QuantumAccountReader.rawAddress(prior.identity))
            if (shardTime <= prior.deadline) throw QuantumPriorControlNotExpired(shardTime, prior.deadline)
        }
        fun acquire(prior: QuantumRecoveryControlJournal.Entry, fresh: QuantumAccountReader.Observation, now: Long): QuantumRecoveryControlHandoff {
            validate(prior, fresh, now)
            return QuantumRecoveryControlHandoff(prior.encode(), fresh)
        }
    }
}
