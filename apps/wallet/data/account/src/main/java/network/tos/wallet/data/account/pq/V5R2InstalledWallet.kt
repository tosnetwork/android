package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosV5R2AccountState
import network.tos.blockchain.ton.contract.TosV5R2Genesis
import network.tos.blockchain.ton.contract.TosV5R2WalletData
import network.tos.security.pq.V5R2VerifiedRead
import org.ton.block.AddrStd
import org.ton.cell.Cell

/** Authenticated initial installed tuple. Global retirement, solvency, custody and
 * signing/action policy are additional gates; this is not RESCUE_READY approval.
 */
class V5R2InstalledWallet private constructor(val state: TosV5R2WalletData, val nextFeeLeaf: Long) {
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
            return V5R2InstalledWallet(TosV5R2WalletData.parse(walletData, birth),
                TosV5R2AccountState.vaultCounter(vaultData, birth.vaultData))
        }
    }
}
