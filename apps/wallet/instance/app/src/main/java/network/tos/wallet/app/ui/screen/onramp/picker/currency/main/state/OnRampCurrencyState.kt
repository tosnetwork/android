package network.tos.wallet.app.ui.screen.onramp.picker.currency.main.state

import network.tos.wallet.data.core.currency.WalletCurrency

data class OnRampCurrencyState(
    val send: WalletCurrency,
    val receive: WalletCurrency,
)