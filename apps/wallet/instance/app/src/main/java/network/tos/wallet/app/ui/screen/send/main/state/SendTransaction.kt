package network.tos.wallet.app.ui.screen.send.main.state

import network.tos.icu.Coins
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.data.account.entities.WalletEntity

data class SendTransaction(
    val fromWallet: WalletEntity,
    val destination: SendDestination,
    val token: BalanceEntity,
    val comment: String?,
    val amount: Amount,
    val encryptedComment: Boolean,
    val max: Boolean
) {

    fun isRealMax(balance: Coins): Boolean {
        return amount.value >= balance
    }

    data class Amount(
        val value: Coins,
        val converted: Coins,
        val format: CharSequence,
        val convertedFormat: CharSequence,
    )
}