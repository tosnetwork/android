package network.tos.wallet.app.ui.screen.send.main.state

import network.tos.icu.Coins

data class SendAmountState(
    val remainingFormat: CharSequence = "",
    val convertedFormat: CharSequence = "",
    val converted: Coins = Coins.ZERO,
    val insufficientBalance: Boolean = false,
    val currencyCode: String = "",
    val amountCurrency: Boolean = false,
    val hiddenBalance: Boolean = false,
)