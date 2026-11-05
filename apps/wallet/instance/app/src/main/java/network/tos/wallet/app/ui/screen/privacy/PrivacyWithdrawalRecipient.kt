package network.tos.wallet.app.ui.screen.privacy

import network.tos.blockchain.ton.TonAddressTags
import org.ton.block.AddrStd

object PrivacyWithdrawalRecipient {
    fun parse(input: String, testnet: Boolean): AddrStd {
        val address = AddrStd.parse(input)
        require(TonAddressTags.of(input).isTestnet != true || testnet) {
            "Test-only address on a main network wallet"
        }
        require(address.workchainId == 0) { "Withdrawals require workchain 0" }
        return address
    }
}
