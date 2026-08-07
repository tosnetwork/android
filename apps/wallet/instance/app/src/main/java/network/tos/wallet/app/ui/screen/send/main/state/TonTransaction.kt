package network.tos.wallet.app.ui.screen.send.main.state

import network.tos.icu.Coins
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.data.account.entities.WalletEntity

data class TonTransaction(
    val fromWallet: WalletEntity,
    val destination: SendDestination.TonAccount,
    val token: BalanceEntity,
    val comment: String?,
    val amount: Amount,
    val encryptedComment: Boolean,
    val max: Boolean
) {

    data class Amount(
        val value: Coins = Coins.ZERO,
        val converted: Coins = Coins.ZERO,
        val format: CharSequence = "",
        val convertedFormat: CharSequence = "",
    ) {

        val isEmpty: Boolean
            get() = !value.isPositive
    }
}