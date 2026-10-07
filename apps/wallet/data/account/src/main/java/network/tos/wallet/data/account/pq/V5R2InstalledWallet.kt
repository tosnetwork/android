package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosV5R2AccountState
import network.tos.blockchain.ton.contract.TosV5R2Genesis
import network.tos.blockchain.ton.contract.TosV5R2WalletData
import network.tos.blockchain.ton.contract.TosV5R2RetirementPolicy
import org.ton.boc.BagOfCells
import network.tos.blockchain.ton.contract.TosV5R2FeeProofTime
import network.tos.security.pq.V5R2VerifiedRead
import org.ton.block.AddrStd
import org.ton.cell.Cell
import network.tos.blockchain.ton.contract.TosV5R2InstalledRoute
import network.tos.blockchain.ton.contract.TosV5R2Auth
import network.tos.blockchain.ton.contract.V5R2AuthRole
import network.tos.blockchain.ton.contract.V5R2AuthAction

/** Authenticated installed tuple. Global retirement, solvency, custody and
 * signing/action policy are additional gates; this is not RESCUE_READY approval.
 */
class V5R2InstalledWallet private constructor(val state: TosV5R2WalletData, val nextFeeLeaf: Long,
                                            private val walletProof: V5R2VerifiedRead,
                                            private val network: ByteArray, private val policy: Int,
                                            private val vaultTime: Long, private val epoch0: Long,
                                            private val globalId: Int, private val walletAddress: AddrStd,
                                            private val moduleAddress: AddrStd, private val walletTime: Long) {
    /** Chain eligibility only; custody, fee-slot/solvency and signed action checks remain mandatory. */
    fun requirePrimaryExecution(policyProof: V5R2VerifiedRead, localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        walletProof.requireSameCheckpoint(policyProof)
        check(policy == 1 && (state.retired and 2) == 0) { "Primary disabled by installed policy" }
        check(state.seqno < 0xffffffffL && state.primaryNonce < ULong.MAX_VALUE) { "Primary counters exhausted" }
        val root = BagOfCells(policyProof.provenConfigParam(48)).roots.single()
        TosV5R2RetirementPolicy.requirePrimary(root, network, localNow)
    }
    /** Authenticated wire construction only. User approval, custody, solvency and delivery remain separate. */
    fun primaryExecuteRequest(policyProof: V5R2VerifiedRead, actions: Cell, validUntil: Long,
                              localNow: Long, maximumAge: Long): TosV5R2Auth {
        requirePrimaryExecution(policyProof, localNow, maximumAge)
        require(validUntil > localNow) { "Primary deadline expired by local clock" }
        return TosV5R2Auth(globalId, network, walletAddress, moduleAddress, V5R2AuthRole.PRIMARY,
            state.epoch, state.primaryNonce, validUntil, V5R2AuthAction.Execute(actions), walletTime)
    }
    fun requireFeeProof(localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        TosV5R2FeeProofTime.check(walletProof.masterchainTime, vaultTime, localNow, maximumAge, epoch0)
    }
    companion object {
        fun bindInitial(birth: TosV5R2Genesis, wallet: V5R2VerifiedRead, module: V5R2VerifiedRead,
                        vault: V5R2VerifiedRead, localNow: Long, maximumAge: Long): V5R2InstalledWallet =
            bind(birth, TosV5R2InstalledRoute.initial(birth), wallet, module, vault, localNow, maximumAge)

        fun bindSuccessor(birth: TosV5R2Genesis, next: TosV5R2Genesis, wallet: V5R2VerifiedRead, module: V5R2VerifiedRead,
                          vault: V5R2VerifiedRead, localNow: Long, maximumAge: Long): V5R2InstalledWallet =
            bind(birth, TosV5R2InstalledRoute.successor(birth, next), wallet, module, vault, localNow, maximumAge)

        private fun bind(birth: TosV5R2Genesis, route: TosV5R2InstalledRoute, wallet: V5R2VerifiedRead, module: V5R2VerifiedRead,
                         vault: V5R2VerifiedRead, localNow: Long, maximumAge: Long): V5R2InstalledWallet {
            wallet.requireLive(localNow, maximumAge)
            wallet.requireSameCheckpoint(module); wallet.requireSameCheckpoint(vault)
            fun data(proof: V5R2VerifiedRead, address: AddrStd, code: Cell): Cell {
                val text = "0:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                val hash = code.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                return TosV5R2AccountState.data(proof.accountState(text, hash), address, code)
            }
            val walletData = data(wallet, birth.address, birth.walletInit.refs[0])
            val moduleData = data(module, route.moduleAddress, route.moduleInit.refs[0])
            val vaultData = data(vault, route.vaultAddress, route.vaultInit.refs[0])
            check(moduleData.hash() == route.moduleData.hash()) { "Installed module data mismatch" }
            val identity = moduleData.beginParse()
            identity.loadBits(8)
            val globalId = identity.loadInt(32).toInt()
            val network = identity.loadBits(256).toByteArray()
            identity.loadBits(8 + 256)
            val policy = identity.loadUInt(8).toInt()
            val metadata = route.metadata.beginParse(); metadata.loadBits(272)
            val epoch0 = metadata.loadUInt(32).toLong()
            val vaultAddress = "0:" + route.vaultAddress.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
            val vaultCode = route.vaultInit.refs[0].hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
            return V5R2InstalledWallet(TosV5R2WalletData.parse(walletData, birth, route.moduleInit, route.metadata),
                TosV5R2AccountState.vaultCounter(vaultData, route.vaultData), wallet, network, policy,
                vault.accountTime(vaultAddress, vaultCode), epoch0, globalId, birth.address, route.moduleAddress,
                wallet.accountTime("0:" + birth.address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) },
                    birth.walletInit.refs[0].hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }))
        }
    }
}
