package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosV5R2AccountState
import network.tos.blockchain.ton.contract.TosV5R2Genesis
import network.tos.blockchain.ton.contract.TosV5R2WalletData
import network.tos.blockchain.ton.contract.TosV5R2RetirementPolicy
import org.ton.boc.BagOfCells
import network.tos.security.pq.V5R2VerifiedRead
import org.ton.block.AddrStd
import org.ton.cell.Cell

/** Authenticated initial installed tuple. Global retirement, solvency, custody and
 * signing/action policy are additional gates; this is not RESCUE_READY approval.
 */
class V5R2InstalledWallet private constructor(val state: TosV5R2WalletData, val nextFeeLeaf: Long,
                                            private val walletProof: V5R2VerifiedRead,
                                            private val network: ByteArray, private val policy: Int) {
    /** Chain eligibility only; custody, fee-slot/solvency and signed action checks remain mandatory. */
    fun requirePrimaryExecution(policyProof: V5R2VerifiedRead, localNow: Long, maximumAge: Long) {
        walletProof.requireLive(localNow, maximumAge)
        walletProof.requireSameCheckpoint(policyProof)
        check(policy == 1 && (state.retired and 2) == 0) { "Primary disabled by installed policy" }
        check(state.seqno < 0xffffffffL && state.primaryNonce < ULong.MAX_VALUE) { "Primary counters exhausted" }
        val root = BagOfCells(policyProof.provenConfigParam(48)).roots.single()
        TosV5R2RetirementPolicy.requirePrimary(root, network, localNow)
    }
    companion object {
        fun bindInitial(birth: TosV5R2Genesis, wallet: V5R2VerifiedRead, module: V5R2VerifiedRead,
                        vault: V5R2VerifiedRead, localNow: Long, maximumAge: Long): V5R2InstalledWallet {
            wallet.requireLive(localNow, maximumAge)
            wallet.requireSameCheckpoint(module); wallet.requireSameCheckpoint(vault)
            fun data(proof: V5R2VerifiedRead, address: AddrStd, code: Cell): Cell {
                val text = "0:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                val hash = code.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
                return TosV5R2AccountState.data(proof.accountState(text, hash), address, code)
            }
            val walletData = data(wallet, birth.address, birth.walletInit.refs[0])
            val moduleData = data(module, birth.moduleAddress, birth.moduleInit.refs[0])
            val vaultData = data(vault, birth.vaultAddress, birth.vaultInit.refs[0])
            check(moduleData.hash() == birth.moduleData.hash()) { "Installed module data mismatch" }
            val identity = moduleData.beginParse()
            identity.loadBits(40)
            val network = identity.loadBits(256).toByteArray()
            identity.loadBits(8 + 256)
            val policy = identity.loadUInt(8).toInt()
            return V5R2InstalledWallet(TosV5R2WalletData.parse(walletData, birth),
                TosV5R2AccountState.vaultCounter(vaultData, birth.vaultData), wallet, network, policy)
        }
    }
}
