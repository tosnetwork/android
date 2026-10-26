package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
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
    fun read(id: UUID, identity: TosQuantumSelfPayIdentity): QuantumSelfPayAccount.Snapshot {
        val record = QuantumCreationRecord.load(context, id)
        require(record.id == id && record.identity == identity) { "Wallet birth identity mismatch" }
        return readEnrolled(id, identity, record.profile)
    }

    internal fun readEnrolled(id: UUID, identity: TosQuantumSelfPayIdentity, profile: QuantumNetworkProfile): QuantumSelfPayAccount.Snapshot {
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
        val address = "0:" + identity.address.address.toByteArray().joinToString("") { "%02x".format(it) }
        val proof = session.readBound(network.requestAtCheckpoint(address, maximumAge = MAXIMUM_AGE), now(), transport)
        network.requireSameCheckpoint(proof)
        val account = QuantumSelfPayAccount.bind(identity, proof, now(), MAXIMUM_AGE)
        network.requireLive(now(), MAXIMUM_AGE)
        return account.snapshot(now())
    }

    companion object { private const val MAXIMUM_AGE = 300L }
}
