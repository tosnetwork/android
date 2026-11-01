package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import java.math.BigInteger
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumPurpose

/** One bounded deployment to the saved probe only. Not a quote, signer or broadcast capability. */
class QuantumPreparedProbeFunding internal constructor(
    private val entry: QuantumRecoveryProbeJournal.Entry,
    private val observation: QuantumAccountReader.Observation,
    val amount: BigInteger,
    now: Long,
    ttl: Long,
    private val handoff: QuantumRecoveryControlHandoff? = null,
    private val restoredDeadline: Long? = null,
) {
    val identity = entry.identity
    val profile = entry.profile
    val probeOperation = entry.operation
    val probeAddress = entry.probe.address
    val probeAddressText get() = QuantumAccountReader.rawAddressFor(probeAddress)
    val expiresAt: Long
    val message: Cell
    val actions: Cell
    val request: TosQuantumSelfPayRequest
    internal val walletId = entry.walletId
    private val stateHash: ByteArray
    private val rescueKey: ByteArray

    init {
        requireAmount(amount)
        require(now in 1..0xffffffffL && ttl in 1..3600)
        check(entry.phase == QuantumRecoveryProbeJournal.Phase.SIGNED)
        entry.requireCurrent(observation, now)
        val current = (QuantumSelfPayAccount.bind(identity, observation.proof, now, 300).snapshot(now).status
            as? QuantumSelfPayAccount.Status.Active)?.state ?: error("Probe funding requires an active wallet")
        stateHash = current.cell.hash().toByteArray()
        rescueKey = current.authority.rescueKey.copyOf()
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = restoredDeadline ?: minOf(time + ttl, entry.deadline)
        check(expiresAt > time && expiresAt <= entry.deadline && expiresAt <= time + 3600)
        val body = BagOfCells(entry.signedBody()).roots.single()
        message = entry.probe.fundingMessage(body, amount)
        actions = entry.probe.fundingActions(body, amount)
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
    internal fun requireProbe(stored: QuantumRecoveryProbeJournal.Entry) {
        check(entry.encode().contentEquals(stored.encode())) { "Saved possession probe changed" }
    }
    internal fun requireSignature(signature: ByteArray) {
        check(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY, rescueKey,
            request.digest, signature)) { "Probe funding signature mismatch" }
    }
    internal fun signedMessage(signature: ByteArray, now: Long): ByteArray {
        requireFresh(now)
        requireSignature(signature)
        return BagOfCells(identity.wallet.external(request.submission(signature), false)).toByteArray()
    }
    internal fun requireReviewedRequest(digest: ByteArray) {
        require(digest.size == 32)
        check(digest.contentEquals(request.digest)) { "Funding request changed after review" }
    }
    internal fun requireStagedMaterial(successor: QuantumSelfPayAuthority, materialDigest: ByteArray) {
        check(successor.hash.contentEquals(entry.successor.hash) &&
            materialDigest.contentEquals(entry.materialDigest)) { "Staged successor material changed" }
    }
    /** Account service invokes this only from the journal's durable SIGNING callback. */
    internal fun signProtected(custody: QuantumCreationCustody, inspection: network.tos.security.pq.QuantumBackupInspection,
                              state: QuantumSelfPayState, now: Long): ByteArray {
        requireFresh(now)
        check(state.cell.hash().toByteArray().contentEquals(stateHash))
        val signatures = entry.signaturePair()
        return try {
            custody.signProbeFunding(walletId, inspection, state, entry.successor, entry.challenge, entry.deadline,
                signatures.first, signatures.second, amount, BagOfCells(request.request).toByteArray(), now)
        } finally { signatures.first.fill(0); signatures.second.fill(0) }
    }
    /** Unsigned codec objects alone cannot authorize a new current-key operation. */
    internal fun requireHandoff(prior: QuantumRecoveryControlJournal.Entry, now: Long) {
        requireFresh(now)
        checkNotNull(handoff) { "Authenticated prior-authorization handoff required" }.requireCurrent(prior, now)
    }
    companion object {
        val MAX_FUNDING: BigInteger = BigInteger("100000000") // Local policy: at most 0.1 TOS, excluding sender gas.
        fun requireAmount(amount: BigInteger) { require(amount.signum() > 0 && amount <= MAX_FUNDING) { "Probe funding exceeds local budget" } }
    }
}
