package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import org.json.JSONObject
import org.ton.boc.BagOfCells
import java.math.BigInteger
import java.util.UUID

/** A proof-bound unsigned payment; no custody access or broadcast permission. */
class QuantumPreparedPayment internal constructor(
    internal val observation: QuantumAccountReader.Observation, val intent: QuantumPaymentIntent,
    now: Long, ttl: Long, original: QuantumPaymentOutbox.Entry? = null,
) {
    val identity = observation.identity
    val profile = observation.profile
    internal val anchorBytes = profile.anchor()
    internal val walletId: UUID = observation.id
    private val network = QuantumSelfPayNetwork.bind(observation.network, profile.globalId, now, 300)
    val observedBalance: BigInteger = observation.snapshot.balance
    var requiresDeployment: Boolean
        private set
    val expiresAt: Long
    val request: TosQuantumSelfPayRequest
    internal val signingPublicKey: ByteArray
    private val simulationBytes: ByteArray
    init {
        require(now in 1..0xffffffffL && ttl in 1..3600)
        observation.requireFresh(now); network.requireSameCheckpoint(observation.proof, now)
        val state = when (val status = observation.snapshot.status) {
            is QuantumSelfPayAccount.Status.Active -> { requiresDeployment = false; status.state }
            QuantumSelfPayAccount.Status.Absent, QuantumSelfPayAccount.Status.Uninitialized -> {
                requiresDeployment = true; QuantumSelfPayState.parse(identity.wallet.initialData)
            }
            is QuantumSelfPayAccount.Status.Frozen -> error("Wallet is frozen")
        }
        check(observedBalance >= intent.amount) { "Insufficient value before fees" }
        val time = maxOf(now, observation.network.masterchainTime,
            observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(identity)))
        expiresAt = original?.deadline ?: (time + ttl); require(expiresAt in (time + 1)..0xffffffffL)
        request = TosQuantumSelfPayRequest(identity.address, state, QuantumAuthRole.PRIMARY, expiresAt,
            QuantumSelfPayAction.Execute(intent.actions()), time,
            BagOfCells(observation.network.provenConfigParam(48)).roots.single())
        signingPublicKey = state.authority.primaryKey
        if (original != null) {
            check(original.phase == QuantumPaymentOutbox.Phase.SIGNED)
            check(original.identity == identity && sameProfile(original.profile, profile) && original.intent == intent)
            check(original.requestHash.contentEquals(request.digest)) { "Original authorization state changed" }
            requiresDeployment = original.deploy
        }
        simulationBytes = JSONObject().put("mode", "live").put("account", QuantumAccountReader.rawAddress(identity))
            .put("max_age_seconds", 300).toString().toByteArray(Charsets.UTF_8)
    }
    internal fun simulationRequest() = simulationBytes.copyOf()
    fun requireFresh(now: Long) {
        observation.requireFresh(now); network.requireSameCheckpoint(observation.proof, now)
        check(now in 1 until expiresAt) { "Authorization expired" }
    }
}

internal fun sameProfile(a: QuantumNetworkProfile, b: QuantumNetworkProfile) =
    a.globalId == b.globalId && a.rootHash == b.rootHash && a.fileHash == b.fileHash
