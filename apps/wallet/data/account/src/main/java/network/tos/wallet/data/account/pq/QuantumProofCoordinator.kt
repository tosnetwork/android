package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosQuantumGenesis
import network.tos.blockchain.ton.contract.TosQuantumInstalledRoute
import network.tos.security.pq.QuantumVerifiedRead
import org.ton.cell.Cell
import network.tos.blockchain.ton.contract.TosQuantumAuth
import network.tos.security.pq.QuantumProofSession
import network.tos.security.pq.QuantumProofTransport
import org.json.JSONObject
import org.ton.block.AddrStd

/** Blocking acquisition of a locally enrolled tuple. No keys, signing, funding or broadcast. */
internal class QuantumProofCoordinator(
    private val session: QuantumProofSession, private val birth: TosQuantumGenesis,
    private val transport: QuantumProofTransport, private val clock: () -> Long,
    private val maximumAge: Long, private val successor: TosQuantumGenesis? = null
) {
    init { require(maximumAge in 1..3599) }
    private val route = successor?.let { TosQuantumInstalledRoute.successor(birth, it) } ?: TosQuantumInstalledRoute.initial(birth)
    private fun address(value: AddrStd) = "0:" + value.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun now(): Long {
        check(!Thread.currentThread().isInterrupted) { "Proof observation cancelled" }
        return clock().also { require(it in 1..0xffffffffL) }
    }
    fun observe(initialize: Boolean, primaryExecution: Boolean): QuantumInstalledWallet = collect(initialize, primaryExecution).first
    fun preparePrimaryExecute(initialize: Boolean, actions: Cell, validUntil: Long,
                              custodyPublicKey: (() -> ByteArray)? = null): TosQuantumAuth {
        TosQuantumAuth.validateActions(actions)
        require(validUntil > now()) { "Primary deadline expired before acquisition" }
        val (installed, policy) = collect(initialize, true)
        val request = installed.primaryExecuteRequest(checkNotNull(policy), actions, validUntil, now(), maximumAge)
        custodyPublicKey?.let { installed.requirePrimaryCustody(it(), policy, now(), maximumAge) }
        val finalNow = now()
        installed.requirePrimaryExecution(policy, finalNow, maximumAge)
        installed.requireFeeProof(finalNow, maximumAge)
        require(validUntil > finalNow) { "Primary deadline expired before returning request" }
        return request
    }
    private fun collect(initialize: Boolean, primaryExecution: Boolean): Pair<QuantumInstalledWallet, QuantumVerifiedRead?> {
        val request = JSONObject().put("mode", "live").put("max_age_seconds", maximumAge)
            .put("account", address(birth.address)).toString().toByteArray(Charsets.UTF_8)
        val wallet = if (initialize) session.enrollBound(request, now(), transport)
                     else session.readBound(request, now(), transport)
        val module = session.readBound(wallet.requestAtCheckpoint(address(route.moduleAddress), maximumAge = maximumAge), now(), transport)
        val vault = session.readBound(wallet.requestAtCheckpoint(address(route.vaultAddress), maximumAge = maximumAge), now(), transport)
        val installed = if (successor == null) QuantumInstalledWallet.bindInitial(birth, wallet, module, vault, now(), maximumAge)
                        else QuantumInstalledWallet.bindSuccessor(birth, successor, wallet, module, vault, now(), maximumAge)
        val policy = if (primaryExecution) {
            val proven = session.readBound(wallet.requestAtCheckpoint(configIndices = intArrayOf(48), maximumAge = maximumAge), now(), transport)
            installed.requirePrimaryExecution(proven, now(), maximumAge)
            proven
        } else null
        installed.requireFeeProof(now(), maximumAge)
        return installed to policy
    }
}
