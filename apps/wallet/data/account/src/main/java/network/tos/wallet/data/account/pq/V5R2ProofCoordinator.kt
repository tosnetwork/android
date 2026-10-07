package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosV5R2Genesis
import network.tos.blockchain.ton.contract.TosV5R2InstalledRoute
import network.tos.security.pq.V5R2ProofSession
import network.tos.security.pq.V5R2ProofTransport
import org.json.JSONObject
import org.ton.block.AddrStd

/** Blocking acquisition of a locally enrolled tuple. No keys, signing, funding or broadcast. */
internal class V5R2ProofCoordinator(
    private val session: V5R2ProofSession, private val birth: TosV5R2Genesis,
    private val transport: V5R2ProofTransport, private val clock: () -> Long,
    private val maximumAge: Long, private val successor: TosV5R2Genesis? = null
) {
    init { require(maximumAge in 1..3599) }
    private val route = successor?.let { TosV5R2InstalledRoute.successor(birth, it) } ?: TosV5R2InstalledRoute.initial(birth)
    private fun address(value: AddrStd) = "0:" + value.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun now(): Long {
        check(!Thread.currentThread().isInterrupted) { "Proof observation cancelled" }
        return clock().also { require(it in 1..0xffffffffL) }
    }
    fun observe(initialize: Boolean, primaryExecution: Boolean): V5R2InstalledWallet {
        val request = JSONObject().put("mode", "live").put("max_age_seconds", maximumAge)
            .put("account", address(birth.address)).toString().toByteArray(Charsets.UTF_8)
        val wallet = if (initialize) session.enrollBound(request, now(), transport)
                     else session.readBound(request, now(), transport)
        val module = session.readBound(wallet.requestAtCheckpoint(address(route.moduleAddress), maximumAge = maximumAge), now(), transport)
        val vault = session.readBound(wallet.requestAtCheckpoint(address(route.vaultAddress), maximumAge = maximumAge), now(), transport)
        val installed = if (successor == null) V5R2InstalledWallet.bindInitial(birth, wallet, module, vault, now(), maximumAge)
                        else V5R2InstalledWallet.bindSuccessor(birth, successor, wallet, module, vault, now(), maximumAge)
        if (primaryExecution) {
            val policy = session.readBound(wallet.requestAtCheckpoint(configIndices = intArrayOf(48), maximumAge = maximumAge), now(), transport)
            installed.requirePrimaryExecution(policy, now(), maximumAge)
        }
        installed.requireFeeProof(now(), maximumAge)
        return installed
    }
}
