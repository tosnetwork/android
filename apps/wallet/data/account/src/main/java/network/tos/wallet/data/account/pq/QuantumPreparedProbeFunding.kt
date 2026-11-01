package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell
import java.math.BigInteger

/** One bounded deployment to the saved probe only. Not a quote, signer or broadcast capability. */
class QuantumPreparedProbeFunding internal constructor(
    private val entry: QuantumRecoveryProbeJournal.Entry,
    private val observation: QuantumAccountReader.Observation,
    val amount: BigInteger,
    now: Long,
    ttl: Long,
) {
    val identity = entry.identity
    val profile = entry.profile
    val probeOperation = entry.operation
    val probeAddress = entry.probe.address
    val expiresAt: Long
    val message: Cell
    val actions: Cell
    val request: TosQuantumSelfPayRequest
    internal val walletId = entry.walletId
    private val stateHash: ByteArray

    init {
        requireAmount(amount)
        require(now in 1..0xffffffffL && ttl in 1..3600)
        check(entry.phase == QuantumRecoveryProbeJournal.Phase.SIGNED)
        entry.requireCurrent(observation, now)
        val current = (QuantumSelfPayAccount.bind(identity, observation.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Probe funding requires an active wallet")
        stateHash = current.cell.hash().toByteArray()
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = minOf(time + ttl, entry.deadline)
        check(expiresAt > time)
        val body = BagOfCells(entry.signedBody()).roots.single()
        message = buildCell {
            storeUInt(16, 6) // Internal, IHR disabled, non-bouncing, absent relaxed source.
            storeAddress(probeAddress)
            val count = (amount.bitLength() + 7) / 8
            storeUInt(count, 4); storeUInt(amount, count * 8); storeBit(false)
            storeUInt(0, 4); storeUInt(0, 4); storeUInt(0, 64); storeUInt(0, 32)
            storeBit(true); storeBit(true); storeRef(entry.probe.stateInit) // Initial verified=0 identity.
            storeBit(true); storeRef(body)
        }
        actions = buildCell {
            storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(buildCell { }); storeRef(message)
        }
        request = TosQuantumSelfPayRequest(identity.address, current, QuantumAuthRole.RESCUE, expiresAt,
            QuantumSelfPayAction.Execute(actions), time)
    }
    fun requireFresh(now: Long) {
        entry.requireCurrent(observation, now)
        check(now in 1 until expiresAt) { "Probe funding request expired" }
    }
    internal fun requireRevalidated(fresh: QuantumAccountReader.Observation, now: Long) {
        requireFresh(now); entry.requireCurrent(fresh, now)
        val current = (QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Probe funding state changed")
        check(current.cell.hash().toByteArray().contentEquals(stateHash))
    }
    internal fun requireBound(stored: Cell) {
        val decoded = QuantumStoredSelfPayRequest.parse(stored)
        require(decoded.role == QuantumAuthRole.RESCUE && decoded.kind == 0 &&
            decoded.cell.hash() == request.request.hash() && decoded.executeActions?.hash() == actions.hash())
    }
    companion object {
        val MAX_FUNDING: BigInteger = BigInteger("100000000") // Local policy: at most 0.1 TOS, excluding sender gas.
        fun requireAmount(amount: BigInteger) { require(amount.signum() > 0 && amount <= MAX_FUNDING) { "Probe funding exceeds local budget" } }
    }
}
