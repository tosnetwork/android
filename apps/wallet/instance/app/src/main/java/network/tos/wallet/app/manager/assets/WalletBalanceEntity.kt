package network.tos.wallet.app.manager.assets

import network.tos.icu.Coins
import network.tos.wallet.data.account.entities.WalletEntity

data class WalletBalanceEntity(
    val accountId: String,
    val testnet: Boolean,
    val balance: Coins
) {

    data class Balances(
        val balances: List<WalletBalanceEntity>
    ) {

        fun getBalance(wallet: WalletEntity): WalletBalanceEntity? {
            return balances.firstOrNull { it.accountId == wallet.accountId && it.testnet == wallet.testnet }
        }
    }
}