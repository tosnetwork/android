package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumAuthRole
import network.tos.blockchain.ton.contract.QuantumSelfPayAction
import network.tos.blockchain.ton.contract.TosQuantumSelfPayRequest
import org.ton.boc.BagOfCells

/** Unsigned recovery-only control. Never permits merchant actions or clearing isolation.
 * Authentication, durable signing journal, bounded fees and final-state verification are
 * separate mandatory stages before this can become a product operation. */
class QuantumPreparedRecoveryControl internal constructor(
    private val observation: QuantumAccountReader.Observation,
    val kind: Kind,
    now: Long,
    ttl: Long,
) {
    enum class Kind { LOCK_PRIMARY, REQUIRE_RESCUE }
    val identity = observation.identity
    val profile = observation.profile
    internal val walletId = observation.id
    val expiresAt: Long
    val request: TosQuantumSelfPayRequest
    private val originalState: ByteArray
    private val stateBytes: ByteArray
    internal val boundStateBOC: ByteArray get() = stateBytes.copyOf()
    private val rescueKey: ByteArray
    val signingPublicKey: ByteArray get() = rescueKey.copyOf()

    init {
        require(now in 1..0xffffffffL && ttl in 1..3600)
        observation.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(observation.network, profile.globalId, now)
        observation.network.requireSameCheckpoint(observation.proof)
        // Rebind from native proof, rather than trusting a caller-provided Snapshot.
        val snapshot = QuantumSelfPayAccount.bind(identity, observation.proof, now, 300).snapshot(now)
        val state = (snapshot.status as? QuantumSelfPayAccount.Status.Active)?.state
            ?: error("Recovery control requires an active wallet")
        originalState = state.cell.hash().toByteArray()
        stateBytes = BagOfCells(state.cell).toByteArray()
        rescueKey = state.authority.rescueKey
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = time + ttl
        require(expiresAt <= 0xffffffffL)
        request = TosQuantumSelfPayRequest(identity.address, state, QuantumAuthRole.RESCUE, expiresAt,
            when (kind) {
                Kind.LOCK_PRIMARY -> QuantumSelfPayAction.LockPrimary
                Kind.REQUIRE_RESCUE -> QuantumSelfPayAction.ConfigureRequired
            }, time)
    }

    fun requireFresh(now: Long) {
        observation.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(observation.network, profile.globalId, now)
        observation.network.requireSameCheckpoint(observation.proof)
        check(now in 1 until expiresAt) { "Recovery control expired" }
    }

    /** A new proof may advance its checkpoint, but not the identity, authority or counters.
     * A boolean UI approval cannot replace this check. No signing capability is returned. */
    internal fun requireRevalidated(fresh: QuantumAccountReader.Observation, now: Long) {
        requireFresh(now)
        require(fresh.id == walletId && fresh.identity == identity && sameProfile(fresh.profile, profile))
        fresh.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(fresh.network, profile.globalId, now)
        fresh.network.requireSameCheckpoint(fresh.proof)
        val state = (QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Recovery control account changed")
        check(state.cell.hash().toByteArray().contentEquals(originalState)) { "Recovery control state changed" }
    }
}
