package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*

/** Immutable unsigned possession challenge. This is neither possession nor migration authority. */
class QuantumPreparedRecoveryProbe internal constructor(
    private val observation: QuantumAccountReader.Observation,
    val successor: QuantumSelfPayAuthority,
    challenge: ByteArray,
    now: Long,
    ttl: Long,
) {
    val identity = observation.identity
    val profile = observation.profile
    internal val walletId = observation.id
    val expiresAt: Long
    val probe: TosQuantumSelfPayProbe
    private val stateHash: ByteArray

    init {
        require(now in 1..0xffffffffL && ttl in 1..3600)
        observation.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(observation.network, profile.globalId, now)
        observation.network.requireSameCheckpoint(observation.proof)
        val current = (QuantumSelfPayAccount.bind(identity, observation.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Possession preparation requires an active wallet")
        stateHash = current.cell.hash().toByteArray()
        require(successor.globalId == profile.globalId && successor.namespace.contentEquals(current.authority.namespace))
        require(!successor.primaryKey.contentEquals(current.authority.primaryKey) &&
            !successor.rescueKey.contentEquals(current.authority.rescueKey)) { "Both successor roles must use new keys" }
        require(current.retired == 0 || successor.policy == QuantumPolicy.REQUIRED)
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = time + ttl
        require(expiresAt <= 0xffffffffL)
        probe = TosQuantumSelfPayProbe(identity.address, successor, challenge.copyOf(), expiresAt, time)
    }

    fun requireFresh(now: Long) {
        observation.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(observation.network, profile.globalId, now)
        observation.network.requireSameCheckpoint(observation.proof)
        check(now in 1 until expiresAt) { "Possession challenge expired" }
    }

    internal fun requireRevalidated(fresh: QuantumAccountReader.Observation, now: Long) {
        requireFresh(now)
        require(fresh.id == walletId && fresh.identity == identity && sameProfile(fresh.profile, profile))
        fresh.requireFresh(now)
        QuantumSelfPayNetwork.requireRecoveryBase(fresh.network, profile.globalId, now)
        fresh.network.requireSameCheckpoint(fresh.proof)
        val state = (QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Possession wallet state changed")
        check(state.cell.hash().toByteArray().contentEquals(stateHash)) { "Possession wallet state changed" }
    }
}
