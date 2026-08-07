package network.tos.wallet.app.core

import network.tos.icu.Coins
import network.tos.wallet.app.ui.screen.send.main.helper.InsufficientBalanceType
import network.tos.wallet.data.core.currency.WalletCurrency

class InsufficientFundsException(
    val currency: WalletCurrency,
    val required: Coins,
    val available: Coins,
    val type: InsufficientBalanceType,
    val withRechargeBattery: Boolean,
    val singleWallet: Boolean
) : Exception("Insufficient funds: required $required, available $available, currency $currency") {

}