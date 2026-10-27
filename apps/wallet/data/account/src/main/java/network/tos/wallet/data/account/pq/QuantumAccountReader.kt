package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import network.tos.security.pq.QuantumVerifiedRead
import org.json.JSONArray
import org.json.JSONObject
import org.ton.boc.BagOfCells
import org.ton.cell.CellType
import java.util.UUID

/** Read using the wallet's retained trust root and enrolled rollback checkpoint.
 * Reading funds does not require PRIMARY admission or a usable signing key.
 */
class QuantumAccountReader(
    private val context: Context,
    private val transport: QuantumProofTransport,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    internal data class Observation(
        val id: UUID,
        val identity: TosQuantumSelfPayIdentity,
        val network: QuantumVerifiedRead,
        val proof: QuantumVerifiedRead,
        val snapshot: QuantumSelfPayAccount.Snapshot,
    ) {
        fun requireFresh(now: Long) {
            network.requireLive(now, MAXIMUM_AGE)
            proof.requireLive(now, MAXIMUM_AGE)
            require(now - proof.provenAccountTime(rawAddress(identity)) in -60..MAXIMUM_AGE)
        }
    }

    fun read(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumSelfPayAccount.Snapshot = observe(id, identity).snapshot

    internal fun observe(id: UUID, identity: TosQuantumSelfPayIdentity): Observation {
        val record = QuantumCreationRecord.load(context, id)
        require(record.id == id && record.identity == identity) { "Wallet birth identity mismatch" }
        return observeEnrolled(id, identity, record.profile)
    }

    internal fun readEnrolled(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile): QuantumSelfPayAccount.Snapshot =
        observeEnrolled(id, identity, profile).snapshot

    private fun observeEnrolled(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile): Observation {
        require(identity.wallet.initialAuthority.globalId == profile.globalId) { "Wallet network identity mismatch" }
        val session = QuantumProofSession(context, id, profile.anchor())
        val request = JSONObject().put("mode", "live").put("max_age_seconds", MAXIMUM_AGE)
            .put("config_params", JSONArray(listOf(19))).toString().toByteArray(Charsets.UTF_8)
        val network = session.readBound(request, now(), transport)
        val root = BagOfCells(network.provenConfigParam(19)).roots.single()
        require(root.type == CellType.ORDINARY && root.levelMask.level == 0)
        val config = root.beginParse()
        require(config.loadInt(32).toInt() == profile.globalId) { "Account network global ID mismatch" }
        require(config.remainingBits == 0 && config.refsPosition == config.refs.size)
        val address = rawAddress(identity)
        val proof = session.readBound(network.requestAtCheckpoint(address, maximumAge = MAXIMUM_AGE), now(), transport)
        network.requireSameCheckpoint(proof)
        val account = QuantumSelfPayAccount.bind(identity, proof, now(), MAXIMUM_AGE)
        network.requireLive(now(), MAXIMUM_AGE)
        return Observation(id, identity, network, proof, account.snapshot(now())).also { it.requireFresh(now()) }
    }

    companion object {
        private const val MAXIMUM_AGE = 300L
        internal fun rawAddress(identity: TosQuantumSelfPayIdentity): String =
            "0:" + identity.address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
