package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import network.tos.blockchain.ton.contract.TosQuantumRetirementPolicy
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.TosQuantumSelfPayWallet
import network.tos.security.pq.QuantumVerifiedRead
import org.json.JSONArray
import org.json.JSONObject
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellType

/** Authenticated creation network. The proof session provisions the anchor locally.
 * This does not attest to deployment, gas admission, or custody of either key.
 */
class QuantumSelfPayNetwork private constructor(
    val globalId: Int, namespace: ByteArray, private val proof: QuantumVerifiedRead,
    private val policy: Cell, private val maximumAge: Long
) {
    private val namespaceBytes = namespace.copyOf()
    val namespace: ByteArray get() = namespaceBytes.copyOf()

    fun requireFresh(now: Long) {
        proof.requireLive(now, maximumAge)
        TosQuantumRetirementPolicy.requirePrimary(policy, namespaceBytes, policyTime(proof, now))
    }

    fun requireSameCheckpoint(account: QuantumVerifiedRead, now: Long) {
        requireFresh(now)
        account.requireLive(now, maximumAge)
        proof.requireSameCheckpoint(account)
    }

    fun makeIdentity(primaryKey: ByteArray, rescueKey: ByteArray, walletId: Long = 0, now: Long): TosQuantumSelfPayIdentity {
        requireFresh(now)
        val authority = QuantumSelfPayAuthority(globalId, namespaceBytes, primaryKey, rescueKey)
        return TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority, walletId))
    }

    companion object {
        fun request(maximumAge: Long): ByteArray {
            require(maximumAge in 1..3599)
            return JSONObject().put("mode", "live").put("max_age_seconds", maximumAge)
                .put("config_params", JSONArray(listOf(8, 19, 48))).toString().toByteArray(Charsets.UTF_8)
        }

        fun bind(proof: QuantumVerifiedRead, expectedGlobalId: Int, now: Long, maximumAge: Long): QuantumSelfPayNetwork {
            require(maximumAge in 1..3599)
            proof.requireLive(now, maximumAge)
            fun cell(index: Int) = BagOfCells(proof.provenConfigParam(index)).roots.single()
            val version = cell(8)
            val identity = cell(19)
            val policy = cell(48)
            val namespace = validate(version, identity, policy, expectedGlobalId, policyTime(proof, now))
            return QuantumSelfPayNetwork(expectedGlobalId, namespace, proof, policy, maximumAge)
        }

        // Schema validation alone never produces an authenticated capability.
        internal fun validate(version: Cell, identity: Cell, policy: Cell, expectedGlobalId: Int, now: Long): ByteArray {
            fun ordinary(cell: Cell) {
                require(cell.type == CellType.ORDINARY && cell.levelMask.level == 0)
            }
            ordinary(version)
            val v = version.beginParse()
            require(v.loadUInt(8).toInt() == 0xc4 && v.loadUInt(32).toLong() >= 16) { "Quantum requires VM 16" }
            v.loadBits(64)
            require(v.remainingBits == 0 && v.refsPosition == v.refs.size)
            ordinary(identity)
            val i = identity.beginParse()
            require(i.loadInt(32).toInt() == expectedGlobalId) { "Creation network global ID mismatch" }
            require(i.remainingBits == 0 && i.refsPosition == i.refs.size)
            ordinary(policy)
            val p = policy.beginParse()
            require(p.loadUInt(8).toInt() == 0xa1)
            val namespace = p.loadBits(256).toByteArray()
            TosQuantumRetirementPolicy.requirePrimary(policy, namespace, now)
            return namespace
        }

        private fun policyTime(proof: QuantumVerifiedRead, now: Long): Long {
            require(now in 1..0xffffffffL && proof.masterchainTime in 0..0xffffffffL)
            // A still-fresh block must not extend a scheduled PRIMARY deadline.
            return maxOf(now, proof.masterchainTime)
        }
    }
}
