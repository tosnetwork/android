package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosQuantumAccountState
import network.tos.blockchain.ton.contract.TosQuantumGenesis
import network.tos.blockchain.ton.contract.TosQuantumWalletData
import network.tos.blockchain.ton.contract.TosQuantumRetirementPolicy
import org.ton.boc.BagOfCells
import network.tos.blockchain.ton.contract.TosQuantumFeeProofTime
import network.tos.security.pq.QuantumVerifiedRead
import org.ton.block.AddrStd
import org.ton.cell.Cell
import network.tos.blockchain.ton.contract.TosQuantumInstalledRoute
import network.tos.blockchain.ton.contract.TosQuantumAuth
import network.tos.blockchain.ton.contract.QuantumAuthRole
import network.tos.blockchain.ton.contract.QuantumAuthAction

/** Authenticated installed tuple. Global retirement, solvency, custody and
 * signing/action policy are additional gates; this is not RESCUE_READY approval.
 */
class QuantumInstalledWallet private constructor(val state: TosQuantumWalletData, val nextFeeLeaf: Long,
                                            private val walletProof: QuantumVerifiedRead,
                                            private val network: ByteArray, private val policy: Int,
                                            private val vaultTime: Long, private val epoch0: Long,
                                            private val globalId: Int, private val walletAddress: AddrStd,
                                            private val moduleAddress: AddrStd, private val walletTime: Long,
                                            private val installedRoute: TosQuantumInstalledRoute,
                                            val walletAccount: TosQuantumAccountState.Snapshot,
                                            val moduleAccount: TosQuantumAccountState.Snapshot,
                                            val vaultAccount: TosQuantumAccountState.Snapshot) {
    /** Checks custody against the immutable module data authenticated during tuple binding. */
    fun requirePrimaryCustody(publicKey: ByteArray, policyProof: QuantumVerifiedRead, localNow: Long, maximumAge: Long) {
        requirePrimaryExecution(policyProof, localNow, maximumAge)
        installedRoute.requirePrimaryKey(publicKey)
    }
    /** Current installed rescue public-key binding; private possession and action eligibility are separate. */
    fun requireRescueCustody(publicKey: ByteArray, localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        installedRoute.requireRescueKey(publicKey)
    }
    /** Chain eligibility only; custody, fee-slot/solvency and signed action checks remain mandatory. */
    fun requirePrimaryExecution(policyProof: QuantumVerifiedRead, localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        walletProof.requireSameCheckpoint(policyProof)
        check(policy == 1 && (state.retired and 2) == 0) { "Primary disabled by installed policy" }
        check(state.seqno < 0xffffffffL && state.primaryNonce < ULong.MAX_VALUE) { "Primary counters exhausted" }
        val root = BagOfCells(policyProof.provenConfigParam(48)).roots.single()
        TosQuantumRetirementPolicy.requirePrimary(root, network, localNow)
    }
    /** Authenticated wire construction only. User approval, custody, solvency and delivery remain separate. */
    fun primaryExecuteRequest(policyProof: QuantumVerifiedRead, actions: Cell, validUntil: Long,
                              localNow: Long, maximumAge: Long): TosQuantumAuth {
        requirePrimaryExecution(policyProof, localNow, maximumAge)
        require(validUntil > localNow) { "Primary deadline expired by local clock" }
        return TosQuantumAuth(globalId, network, walletAddress, moduleAddress, QuantumAuthRole.PRIMARY,
            state.epoch, state.primaryNonce, validUntil, QuantumAuthAction.Execute(actions), walletTime)
    }
    fun requireFeeProof(localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        TosQuantumFeeProofTime.check(walletProof.masterchainTime, vaultTime, localNow, maximumAge, epoch0)
    }
    companion object {
        fun bindInitial(birth: TosQuantumGenesis, wallet: QuantumVerifiedRead, module: QuantumVerifiedRead,
                        vault: QuantumVerifiedRead, localNow: Long, maximumAge: Long): QuantumInstalledWallet =
            bind(birth, TosQuantumInstalledRoute.initial(birth), wallet, module, vault, localNow, maximumAge)

        fun bindSuccessor(birth: TosQuantumGenesis, next: TosQuantumGenesis, wallet: QuantumVerifiedRead, module: QuantumVerifiedRead,
                          vault: QuantumVerifiedRead, localNow: Long, maximumAge: Long): QuantumInstalledWallet =
            bind(birth, TosQuantumInstalledRoute.successor(birth, next), wallet, module, vault, localNow, maximumAge)

        private fun bind(birth: TosQuantumGenesis, route: TosQuantumInstalledRoute, wallet: QuantumVerifiedRead, module: QuantumVerifiedRead,
                         vault: QuantumVerifiedRead, localNow: Long, maximumAge: Long): QuantumInstalledWallet {
            wallet.requireLive(localNow, maximumAge)
            wallet.requireSameCheckpoint(module); wallet.requireSameCheckpoint(vault)
            fun account(proof: QuantumVerifiedRead, address: AddrStd, code: Cell): TosQuantumAccountState.Snapshot {
                val text = "0:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                val hash = code.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                return TosQuantumAccountState.snapshot(proof.accountState(text, hash), address, code)
            }
            val walletAccount = account(wallet, birth.address, birth.walletInit.refs[0])
            val walletData = walletAccount.data
            val moduleAccount = account(module, route.moduleAddress, route.moduleInit.refs[0])
            val moduleData = moduleAccount.data
            val vaultAccount = account(vault, route.vaultAddress, route.vaultInit.refs[0])
            val vaultData = vaultAccount.data
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
            return QuantumInstalledWallet(TosQuantumWalletData.parse(walletData, birth, route.moduleInit, route.metadata),
                TosQuantumAccountState.vaultCounter(vaultData, route.vaultData), wallet, network, policy,
                vault.accountTime(vaultAddress, vaultCode), epoch0, globalId, birth.address, route.moduleAddress,
                wallet.accountTime("0:" + birth.address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) },
                    birth.walletInit.refs[0].hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }), route, walletAccount, moduleAccount, vaultAccount)
        }
    }
}
