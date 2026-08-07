package network.tos.wallet.data.token.entities

import network.tos.icu.Coins
import network.tos.wallet.data.core.currency.WalletCurrency

data class TokenRateEntity(
    val currency: WalletCurrency,
    val fiat: Coins,
    val rate: Coins,
    val rateDiff24h: String,
) {

    companion object {
        val EMPTY = TokenRateEntity(WalletCurrency.TON, Coins.ZERO, Coins.ZERO, "")
    }
}